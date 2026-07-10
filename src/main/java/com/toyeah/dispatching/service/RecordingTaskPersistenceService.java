package com.toyeah.dispatching.service;

import com.toyeah.dispatching.dto.RecordingTaskRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public class RecordingTaskPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(RecordingTaskPersistenceService.class);

    @Value("${rtc.persistence.enabled:false}")
    private boolean enabled;

    @Value("${rtc.persistence.fail-fast:false}")
    private boolean failFast;

    @Value("${rtc.persistence.jdbc-url:}")
    private String jdbcUrl;

    @Value("${rtc.persistence.username:}")
    private String username;

    @Value("${rtc.persistence.password:}")
    private String password;

    private volatile boolean available;

    @PostConstruct
    public void init() {
        if (!enabled) {
            logger.info("录制任务 MySQL 持久化未启用");
            return;
        }
        if (isBlank(jdbcUrl) || isBlank(username)) {
            handleFailure("录制任务 MySQL 持久化已启用，但 jdbc-url 或 username 为空",
                    new IllegalStateException("RTC MySQL 配置不完整"));
            return;
        }
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection connection = openConnection()) {
                validateQuery(connection, "SELECT 1");
                validateQuery(connection, "SELECT 1 FROM rtc_recording_task LIMIT 1");
            }
            available = true;
            logger.info("录制任务 MySQL 持久化已启用，连接和表结构检查通过");
        } catch (Exception e) {
            available = false;
            handleFailure("录制任务 MySQL 初始化失败，请检查 rtc_recording_task 建表脚本", e);
        }
    }

    public boolean isAvailable() {
        return enabled && available;
    }

    public void save(RecordingTaskRecord record) {
        if (!isAvailable() || record == null || isBlank(record.getTaskID()) || isBlank(record.getRoomID())) {
            return;
        }
        String sql = "INSERT INTO rtc_recording_task (task_id,room_id,order_id,doctor_user_id,peer_user_id,"
                + "doctor_name,peer_name,status,"
                + "output_file,log_file,local_file_deleted,local_file_deleted_at,oss_bucket,oss_object_key,oss_url,"
                + "file_size,duration_seconds,started_at,ended_at,uploaded_at,upload_attempts,next_upload_at,"
                + "message,last_error,created_at,state_updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE room_id=VALUES(room_id),order_id=VALUES(order_id),"
                + "doctor_user_id=VALUES(doctor_user_id),peer_user_id=VALUES(peer_user_id),status=VALUES(status),"
                + "doctor_name=VALUES(doctor_name),peer_name=VALUES(peer_name),"
                + "output_file=VALUES(output_file),log_file=VALUES(log_file),local_file_deleted=VALUES(local_file_deleted),"
                + "local_file_deleted_at=VALUES(local_file_deleted_at),oss_bucket=VALUES(oss_bucket),"
                + "oss_object_key=VALUES(oss_object_key),oss_url=VALUES(oss_url),file_size=VALUES(file_size),"
                + "duration_seconds=VALUES(duration_seconds),started_at=VALUES(started_at),ended_at=VALUES(ended_at),"
                + "uploaded_at=VALUES(uploaded_at),upload_attempts=VALUES(upload_attempts),next_upload_at=VALUES(next_upload_at),"
                + "message=VALUES(message),last_error=VALUES(last_error),state_updated_at=VALUES(state_updated_at)";
        long now = System.currentTimeMillis();
        if (record.getCreatedAt() <= 0) {
            record.setCreatedAt(now);
        }
        record.setUpdatedAt(now);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            fillSaveStatement(statement, record);
            statement.executeUpdate();
        } catch (SQLException e) {
            handleFailure("保存录制任务失败", e);
        }
    }

    public RecordingTaskRecord findByTaskID(String taskID) {
        if (!isAvailable() || isBlank(taskID)) {
            return null;
        }
        String sql = selectColumns() + " FROM rtc_recording_task WHERE task_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, taskID.trim());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            handleFailure("查询录制任务失败", e);
            return null;
        }
    }

    public RecordingTaskRecord findLatestByRoomID(String roomID) {
        if (!isAvailable() || isBlank(roomID)) {
            return null;
        }
        String sql = selectColumns() + " FROM rtc_recording_task WHERE room_id = ? ORDER BY started_at DESC LIMIT 1";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, roomID.trim());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            handleFailure("按房间查询录制任务失败", e);
            return null;
        }
    }

    public List<RecordingTaskRecord> list(String roomID, String orderID, String status, int page, int size) {
        List<RecordingTaskRecord> records = new ArrayList<>();
        if (!isAvailable()) {
            return records;
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        StringBuilder sql = new StringBuilder(selectColumns()).append(" FROM rtc_recording_task WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (!isBlank(roomID)) {
            sql.append(" AND room_id = ?");
            args.add(roomID.trim());
        }
        if (!isBlank(orderID)) {
            sql.append(" AND order_id = ?");
            args.add(orderID.trim());
        }
        if (!isBlank(status)) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY started_at DESC LIMIT ? OFFSET ?");
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (String arg : args) {
                statement.setString(index++, arg);
            }
            statement.setInt(index++, safeSize);
            statement.setInt(index, (safePage - 1) * safeSize);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    records.add(map(rs));
                }
            }
        } catch (SQLException e) {
            handleFailure("查询录制任务列表失败", e);
        }
        return records;
    }

    public long count(String roomID, String orderID, String status) {
        if (!isAvailable()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("SELECT COUNT(1) FROM rtc_recording_task WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (!isBlank(roomID)) {
            sql.append(" AND room_id = ?");
            args.add(roomID.trim());
        }
        if (!isBlank(orderID)) {
            sql.append(" AND order_id = ?");
            args.add(orderID.trim());
        }
        if (!isBlank(status)) {
            sql.append(" AND status = ?");
            args.add(status.trim());
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (String arg : args) {
                statement.setString(index++, arg);
            }
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            handleFailure("统计录制任务数量失败", e);
        }
        return 0;
    }

    public void markInterruptedRunningTasks() {
        if (!isAvailable()) {
            return;
        }
        String sql = "UPDATE rtc_recording_task SET status='ABNORMAL', ended_at=?, state_updated_at=?, "
                + "message='Java 服务重启，上一进程录制状态已失效', last_error='Java 服务重启，录制进程已不存在' "
                + "WHERE status IN ('STARTING','RUNNING')";
        long now = System.currentTimeMillis();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            int changed = statement.executeUpdate();
            if (changed > 0) {
                logger.warn("已将 {} 个历史运行中录制任务标记为异常结束", changed);
            }
        } catch (SQLException e) {
            handleFailure("标记历史运行中录制任务失败", e);
        }
    }

    private void fillSaveStatement(PreparedStatement statement, RecordingTaskRecord record) throws SQLException {
        statement.setString(1, limit(record.getTaskID(), 64));
        statement.setString(2, limit(record.getRoomID(), 128));
        statement.setString(3, emptyToNull(limit(record.getOrderID(), 128)));
        statement.setString(4, emptyToNull(limit(record.getDoctorUserID(), 64)));
        statement.setString(5, emptyToNull(limit(record.getPeerUserID(), 64)));
        statement.setString(6, emptyToNull(limit(record.getDoctorName(), 64)));
        statement.setString(7, emptyToNull(limit(record.getPeerName(), 64)));
        statement.setString(8, limit(blankToDefault(record.getStatus(), "STARTING"), 32));
        statement.setString(9, emptyToNull(limit(record.getOutputFile(), 512)));
        statement.setString(10, emptyToNull(limit(record.getLogFile(), 512)));
        statement.setBoolean(11, record.isLocalFileDeleted());
        statement.setLong(12, record.getLocalFileDeletedAt());
        statement.setString(13, emptyToNull(limit(record.getOssBucket(), 128)));
        statement.setString(14, emptyToNull(limit(record.getOssObjectKey(), 512)));
        statement.setString(15, emptyToNull(limit(record.getOssUrl(), 1024)));
        statement.setLong(16, record.getFileSize());
        if (record.getDurationSeconds() == null) {
            statement.setObject(17, null);
        } else {
            statement.setDouble(17, record.getDurationSeconds());
        }
        statement.setLong(18, record.getStartedAt());
        statement.setLong(19, record.getEndedAt());
        statement.setLong(20, record.getUploadedAt());
        statement.setInt(21, record.getUploadAttempts());
        statement.setLong(22, record.getNextUploadAt());
        statement.setString(23, emptyToNull(limit(record.getMessage(), 1024)));
        statement.setString(24, emptyToNull(limit(record.getLastError(), 1024)));
        statement.setLong(25, record.getCreatedAt());
        statement.setLong(26, record.getUpdatedAt());
    }

    private RecordingTaskRecord map(ResultSet rs) throws SQLException {
        RecordingTaskRecord record = new RecordingTaskRecord();
        record.setTaskID(rs.getString("task_id"));
        record.setRoomID(rs.getString("room_id"));
        record.setOrderID(rs.getString("order_id"));
        record.setDoctorUserID(rs.getString("doctor_user_id"));
        record.setPeerUserID(rs.getString("peer_user_id"));
        record.setDoctorName(rs.getString("doctor_name"));
        record.setPeerName(rs.getString("peer_name"));
        record.setStatus(rs.getString("status"));
        record.setOutputFile(rs.getString("output_file"));
        record.setLogFile(rs.getString("log_file"));
        record.setLocalFileDeleted(rs.getBoolean("local_file_deleted"));
        record.setLocalFileDeletedAt(rs.getLong("local_file_deleted_at"));
        record.setOssBucket(rs.getString("oss_bucket"));
        record.setOssObjectKey(rs.getString("oss_object_key"));
        record.setOssUrl(rs.getString("oss_url"));
        record.setFileSize(rs.getLong("file_size"));
        Object duration = rs.getObject("duration_seconds");
        record.setDurationSeconds(duration == null ? null : rs.getDouble("duration_seconds"));
        record.setStartedAt(rs.getLong("started_at"));
        record.setEndedAt(rs.getLong("ended_at"));
        record.setUploadedAt(rs.getLong("uploaded_at"));
        record.setUploadAttempts(rs.getInt("upload_attempts"));
        record.setNextUploadAt(rs.getLong("next_upload_at"));
        record.setMessage(rs.getString("message"));
        record.setLastError(rs.getString("last_error"));
        record.setCreatedAt(rs.getLong("created_at"));
        record.setUpdatedAt(rs.getLong("state_updated_at"));
        return record;
    }

    private String selectColumns() {
        return "SELECT task_id,room_id,order_id,doctor_user_id,peer_user_id,doctor_name,peer_name,"
                + "status,output_file,log_file,"
                + "local_file_deleted,local_file_deleted_at,oss_bucket,oss_object_key,oss_url,file_size,"
                + "duration_seconds,started_at,ended_at,uploaded_at,upload_attempts,next_upload_at,"
                + "message,last_error,created_at,state_updated_at";
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void validateQuery(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet ignored = statement.executeQuery()) {
            // Executing the query is sufficient to validate connectivity and table availability.
        }
    }

    private void handleFailure(String message, Exception e) {
        if (failFast) {
            throw new IllegalStateException(message, e);
        }
        logger.warn(message, e);
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
