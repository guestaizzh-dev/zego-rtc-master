package com.toyeah.dispatching.service;

import com.toyeah.dispatching.dto.RtcRoomRecord;
import com.toyeah.dispatching.dto.RtcSessionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@Service
public class RtcRoomPersistenceService {

    private static final Logger logger = LoggerFactory.getLogger(RtcRoomPersistenceService.class);

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
            logger.info("RTC MySQL 持久化未启用");
            return;
        }
        if (isBlank(jdbcUrl) || isBlank(username)) {
            handleFailure("RTC MySQL 持久化已启用，但 jdbc-url 或 username 为空",
                    new IllegalStateException("RTC MySQL 配置不完整"));
            return;
        }
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            validateConnectionAndSchema();
            available = true;
            logger.info("RTC MySQL 持久化已启用，连接和表结构检查通过");
        } catch (Exception e) {
            available = false;
            handleFailure("RTC MySQL 初始化失败，请检查连接和建表脚本", e);
        }
    }

    public RtcRoomRecord find(String roomID) {
        if (!isAvailable() || isBlank(roomID)) {
            return null;
        }
        String sql = "SELECT room_id,status,doctor_user_id,doctor_stream_id,doctor_lease_id,doctor_last_seen_at,"
                + "peer_user_id,peer_stream_id,peer_lease_id,peer_last_seen_at,started_at,ended_at,abnormal_at,"
                + "last_heartbeat_at,state_updated_at,last_event,last_error "
                + "FROM rtc_room_state WHERE room_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, roomID);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                RtcRoomRecord record = new RtcRoomRecord();
                record.setRoomID(rs.getString("room_id"));
                record.setStatus(rs.getString("status"));
                record.setDoctorUserID(rs.getString("doctor_user_id"));
                record.setDoctorStreamID(rs.getString("doctor_stream_id"));
                record.setDoctorLeaseID(rs.getString("doctor_lease_id"));
                record.setDoctorLastSeenAt(rs.getLong("doctor_last_seen_at"));
                record.setPeerUserID(rs.getString("peer_user_id"));
                record.setPeerStreamID(rs.getString("peer_stream_id"));
                record.setPeerLeaseID(rs.getString("peer_lease_id"));
                record.setPeerLastSeenAt(rs.getLong("peer_last_seen_at"));
                record.setStartedAt(rs.getLong("started_at"));
                record.setEndedAt(rs.getLong("ended_at"));
                record.setAbnormalAt(rs.getLong("abnormal_at"));
                record.setLastHeartbeatAt(rs.getLong("last_heartbeat_at"));
                record.setUpdatedAt(rs.getLong("state_updated_at"));
                record.setLastEvent(rs.getString("last_event"));
                record.setLastError(rs.getString("last_error"));
                return record;
            }
        } catch (SQLException e) {
            handleFailure("查询 RTC 房间状态失败", e);
            return null;
        }
    }

    public void save(RtcRoomRecord record) {
        if (!isAvailable() || record == null || isBlank(record.getRoomID())) {
            return;
        }
        String sql = "INSERT INTO rtc_room_state (room_id,status,doctor_user_id,doctor_stream_id,doctor_lease_id,doctor_last_seen_at,"
                + "peer_user_id,peer_stream_id,peer_lease_id,peer_last_seen_at,started_at,ended_at,abnormal_at,last_heartbeat_at,"
                + "state_updated_at,last_event,last_error) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE status=VALUES(status),doctor_user_id=VALUES(doctor_user_id),"
                + "doctor_stream_id=VALUES(doctor_stream_id),doctor_lease_id=VALUES(doctor_lease_id),doctor_last_seen_at=VALUES(doctor_last_seen_at),"
                + "peer_user_id=VALUES(peer_user_id),peer_stream_id=VALUES(peer_stream_id),"
                + "peer_lease_id=VALUES(peer_lease_id),peer_last_seen_at=VALUES(peer_last_seen_at),started_at=VALUES(started_at),ended_at=VALUES(ended_at),"
                + "abnormal_at=VALUES(abnormal_at),last_heartbeat_at=VALUES(last_heartbeat_at),"
                + "state_updated_at=VALUES(state_updated_at),last_event=VALUES(last_event),last_error=VALUES(last_error)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.getRoomID());
            statement.setString(2, blankToDefault(record.getStatus(), "WAITING"));
            statement.setString(3, emptyToNull(record.getDoctorUserID()));
            statement.setString(4, emptyToNull(record.getDoctorStreamID()));
            statement.setString(5, emptyToNull(record.getDoctorLeaseID()));
            statement.setLong(6, record.getDoctorLastSeenAt());
            statement.setString(7, emptyToNull(record.getPeerUserID()));
            statement.setString(8, emptyToNull(record.getPeerStreamID()));
            statement.setString(9, emptyToNull(record.getPeerLeaseID()));
            statement.setLong(10, record.getPeerLastSeenAt());
            statement.setLong(11, record.getStartedAt());
            statement.setLong(12, record.getEndedAt());
            statement.setLong(13, record.getAbnormalAt());
            statement.setLong(14, record.getLastHeartbeatAt());
            statement.setLong(15, record.getUpdatedAt());
            statement.setString(16, emptyToNull(limit(record.getLastEvent(), 64)));
            statement.setString(17, emptyToNull(limit(record.getLastError(), 255)));
            statement.executeUpdate();
        } catch (SQLException e) {
            handleFailure("保存 RTC 房间状态失败", e);
        }
    }

    public void audit(String action, String result, String message, RtcSessionRequest request, String slotType) {
        if (!isAvailable()) {
            return;
        }
        String sql = "INSERT INTO rtc_audit_log (action,result,room_id,user_id,role,slot_type,stream_id,order_id,"
                + "client_type,client_ip,user_agent,ticket_hash,message) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, limit(action, 64));
            statement.setString(2, limit(result, 32));
            statement.setString(3, emptyToNull(limit(value(request, "room"), 128)));
            statement.setString(4, emptyToNull(limit(value(request, "user"), 64)));
            statement.setString(5, emptyToNull(limit(value(request, "role"), 32)));
            statement.setString(6, emptyToNull(limit(slotType, 32)));
            statement.setString(7, emptyToNull(limit(value(request, "stream"), 256)));
            statement.setString(8, emptyToNull(limit(value(request, "order"), 64)));
            statement.setString(9, emptyToNull(limit(value(request, "clientType"), 32)));
            statement.setString(10, emptyToNull(limit(value(request, "clientIP"), 64)));
            statement.setString(11, emptyToNull(limit(value(request, "userAgent"), 255)));
            statement.setString(12, emptyToNull(hashTicket(request == null ? null : request.getTicket())));
            statement.setString(13, emptyToNull(limit(message, 255)));
            statement.executeUpdate();
        } catch (SQLException e) {
            handleFailure("写入 RTC 审计日志失败", e);
        }
    }

    private boolean isAvailable() {
        return enabled && available;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private void validateConnectionAndSchema() throws SQLException {
        try (Connection connection = openConnection()) {
            validateQuery(connection, "SELECT 1");
            validateQuery(connection, "SELECT 1 FROM rtc_room_state LIMIT 1");
            validateQuery(connection, "SELECT 1 FROM rtc_audit_log LIMIT 1");
            validateQuery(connection, "SELECT 1 FROM rtc_recording_task LIMIT 1");
        }
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

    private String value(RtcSessionRequest request, String type) {
        if (request == null) {
            return null;
        }
        switch (type) {
            case "room":
                return request.getRoomID();
            case "user":
                return request.getUserID();
            case "role":
                return request.getRole();
            case "stream":
                return request.getStreamID();
            case "order":
                return request.getOrderID();
            case "clientType":
                return request.getClientType();
            case "clientIP":
                return request.getClientIP();
            case "userAgent":
                return request.getUserAgent();
            default:
                return null;
        }
    }

    private String hashTicket(String ticket) {
        if (isBlank(ticket)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(ticket.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception e) {
            return null;
        }
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
