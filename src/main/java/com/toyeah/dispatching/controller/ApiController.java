package com.toyeah.dispatching.controller;

import java.security.SecureRandom;
import com.toyeah.dispatching.dto.RecordingPageResponse;
import com.toyeah.dispatching.dto.RecordingResponse;
import com.toyeah.dispatching.dto.RtcRoomRecord;
import com.toyeah.dispatching.dto.RtcSessionRequest;
import com.toyeah.dispatching.dto.RtcSessionResponse;
import com.toyeah.dispatching.service.LocalRecordingService;
import com.toyeah.dispatching.service.RoomAccessService;
import com.toyeah.dispatching.service.ZegoTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Logger logger = LoggerFactory.getLogger(ApiController.class);
    private static final SecureRandom RTC_USER_ID_RANDOM = new SecureRandom();
    private final ZegoTokenService zegoTokenService;
    private final LocalRecordingService localRecordingService;
    private final RoomAccessService roomAccessService;

    @Value("${recording.admin-token:}")
    private String recordingAdminToken;

    @Value("${rtc.server-token:}")
    private String rtcServerToken;

    public ApiController(ZegoTokenService zegoTokenService,
                         LocalRecordingService localRecordingService,
                         RoomAccessService roomAccessService) {
        this.zegoTokenService = zegoTokenService;
        this.localRecordingService = localRecordingService;
        this.roomAccessService = roomAccessService;
    }

    @PostMapping("/rtc/session")
    public ResponseEntity<?> createRtcSession(@RequestBody RtcSessionRequest request,
                                              HttpServletRequest httpRequest) {
        try {
            fillRequestMeta(request, httpRequest, "web");
            prepareZegoUserID(request);
            RoomAccessService.EnterResult enterResult = roomAccessService.enter(request);
            try {
                RtcSessionResponse response = zegoTokenService.createSession(request);
                response.setLeaseID(enterResult.getLeaseID());
                return ResponseEntity.ok(response);
            } catch (Exception e) {
                roomAccessService.rollbackEnter(request, enterResult);
                throw e;
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (IllegalStateException e) {
            logger.error("ZEGO 会话配置错误", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(e.getMessage()));
        } catch (Exception e) {
            logger.error("创建 ZEGO 会话失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("创建 ZEGO 会话失败"));
        }
    }

    @GetMapping("/rtc/session")
    public ResponseEntity<?> createRtcSessionByGet(@RequestParam String roomID,
                                                   @RequestParam String userID,
                                                   @RequestParam(required = false) String streamID,
                                                   @RequestParam(required = false) String leaseID,
                                                   @RequestParam(required = false) Integer ttlSec,
                                                   @RequestParam(required = false) String role,
                                                   @RequestParam(required = false) String ticket,
                                                   @RequestParam(required = false) String orderID,
                                                   @RequestParam(required = false) String clientType,
                                                   HttpServletRequest httpRequest) {
        RtcSessionRequest request = new RtcSessionRequest();
        request.setRoomID(roomID);
        request.setUserID(userID);
        request.setStreamID(streamID);
        request.setLeaseID(leaseID);
        request.setTtlSec(ttlSec);
        request.setRole(role);
        request.setTicket(ticket);
        request.setOrderID(orderID);
        request.setClientType(clientType);
        return createRtcSession(request, httpRequest);
    }

    /**
     * Backward-compatible endpoint used by the old mini program. New clients should call
     * /api/rtc/session because they also need appID, server, userID and streamID.
     */
    @GetMapping("/getToken")
    public ResponseEntity<String> getToken(@RequestParam String cname,
                                           @RequestParam String uid,
                                           @RequestParam(required = false, defaultValue = "3600") int ttlSec,
                                           @RequestParam(required = false) String role,
                                           @RequestParam(required = false) String orderID,
                                           @RequestParam(required = false) String clientType,
                                           HttpServletRequest httpRequest) {
        try {
            RtcSessionRequest request = new RtcSessionRequest();
            request.setRoomID(cname);
            request.setUserID(uid);
            request.setTtlSec(ttlSec);
            request.setRole(role);
            request.setOrderID(orderID);
            request.setClientType(clientType);
            fillRequestMeta(request, httpRequest, "web");
            RoomAccessService.EnterResult enterResult = roomAccessService.enter(request);
            RtcSessionResponse response;
            try {
                response = zegoTokenService.createSession(request);
            } catch (Exception e) {
                roomAccessService.rollbackEnter(request, enterResult);
                throw e;
            }
            return ResponseEntity.ok(response.getToken());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("生成兼容 Token 失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("生成 Token 失败");
        }
    }

    @PostMapping("/rtc/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestBody RtcSessionRequest request,
                                       HttpServletRequest httpRequest) {
        try {
            fillRequestMeta(request, httpRequest, null);
            roomAccessService.heartbeat(request);
            return ResponseEntity.ok(Collections.singletonMap("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @PostMapping("/rtc/leave")
    public ResponseEntity<?> leaveRtcRoom(@RequestBody RtcSessionRequest request,
                                          HttpServletRequest httpRequest) {
        try {
            fillRequestMeta(request, httpRequest, null);
            roomAccessService.leave(request);
            return ResponseEntity.ok(Collections.singletonMap("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @PostMapping("/rtc/end")
    public ResponseEntity<?> endRtcRoom(@RequestBody RtcSessionRequest request,
                                        HttpServletRequest httpRequest) {
        try {
            fillRequestMeta(request, httpRequest, null);
            roomAccessService.end(request);
            return ResponseEntity.ok(Collections.singletonMap("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @PostMapping("/rtc/reopen")
    public ResponseEntity<?> reopenRtcRoom(@RequestBody RtcSessionRequest request,
                                           HttpServletRequest httpRequest) {
        try {
            fillRequestMeta(request, httpRequest, "server");
            if (!rtcServerAllowed(request, httpRequest)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("无权重开视频房间"));
            }
            roomAccessService.reopen(request);
            return ResponseEntity.ok(Collections.singletonMap("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @GetMapping("/rtc/status")
    public ResponseEntity<?> getRtcRoomStatus(@RequestParam String roomID) {
        try {
            RtcRoomRecord record = roomAccessService.findRoom(roomID);
            if (record == null) {
                return ResponseEntity.ok(Collections.singletonMap("status", "NONE"));
            }
            return ResponseEntity.ok(record);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @PostMapping("/recording/start")
    public ResponseEntity<?> startRecording(@RequestBody Map<String, String> body) {
        try {
            String roomID = body == null ? null : body.get("roomID");
            String orderID = body == null ? null : body.get("orderID");
            RecordingResponse response = localRecordingService.start(roomID, orderID);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(error(e.getMessage()));
        } catch (Exception e) {
            logger.error("启动本地录制失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("启动本地录制失败"));
        }
    }

    @PostMapping("/recording/stop")
    public ResponseEntity<?> stopRecording(@RequestBody Map<String, String> body) {
        try {
            String roomID = body == null ? null : body.get("roomID");
            String taskID = body == null ? null : body.get("taskID");
            RecordingResponse response = localRecordingService.stop(roomID, taskID);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            logger.error("停止本地录制失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("停止本地录制失败"));
        }
    }

    @GetMapping("/recording/tasks")
    public ResponseEntity<?> getRecordingTask(@RequestParam(required = false) String roomID,
                                              @RequestParam(required = false) String taskID) {
        try {
            if (taskID != null && !taskID.trim().isEmpty()) {
                return ResponseEntity.ok(localRecordingService.findByTaskID(taskID));
            }
            return ResponseEntity.ok(localRecordingService.findByRoomID(roomID));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @GetMapping("/recording/tasks/{taskID}/play-url")
    public ResponseEntity<?> getRecordingPlayUrl(@PathVariable String taskID,
                                                 HttpServletRequest request) {
        return getAdminRecordingPlayUrl(taskID, request);
    }

    @GetMapping("/admin/recordings")
    public ResponseEntity<?> listAdminRecordings(@RequestParam(required = false) String roomID,
                                                 @RequestParam(required = false) String orderID,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false, defaultValue = "1") int page,
                                                 @RequestParam(required = false, defaultValue = "50") int size,
                                                 HttpServletRequest request) {
        if (!adminAllowed(request)) {
            return adminUnauthorized();
        }
        return ResponseEntity.ok(localRecordingService.list(roomID, orderID, status, page, size));
    }

    @GetMapping("/admin/recordings/page")
    public ResponseEntity<?> pageAdminRecordings(@RequestParam(required = false) String roomID,
                                                 @RequestParam(required = false) String orderID,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false, defaultValue = "1") int page,
                                                 @RequestParam(required = false, defaultValue = "50") int size,
                                                 HttpServletRequest request) {
        if (!adminAllowed(request)) {
            return adminUnauthorized();
        }
        RecordingPageResponse response = localRecordingService.listPage(roomID, orderID, status, page, size);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/admin/recordings/{taskID}")
    public ResponseEntity<?> getAdminRecording(@PathVariable String taskID,
                                               HttpServletRequest request) {
        if (!adminAllowed(request)) {
            return adminUnauthorized();
        }
        try {
            RecordingResponse response = localRecordingService.findByTaskID(taskID);
            if (response == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("未找到录制任务"));
            }
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    @GetMapping("/admin/recordings/{taskID}/play-url")
    public ResponseEntity<?> getAdminRecordingPlayUrl(@PathVariable String taskID,
                                                      HttpServletRequest request) {
        if (!adminAllowed(request)) {
            return adminUnauthorized();
        }
        try {
            String playUrl = localRecordingService.generatePlayUrl(taskID);
            String previewUrl = generatePreviewUrl(taskID, request);
            Map<String, Object> body = new HashMap<>();
            body.put("taskID", taskID);
            body.put("ossPlayUrl", playUrl);
            body.put("playUrl", previewUrl);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            logger.error("生成录制播放地址失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("生成录制播放地址失败"));
        }
    }

    @GetMapping("/recording/preview/{taskID}")
    public void previewRecording(@PathVariable String taskID,
                                 @RequestParam long expires,
                                 @RequestParam String signature,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        if (!previewSignatureValid(taskID, expires, signature)) {
            writeJsonError(response, HttpStatus.UNAUTHORIZED.value(), "播放地址已失效，请重新获取");
            return;
        }
        HttpURLConnection connection = null;
        try {
            String ossUrl = localRecordingService.generatePlayUrl(taskID);
            connection = (HttpURLConnection) new URL(ossUrl).openConnection();
            connection.setRequestMethod("GET");
            String range = request.getHeader("Range");
            if (range != null && !range.trim().isEmpty()) {
                connection.setRequestProperty("Range", range);
            }
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(30000);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                writeJsonError(response, HttpStatus.BAD_GATEWAY.value(), "读取录制文件失败");
                return;
            }
            response.setStatus(status);
            response.setContentType("video/mp4");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
            response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange != null) {
                response.setHeader(HttpHeaders.CONTENT_RANGE, contentRange);
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength >= 0) {
                response.setContentLengthLong(contentLength);
            }
            byte[] buffer = new byte[64 * 1024];
            try (InputStream inputStream = connection.getInputStream();
                 OutputStream outputStream = response.getOutputStream()) {
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                outputStream.flush();
            }
        } catch (Exception e) {
            logger.error("代理播放录制文件失败，taskID={}", taskID, e);
            writeJsonError(response, HttpStatus.INTERNAL_SERVER_ERROR.value(), "代理播放录制文件失败");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @PostMapping("/admin/recordings/{taskID}/retry-upload")
    public ResponseEntity<?> retryAdminRecordingUpload(@PathVariable String taskID,
                                                       HttpServletRequest request) {
        if (!adminAllowed(request)) {
            return adminUnauthorized();
        }
        try {
            return ResponseEntity.ok(localRecordingService.retryUpload(taskID));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            logger.error("重试录制上传失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("重试录制上传失败"));
        }
    }

    @GetMapping("/startCloudRecording")
    public ResponseEntity<?> startCloudRecording(@RequestParam String cname,
                                                 @RequestParam(required = false) String orderID) {
        try {
            return ResponseEntity.ok(localRecordingService.start(cname, orderID));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(error(e.getMessage()));
        } catch (Exception e) {
            logger.error("启动兼容录制失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("启动本地录制失败"));
        }
    }

    @GetMapping("/stopCloudRecording")
    public ResponseEntity<?> stopCloudRecording(@RequestParam String cname,
                                                @RequestParam(required = false) String taskId) {
        try {
            return ResponseEntity.ok(localRecordingService.stop(cname, taskId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            logger.error("停止兼容录制失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("停止本地录制失败"));
        }
    }

    @GetMapping("/getCloudRecordingTasks")
    public ResponseEntity<?> getCloudRecordingTasks(@RequestParam String cname) {
        try {
            return ResponseEntity.ok(localRecordingService.findByRoomID(cname));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    private Map<String, String> error(String message) {
        return Collections.singletonMap("error", message);
    }

    private boolean adminAllowed(HttpServletRequest request) {
        if (recordingAdminToken == null || recordingAdminToken.trim().isEmpty()) {
            return true;
        }
        String headerToken = request.getHeader("X-Admin-Token");
        String queryToken = request.getParameter("adminToken");
        return recordingAdminToken.equals(headerToken) || recordingAdminToken.equals(queryToken);
    }

    private boolean rtcServerAllowed(RtcSessionRequest request, HttpServletRequest httpRequest) {
        if (rtcServerToken == null || rtcServerToken.trim().isEmpty()) {
            return true;
        }
        String headerToken = httpRequest.getHeader("X-RTC-Server-Token");
        String ticket = request == null ? null : request.getTicket();
        return rtcServerToken.equals(headerToken) || rtcServerToken.equals(ticket);
    }

    private ResponseEntity<?> adminUnauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("无权访问录制后台"));
    }

    private void writeJsonError(HttpServletResponse response, int status, String message) {
        if (response.isCommitted()) {
            return;
        }
        try {
            response.reset();
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}");
            response.getWriter().flush();
        } catch (Exception ignored) {
            // The client may have closed the preview connection.
        }
    }

    private String generatePreviewUrl(String taskID, HttpServletRequest request) {
        long expires = System.currentTimeMillis() / 1000L + 600L;
        String signature = previewSignature(taskID, expires);
        return externalBaseUrl(request) + "/api/recording/preview/" + urlEncode(taskID)
                + "?expires=" + expires + "&signature=" + urlEncode(signature);
    }

    private boolean previewSignatureValid(String taskID, long expires, String signature) {
        if (System.currentTimeMillis() / 1000L > expires) {
            return false;
        }
        return constantTimeEquals(previewSignature(taskID, expires), signature);
    }

    private String previewSignature(String taskID, long expires) {
        try {
            String secret = recordingAdminToken == null || recordingAdminToken.trim().isEmpty()
                    ? "chbzg-zego-recording-preview" : recordingAdminToken;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((taskID + ":" + expires).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("生成播放签名失败", e);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] left = expected.getBytes(StandardCharsets.UTF_8);
        byte[] right = actual.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < left.length; i++) {
            result |= left[i] ^ right[i];
        }
        return result == 0;
    }

    private String externalBaseUrl(HttpServletRequest request) {
        String proto = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        if (proto == null || proto.trim().isEmpty()) {
            proto = request.getScheme();
        }
        String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
        if (host == null || host.trim().isEmpty()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.trim().isEmpty()) {
            host = request.getServerName();
        }
        return proto + "://" + host;
    }

    private String firstHeaderValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.split(",")[0].trim();
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("URL 编码失败", e);
        }
    }

    private void fillRequestMeta(RtcSessionRequest request, HttpServletRequest httpRequest, String defaultClientType) {
        if (request == null || httpRequest == null) {
            return;
        }
        if (request.getClientType() == null || request.getClientType().trim().isEmpty()) {
            request.setClientType(defaultClientType);
        }
        request.setClientIP(resolveClientIP(httpRequest));
        request.setUserAgent(httpRequest.getHeader("User-Agent"));
    }
    private void prepareZegoUserID(RtcSessionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("通话参数不能为空");
        }

        // 续 Token 必须继续使用本次通话已生成的随机 userID。
        if (request.getLeaseID() != null && !request.getLeaseID().trim().isEmpty()) {
            return;
        }

        String sourceUserID = request.getUserID() == null ? "" : request.getUserID().trim();
        if (sourceUserID.isEmpty()) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }

        byte[] randomBytes = new byte[18]; // 144 bit
        RTC_USER_ID_RANDOM.nextBytes(randomBytes);
        String suffix = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        int maxPrefixLength = 64 - suffix.length() - 1;
        String prefix = sourceUserID.length() > maxPrefixLength
                ? sourceUserID.substring(0, maxPrefixLength)
                : sourceUserID;

        request.setUserID(prefix + "_" + suffix);
        request.setStreamID(null);//必须有。否则前端传来的旧流 ID 没变，Token 中的用户和流会不一致。
    }


    private String resolveClientIP(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.trim().isEmpty()) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIP = request.getHeader("X-Real-IP");
        if (realIP != null && !realIP.trim().isEmpty()) {
            return realIP.trim();
        }
        return request.getRemoteAddr();

    }


}
