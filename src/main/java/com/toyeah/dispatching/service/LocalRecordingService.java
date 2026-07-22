package com.toyeah.dispatching.service;

import com.toyeah.dispatching.dto.RecordingPageResponse;
import com.toyeah.dispatching.dto.RecordingResponse;
import com.toyeah.dispatching.dto.RecordingTaskRecord;
import com.toyeah.dispatching.dto.RtcRoomRecord;
import com.toyeah.dispatching.dto.OrderParticipantInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class LocalRecordingService implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(LocalRecordingService.class);
    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");
    private static final long MB = 1024L * 1024L;

    private final Map<String, RecordingTask> taskByID = new ConcurrentHashMap<>();
    private final Map<String, String> latestTaskByRoomID = new ConcurrentHashMap<>();
    private final Map<String, Object> roomLocks = new ConcurrentHashMap<>();

    private final ZegoTokenService zegoTokenService;
    private final OssStorageService ossStorageService;
    private final RecordingTaskPersistenceService recordingPersistenceService;
    private final RoomAccessService roomAccessService;
    private final ChbzgOrderInfoService chbzgOrderInfoService;

    @Value("${recording.enabled:false}")
    private boolean enabled;

    @Value("${recording.work-dir:/tmp/chbzg-rtc-recordings}")
    private String workDir;

    @Value("${recording.command-template:}")
    private String commandTemplate;

    @Value("${recording.max-seconds:600}")
    private int maxSeconds;

    @Value("${recording.upload-max-attempts:3}")
    private int uploadMaxAttempts;

    @Value("${recording.upload-retry-delay-ms:15000}")
    private long uploadRetryDelayMs;

    @Value("${recording.delete-local-after-upload:true}")
    private boolean deleteLocalAfterUpload;

    @Value("${recording.failed-file-retention-hours:72}")
    private int failedFileRetentionHours;

    @Value("${recording.min-free-disk-mb:2048}")
    private long minFreeDiskMb;

    @Value("${recording.oss.play-url-expire-seconds:7200}")
    private long playUrlExpireSeconds;

    public LocalRecordingService(ZegoTokenService zegoTokenService,
                                 OssStorageService ossStorageService,
                                 RecordingTaskPersistenceService recordingPersistenceService,
                                 RoomAccessService roomAccessService,
                                 ChbzgOrderInfoService chbzgOrderInfoService) {
        this.zegoTokenService = zegoTokenService;
        this.ossStorageService = ossStorageService;
        this.recordingPersistenceService = recordingPersistenceService;
        this.roomAccessService = roomAccessService;
        this.chbzgOrderInfoService = chbzgOrderInfoService;
    }

    @PostConstruct
    public void init() {
        recordingPersistenceService.markInterruptedRunningTasks();
    }

    public RecordingResponse start(String roomID) {
        return start(roomID, null);
    }

    public RecordingResponse start(String roomID, String orderID) {
        validateRoomID(roomID);
        synchronized (roomLocks.computeIfAbsent(roomID, key -> new Object())) {
            return startLocked(roomID, normalize(orderID));
        }
    }

    private RecordingResponse startLocked(String roomID, String orderID) {
        if (!enabled || commandTemplate == null || commandTemplate.trim().isEmpty()) {
            throw new UnsupportedOperationException("本地录制未配置。请在服务器设置 RECORDING_ENABLED=true 和 RECORDING_COMMAND_TEMPLATE。");
        }
        ensureEnoughDisk();

        RecordingTask existing = resolveTask(roomID, null);
        if (existing != null && isRunningStatus(existing.status)) {
            return toResponse(existing);
        }

        String taskID = "rec_" + UUID.randomUUID().toString().replace("-", "");
        File outputFile = outputFile(roomID, taskID);
        File logFile = logFile(roomID, taskID);
        ensureDirectory(outputFile.getParentFile(), "创建录制工作目录失败");
        ensureDirectory(logFile.getParentFile(), "创建录制日志目录失败");

        String recorderUserID = "rec_" + sanitize(roomID);
        String token = zegoTokenService.createRecordingToken(roomID, recorderUserID,
                Math.max(600, maxSeconds + 300));
        String command = expandCommand(commandTemplate, roomID, taskID, outputFile, recorderUserID, token);

        RecordingTask task = new RecordingTask();
        task.taskID = taskID;
        task.roomID = roomID;
        task.orderID = orderID;
        task.outputFile = outputFile.getAbsolutePath();
        task.logFile = logFile.getAbsolutePath();
        task.status = "STARTING";
        task.startedAt = System.currentTimeMillis();
        task.createdAt = task.startedAt;
        task.message = "正在启动本地录制进程";
        fillRoomParticipants(task);
        fillParticipantNames(task);
        remember(task);
        persist(task);

        try {
            Process process = new ProcessBuilder("/bin/sh", "-lc", command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                    .start();
            task.process = process;
            if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                task.status = "FAILED";
                task.message = "本地录制进程启动后立即退出，退出码：" + process.exitValue()
                        + "，请查看日志：" + logFile.getAbsolutePath();
                task.lastError = task.message;
                task.endedAt = System.currentTimeMillis();
                persist(task);
                throw new IllegalStateException(task.message);
            }
            task.status = "RUNNING";
            task.message = "本地录制进程已启动，日志：" + logFile.getAbsolutePath();
            persist(task);
            return toResponse(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (task.process != null && task.process.isAlive()) {
                task.process.destroyForcibly();
            }
            task.status = "FAILED";
            task.message = "等待本地录制进程启动时被中断";
            task.lastError = task.message;
            task.endedAt = System.currentTimeMillis();
            persist(task);
            throw new IllegalStateException(task.message, e);
        } catch (IOException e) {
            task.status = "FAILED";
            task.message = "启动本地录制进程失败：" + e.getMessage();
            task.lastError = e.getMessage();
            task.endedAt = System.currentTimeMillis();
            persist(task);
            throw new IllegalStateException("启动本地录制进程失败", e);
        }
    }

    public RecordingResponse stop(String roomID, String taskID) {
        RecordingTask task = resolveTask(roomID, taskID);
        if (task == null) {
            throw new IllegalArgumentException("未找到录制任务");
        }
        synchronized (roomLocks.computeIfAbsent(task.roomID, key -> new Object())) {
            if (isRunningStatus(task.status)) {
                stopProcess(task);
                task.status = "STOPPED";
                task.message = "录制已停止";
                task.endedAt = System.currentTimeMillis();
                refreshFileMeta(task);
                persist(task);
            }
        }
        uploadIfPossible(task);
        return toResponse(task);
    }

    public RecordingResponse findByRoomID(String roomID) {
        validateRoomID(roomID);
        RecordingTask task = resolveTask(roomID, null);
        return task == null ? null : toResponse(task);
    }

    public RecordingResponse findByTaskID(String taskID) {
        RecordingTask task = resolveTask(null, taskID);
        return task == null ? null : toResponse(task);
    }

    public List<RecordingResponse> list(String roomID, String orderID, String status, int page, int size) {
        if (recordingPersistenceService.isAvailable()) {
            List<RecordingTaskRecord> records = recordingPersistenceService.list(roomID, orderID, status, page, size);
            List<RecordingResponse> responses = new ArrayList<>();
            for (RecordingTaskRecord record : records) {
                responses.add(toResponse(fromRecord(record)));
            }
            return responses;
        }
        List<RecordingTask> tasks = new ArrayList<>(taskByID.values());
        tasks.sort(Comparator.comparingLong((RecordingTask task) -> task.startedAt).reversed());
        List<RecordingResponse> responses = new ArrayList<>();
        for (RecordingTask task : tasks) {
            if (!isBlank(roomID) && !roomID.trim().equals(task.roomID)) {
                continue;
            }
            if (!isBlank(orderID) && !orderID.trim().equals(task.orderID)) {
                continue;
            }
            if (!isBlank(status) && !status.trim().equals(task.status)) {
                continue;
            }
            responses.add(toResponse(task));
        }
        return responses;
    }

    public RecordingPageResponse listPage(String roomID, String orderID, String status, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        long total;
        List<RecordingResponse> responses = new ArrayList<>();
        if (recordingPersistenceService.isAvailable()) {
            total = recordingPersistenceService.count(roomID, orderID, status);
            List<RecordingTaskRecord> records = recordingPersistenceService.list(roomID, orderID, status, safePage, safeSize);
            for (RecordingTaskRecord record : records) {
                responses.add(toResponse(fromRecord(record)));
            }
        } else {
            List<RecordingResponse> filtered = list(roomID, orderID, status, 1, safeSize);
            total = filtered.size();
            int fromIndex = Math.min((safePage - 1) * safeSize, filtered.size());
            int toIndex = Math.min(fromIndex + safeSize, filtered.size());
            responses.addAll(filtered.subList(fromIndex, toIndex));
        }
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        RecordingPageResponse response = new RecordingPageResponse();
        response.setList(responses);
        response.setPage(safePage);
        response.setSize(safeSize);
        response.setTotal(total);
        response.setTotalPages(totalPages);
        response.setHasPreviousPage(safePage > 1);
        response.setHasNextPage(totalPages > 0 && safePage < totalPages);
        return response;
    }

    public String generatePlayUrl(String taskID) {
        RecordingTask task = resolveTask(null, taskID);
        if (task == null) {
            throw new IllegalArgumentException("未找到录制任务");
        }
        if (isBlank(task.ossObjectKey)) {
            throw new IllegalArgumentException("录制文件尚未上传到 OSS，无法播放");
        }
        return ossStorageService.generateSignedUrl(task.ossObjectKey, playUrlExpireSeconds);
    }

    public RecordingResponse retryUpload(String taskID) {
        RecordingTask task = resolveTask(null, taskID);
        if (task == null) {
            throw new IllegalArgumentException("未找到录制任务");
        }
        if (!isBlank(task.ossObjectKey)) {
            return toResponse(task);
        }
        uploadIfPossible(task);
        return toResponse(task);
    }

    @Override
    public void destroy() {
        for (RecordingTask task : taskByID.values()) {
            if (task.process == null || !task.process.isAlive()) {
                continue;
            }
            try {
                logger.info("服务关闭，停止录制任务 {}，roomID={}", task.taskID, task.roomID);
                stopProcess(task);
                task.status = "ABNORMAL";
                task.message = appendMessage(task.message, "服务关闭，录制进程已停止");
                task.lastError = "服务关闭，录制进程已停止";
                task.endedAt = System.currentTimeMillis();
                refreshFileMeta(task);
                persist(task);
            } catch (Exception e) {
                logger.error("服务关闭时停止录制任务失败，taskID={}", task.taskID, e);
                task.process.destroyForcibly();
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void reapFinishedTasks() {
        for (RecordingTask task : taskByID.values()) {
            if ("UPLOAD_FAILED".equals(task.status)
                    && task.uploadAttempts < Math.max(1, uploadMaxAttempts)
                    && System.currentTimeMillis() >= task.nextUploadAt) {
                uploadIfPossible(task);
                continue;
            }
            if (!"RUNNING".equals(task.status) || task.process == null) {
                continue;
            }
            if (!task.process.isAlive()) {
                task.status = task.process.exitValue() == 0 ? "COMPLETED" : "FAILED";
                task.message = "录制进程已退出，退出码：" + task.process.exitValue();
                if (task.process.exitValue() != 0) {
                    task.lastError = task.message;
                }
                task.endedAt = System.currentTimeMillis();
                refreshFileMeta(task);
                persist(task);
                uploadIfPossible(task);
                continue;
            }
            if (maxSeconds > 0 && System.currentTimeMillis() - task.startedAt > maxSeconds * 1000L) {
                logger.warn("录制任务 {} 已超过最大录制时长，准备停止", task.taskID);
                stop(task.roomID, task.taskID);
            }
        }
        retryPersistedUploadFailures();
        cleanupExpiredFailedLocalFiles();
    }

    private void uploadIfPossible(RecordingTask task) {
        synchronized (task) {
            if (task.uploading || !isBlank(task.ossObjectKey)) {
                return;
            }
            task.uploading = true;
        }
        try {
            File file = new File(task.outputFile == null ? "" : task.outputFile);
            if (!file.exists() || !file.isFile() || file.length() <= 0) {
                task.message = appendMessage(task.message, "未找到有效录制输出文件");
                task.lastError = "未找到有效录制输出文件";
                persist(task);
                return;
            }
            if (!ossStorageService.isEnabled()) {
                task.message = appendMessage(task.message, "OSS 上传未启用");
                persist(task);
                return;
            }
            task.uploadAttempts++;
            refreshFileMeta(task);
            OssStorageService.UploadResult uploadResult = ossStorageService.upload(file, task.roomID, task.taskID);
            if (uploadResult == null) {
                task.message = appendMessage(task.message, "OSS 上传未启用");
                persist(task);
                return;
            }
            task.ossBucket = uploadResult.getBucket();
            task.ossObjectKey = uploadResult.getObjectKey();
            task.ossUrl = uploadResult.getUrl();
            task.uploadedAt = System.currentTimeMillis();
            task.status = "UPLOADED";
            task.message = appendMessage(task.message, "已上传到 OSS");
            task.lastError = null;
            deleteLocalFileAfterUpload(task, file);
            persist(task);
        } catch (Exception e) {
            logger.error("录制文件上传 OSS 失败，taskID={}", task.taskID, e);
            task.nextUploadAt = System.currentTimeMillis() + Math.max(1000L, uploadRetryDelayMs);
            task.status = "UPLOAD_FAILED";
            task.lastError = e.getMessage();
            task.message = appendMessage(task.message, "OSS 上传失败（第 " + task.uploadAttempts + " 次）：" + e.getMessage());
            persist(task);
        } finally {
            task.uploading = false;
        }
    }

    private void retryPersistedUploadFailures() {
        if (!recordingPersistenceService.isAvailable()) {
            return;
        }
        List<RecordingTaskRecord> records = recordingPersistenceService.list(null, null, "UPLOAD_FAILED", 1, 50);
        long now = System.currentTimeMillis();
        for (RecordingTaskRecord record : records) {
            if (record.getUploadAttempts() >= Math.max(1, uploadMaxAttempts)) {
                continue;
            }
            if (record.getNextUploadAt() > now) {
                continue;
            }
            RecordingTask task = taskByID.computeIfAbsent(record.getTaskID(), key -> fromRecord(record));
            uploadIfPossible(task);
        }
    }

    private void cleanupExpiredFailedLocalFiles() {
        if (!recordingPersistenceService.isAvailable() || failedFileRetentionHours <= 0) {
            return;
        }
        List<RecordingTaskRecord> records = recordingPersistenceService.list(null, null, "UPLOAD_FAILED", 1, 100);
        long deadline = System.currentTimeMillis() - failedFileRetentionHours * 3600_000L;
        for (RecordingTaskRecord record : records) {
            if (record.isLocalFileDeleted() || isBlank(record.getOutputFile())) {
                continue;
            }
            long baseTime = record.getEndedAt() > 0 ? record.getEndedAt() : record.getStartedAt();
            if (baseTime <= 0 || baseTime > deadline) {
                continue;
            }
            File file = new File(record.getOutputFile());
            if (file.exists() && file.isFile() && !file.delete()) {
                logger.warn("清理过期录制临时文件失败：{}", file.getAbsolutePath());
                continue;
            }
            RecordingTask task = fromRecord(record);
            task.localFileDeleted = true;
            task.localFileDeletedAt = System.currentTimeMillis();
            task.message = appendMessage(task.message, "上传失败文件超过保留时间，已清理本地临时文件");
            persist(task);
        }
    }

    private void stopProcess(RecordingTask task) {
        if (task.process == null || !task.process.isAlive()) {
            return;
        }
        task.process.destroy();
        try {
            if (!task.process.waitFor(10, TimeUnit.SECONDS)) {
                task.process.destroyForcibly();
                if (!task.process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("录制进程无法停止，请检查进程状态");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.process.destroyForcibly();
            try {
                task.process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private RecordingTask resolveTask(String roomID, String taskID) {
        if (!isBlank(taskID)) {
            RecordingTask task = taskByID.get(taskID.trim());
            if (task == null) {
                task = fromRecord(recordingPersistenceService.findByTaskID(taskID.trim()));
                if (task != null) {
                    remember(task);
                }
            }
            if (task != null && !isBlank(roomID) && !task.roomID.equals(roomID.trim())) {
                throw new IllegalArgumentException("录制任务与房间号不匹配");
            }
            return task;
        }
        validateRoomID(roomID);
        String latestTaskID = latestTaskByRoomID.get(roomID);
        RecordingTask task = latestTaskID == null ? null : taskByID.get(latestTaskID);
        if (task != null) {
            return task;
        }
        task = fromRecord(recordingPersistenceService.findLatestByRoomID(roomID));
        if (task != null) {
            remember(task);
        }
        return task;
    }

    private void remember(RecordingTask task) {
        if (task == null || isBlank(task.taskID)) {
            return;
        }
        taskByID.put(task.taskID, task);
        if (!isBlank(task.roomID)) {
            latestTaskByRoomID.put(task.roomID, task.taskID);
        }
    }

    private void persist(RecordingTask task) {
        recordingPersistenceService.save(toRecord(task));
    }

    private RecordingTaskRecord toRecord(RecordingTask task) {
        RecordingTaskRecord record = new RecordingTaskRecord();
        record.setTaskID(task.taskID);
        record.setRoomID(task.roomID);
        record.setOrderID(task.orderID);
        record.setDoctorUserID(task.doctorUserID);
        record.setPeerUserID(task.peerUserID);
        record.setDoctorName(task.doctorName);
        record.setPeerName(task.peerName);
        record.setStatus(task.status);
        record.setOutputFile(task.outputFile);
        record.setLogFile(task.logFile);
        record.setLocalFileDeleted(task.localFileDeleted);
        record.setLocalFileDeletedAt(task.localFileDeletedAt);
        record.setOssBucket(task.ossBucket);
        record.setOssObjectKey(task.ossObjectKey);
        record.setOssUrl(task.ossUrl);
        record.setFileSize(task.fileSize);
        record.setDurationSeconds(task.durationSeconds);
        record.setStartedAt(task.startedAt);
        record.setEndedAt(task.endedAt);
        record.setUploadedAt(task.uploadedAt);
        record.setUploadAttempts(task.uploadAttempts);
        record.setNextUploadAt(task.nextUploadAt);
        record.setMessage(task.message);
        record.setLastError(task.lastError);
        record.setCreatedAt(task.createdAt);
        return record;
    }

    private RecordingTask fromRecord(RecordingTaskRecord record) {
        if (record == null) {
            return null;
        }
        RecordingTask task = new RecordingTask();
        task.taskID = record.getTaskID();
        task.roomID = record.getRoomID();
        task.orderID = record.getOrderID();
        task.doctorUserID = record.getDoctorUserID();
        task.peerUserID = record.getPeerUserID();
        task.doctorName = record.getDoctorName();
        task.peerName = record.getPeerName();
        task.status = record.getStatus();
        task.outputFile = record.getOutputFile();
        task.logFile = record.getLogFile();
        task.localFileDeleted = record.isLocalFileDeleted();
        task.localFileDeletedAt = record.getLocalFileDeletedAt();
        task.ossBucket = record.getOssBucket();
        task.ossObjectKey = record.getOssObjectKey();
        task.ossUrl = record.getOssUrl();
        task.fileSize = record.getFileSize();
        task.durationSeconds = record.getDurationSeconds();
        task.startedAt = record.getStartedAt();
        task.endedAt = record.getEndedAt();
        task.uploadedAt = record.getUploadedAt();
        task.uploadAttempts = record.getUploadAttempts();
        task.nextUploadAt = record.getNextUploadAt();
        task.message = record.getMessage();
        task.lastError = record.getLastError();
        task.createdAt = record.getCreatedAt();
        return task;
    }

    private RecordingResponse toResponse(RecordingTask task) {
        if (fillParticipantNames(task)) {
            persist(task);
        }
        RecordingResponse response = new RecordingResponse();
        response.setTaskID(task.taskID);
        response.setRoomID(task.roomID);
        response.setOrderID(task.orderID);
        response.setDoctorUserID(task.doctorUserID);
        response.setPeerUserID(task.peerUserID);
        response.setDoctorName(task.doctorName);
        response.setPeerName(task.peerName);
        response.setStatus(task.status);
        response.setOutputFile(task.outputFile);
        response.setLogFile(task.logFile);
        response.setLocalFileDeleted(task.localFileDeleted);
        response.setLocalFileDeletedAt(task.localFileDeletedAt);
        response.setOssBucket(task.ossBucket);
        response.setOssObjectKey(task.ossObjectKey);
        response.setOssUrl(task.ossUrl);
        response.setFileSize(task.fileSize);
        response.setDurationSeconds(task.durationSeconds);
        response.setMessage(task.message);
        response.setLastError(task.lastError);
        response.setStartedAt(task.startedAt);
        response.setEndedAt(task.endedAt);
        response.setUploadedAt(task.uploadedAt);
        response.setUploadAttempts(task.uploadAttempts);
        return response;
    }

    private void fillRoomParticipants(RecordingTask task) {
        try {
            RtcRoomRecord room = roomAccessService.findRoom(task.roomID);
            if (room == null) {
                return;
            }
            task.doctorUserID = room.getDoctorUserID();
            task.peerUserID = room.getPeerUserID();
        } catch (Exception e) {
            logger.warn("读取房间参与者失败，roomID={}", task.roomID, e);
        }
    }

    private boolean fillParticipantNames(RecordingTask task) {
        if (task == null || isBlank(task.orderID) || (!isBlank(task.doctorName) && !isBlank(task.peerName))) {
            return false;
        }
        try {
            OrderParticipantInfo info = chbzgOrderInfoService.findByOrderID(task.orderID);
            if (info == null) {
                return false;
            }
            boolean changed = false;
            if (isBlank(task.doctorName) && !isBlank(info.getDoctorName())) {
                task.doctorName = info.getDoctorName();
                changed = true;
            }
            if (isBlank(task.peerName) && !isBlank(info.getPeerName())) {
                task.peerName = info.getPeerName();
                changed = true;
            }
            return changed;
        } catch (Exception e) {
            logger.warn("补充录制任务参与人姓名失败，taskID={}, orderID={}", task.taskID, task.orderID, e);
            return false;
        }
    }

    private void refreshFileMeta(RecordingTask task) {
        File file = new File(task.outputFile == null ? "" : task.outputFile);
        if (!file.exists() || !file.isFile()) {
            return;
        }
        task.fileSize = file.length();
        Double duration = probeDurationSeconds(file);
        if (duration != null) {
            task.durationSeconds = duration;
        }
    }

    private Double probeDurationSeconds(File file) {
        Process process = null;
        try {
            process = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries",
                    "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
                    file.getAbsolutePath()).start();
            String output;
            try (Scanner scanner = new Scanner(process.getInputStream(), "UTF-8").useDelimiter("\\A")) {
                output = scanner.hasNext() ? scanner.next().trim() : "";
            }
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0 || output.isEmpty()) {
                return null;
            }
            return Double.parseDouble(output);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void deleteLocalFileAfterUpload(RecordingTask task, File file) {
        if (!deleteLocalAfterUpload) {
            return;
        }
        if (!file.exists()) {
            task.localFileDeleted = true;
            task.localFileDeletedAt = System.currentTimeMillis();
            return;
        }
        if (file.delete()) {
            task.localFileDeleted = true;
            task.localFileDeletedAt = System.currentTimeMillis();
            task.message = appendMessage(task.message, "本地临时 MP4 已删除");
            return;
        }
        task.message = appendMessage(task.message, "本地临时 MP4 删除失败，请检查文件权限");
    }

    private void ensureEnoughDisk() {
        File dir = new File(workDir);
        ensureDirectory(dir, "创建录制根目录失败");
        long usableMb = dir.getUsableSpace() / MB;
        if (minFreeDiskMb > 0 && usableMb < minFreeDiskMb) {
            throw new IllegalStateException("服务器录制磁盘空间不足，请稍后再试");
        }
    }

    private void ensureDirectory(File dir, String message) {
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                throw new IllegalStateException(message + "：" + dir.getAbsolutePath());
            }
            return;
        }
        if (!dir.mkdirs()) {
            throw new IllegalStateException(message + "：" + dir.getAbsolutePath());
        }
    }

    private File outputFile(String roomID, String taskID) {
        return new File(roomWorkDir(roomID), taskID + ".mp4");
    }

    private File logFile(String roomID, String taskID) {
        return new File(new File(roomWorkDir(roomID), "logs"), taskID + ".log");
    }

    private File roomWorkDir(String roomID) {
        return new File(workDir, sanitize(roomID));
    }

    private String expandCommand(String template,
                                 String roomID,
                                 String taskID,
                                 File outputFile,
                                 String recorderUserID,
                                 String token) {
        return template
                .replace("{roomID}", shellQuote(roomID))
                .replace("{taskID}", shellQuote(taskID))
                .replace("{outputFile}", shellQuote(outputFile.getAbsolutePath()))
                .replace("{appID}", shellQuote(String.valueOf(zegoTokenService.getAppID())))
                .replace("{server}", shellQuote(zegoTokenService.getServer()))
                .replace("{userID}", shellQuote(recorderUserID))
                .replace("{token}", shellQuote(token));
    }

    private boolean isRunningStatus(String status) {
        return "RUNNING".equals(status) || "STARTING".equals(status);
    }

    private void validateRoomID(String roomID) {
        if (roomID == null || !ROOM_ID_PATTERN.matcher(roomID.trim()).matches()) {
            throw new IllegalArgumentException("房间号只能包含字母、数字、下划线、横线、点号和冒号，长度必须为 1-128 个字符");
        }
    }

    private String appendMessage(String current, String addition) {
        if (isBlank(addition)) {
            return current;
        }
        if (isBlank(current)) {
            return addition;
        }
        return current + "；" + addition;
    }

    private String sanitize(String value) {
        return value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class RecordingTask {
        private String taskID;
        private String roomID;
        private String orderID;
        private String doctorUserID;
        private String peerUserID;
        private String doctorName;
        private String peerName;
        private String status;
        private String outputFile;
        private String logFile;
        private boolean localFileDeleted;
        private long localFileDeletedAt;
        private String ossBucket;
        private String ossObjectKey;
        private String ossUrl;
        private long fileSize;
        private Double durationSeconds;
        private String message;
        private String lastError;
        private long startedAt;
        private long endedAt;
        private long uploadedAt;
        private int uploadAttempts;
        private long nextUploadAt;
        private long createdAt;
        private boolean uploading;
        private Process process;
    }
}
