package com.toyeah.dispatching.service;

import com.toyeah.dispatching.dto.RtcRoomRecord;
import com.toyeah.dispatching.dto.RtcSessionRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class RoomAccessService {

    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");
    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    private final Map<String, RoomState> rooms = new ConcurrentHashMap<>();
    private final RtcRoomPersistenceService persistenceService;

    @Value("${rtc.room.slot-timeout-seconds:1800}")
    private int slotTimeoutSeconds;

    public RoomAccessService(RtcRoomPersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    public EnterResult enter(RtcSessionRequest request) {
        String roomID = normalize(request == null ? null : request.getRoomID());
        String userID = normalize(request == null ? null : request.getUserID());
        String streamID = normalize(request == null ? null : request.getStreamID());
        String requestedLeaseID = normalize(request == null ? null : request.getLeaseID());
        SlotType slotType = normalizeSlot(request == null ? null : request.getRole());
        validateRoomID(roomID);
        validateUserID(userID);

        RoomState state = rooms.computeIfAbsent(roomID, this::restoreState);
        synchronized (state) {
            removeExpiredSlots(roomID, state);
            if (state.ended) {
                throw reject(request, slotType, state, "当前订单已结束");
            }

            long now = System.currentTimeMillis();
            Slot current = slot(state, slotType);
            if (current == null) {
                String leaseID = newLeaseID();
                setSlot(state, slotType, new Slot(userID, streamID, leaseID, now));
                if (state.startedAt <= 0) {
                    state.startedAt = now;
                }
                mark(state, "SESSION", null, now);
                save(roomID, state);
                audit("SESSION", "SUCCESS", "允许进入房间", request, slotType);
                return new EnterResult(leaseID, null, true);
            }
            if (current.userID.equals(userID)) {
                if (!requestedLeaseID.isEmpty() && !requestedLeaseID.equals(current.leaseID)) {
                    throw reject(request, slotType, state, "当前会话已被其他页面接管，请重新进入房间");
                }
                Slot previous = null;
                if (requestedLeaseID.isEmpty()) {
                    previous = current.copy();
                    current.leaseID = newLeaseID();
                }
                current.lastSeenAt = now;
                if (!streamID.isEmpty()) {
                    current.streamID = streamID;
                }
                if (state.startedAt <= 0) {
                    state.startedAt = now;
                }
                mark(state, "SESSION_REFRESH", null, now);
                save(roomID, state);
                audit("SESSION", "SUCCESS", "重复进入或 Token 续期", request, slotType);
                return new EnterResult(current.leaseID, previous, previous != null);
            }
            if (state.doctor != null && state.peer != null) {
                throw reject(request, slotType, state, "房间已满");
            }
            throw reject(request, slotType, state, slotType == SlotType.DOCTOR
                    ? "医生已在其他设备进入"
                    : "患者或药店已在其他设备进入");
        }
    }

    public void rollbackEnter(RtcSessionRequest request, EnterResult enterResult) {
        if (enterResult == null || !enterResult.changed) {
            return;
        }
        String roomID = normalize(request == null ? null : request.getRoomID());
        String userID = normalize(request == null ? null : request.getUserID());
        SlotType slotType = normalizeSlot(request == null ? null : request.getRole());
        validateRoomID(roomID);
        validateUserID(userID);

        RoomState state = rooms.get(roomID);
        if (state == null) {
            return;
        }
        synchronized (state) {
            Slot current = slot(state, slotType);
            if (current == null || !current.userID.equals(userID)
                    || !enterResult.leaseID.equals(current.leaseID)) {
                return;
            }
            setSlot(state, slotType, enterResult.previousSlot == null ? null : enterResult.previousSlot.copy());
            long now = System.currentTimeMillis();
            mark(state, "SESSION_ROLLBACK", "Token 生成失败，已回滚本次席位", now);
            save(roomID, state);
            audit("SESSION_ROLLBACK", "SUCCESS", "Token 生成失败，已回滚本次席位", request, slotType);
        }
    }

    public void heartbeat(RtcSessionRequest request) {
        String roomID = normalize(request == null ? null : request.getRoomID());
        String userID = normalize(request == null ? null : request.getUserID());
        String leaseID = normalize(request == null ? null : request.getLeaseID());
        SlotType slotType = normalizeSlot(request == null ? null : request.getRole());
        validateRoomID(roomID);
        validateUserID(userID);

        RoomState state = rooms.computeIfAbsent(roomID, this::restoreState);
        synchronized (state) {
            removeExpiredSlots(roomID, state);
            Slot current = slot(state, slotType);
            if (state.ended) {
                throw new IllegalArgumentException("当前订单已结束");
            }
            if (current == null || !current.userID.equals(userID) || !current.leaseID.equals(leaseID)) {
                throw new IllegalArgumentException("当前会话已失效，请重新进入房间");
            }
            long now = System.currentTimeMillis();
            current.lastSeenAt = now;
            state.lastHeartbeatAt = now;
            mark(state, "HEARTBEAT", null, now);
            save(roomID, state);
        }
    }

    public void leave(RtcSessionRequest request) {
        String roomID = normalize(request == null ? null : request.getRoomID());
        String userID = normalize(request == null ? null : request.getUserID());
        String leaseID = normalize(request == null ? null : request.getLeaseID());
        SlotType slotType = normalizeSlot(request == null ? null : request.getRole());
        validateRoomID(roomID);
        validateUserID(userID);

        RoomState state = rooms.computeIfAbsent(roomID, this::restoreState);
        synchronized (state) {
            Slot current = slot(state, slotType);
            if (current != null && current.userID.equals(userID) && current.leaseID.equals(leaseID)) {
                setSlot(state, slotType, null);
                mark(state, "LEAVE", null, System.currentTimeMillis());
                save(roomID, state);
                audit("LEAVE", "SUCCESS", "释放房间席位", request, slotType);
            } else if (current != null && current.userID.equals(userID)) {
                throw new IllegalArgumentException("当前会话已失效，无需重复离开");
            }
        }
    }

    public void end(RtcSessionRequest request) {
        String roomID = normalize(request == null ? null : request.getRoomID());
        String userID = normalize(request == null ? null : request.getUserID());
        String leaseID = normalize(request == null ? null : request.getLeaseID());
        SlotType slotType = normalizeSlot(request == null ? null : request.getRole());
        validateRoomID(roomID);
        validateUserID(userID);
        RoomState state = rooms.computeIfAbsent(roomID, this::restoreState);
        synchronized (state) {
            if (slotType != SlotType.DOCTOR) {
                throw reject(request, slotType, state, "只有医生可以结束房间");
            }
            Slot current = slot(state, slotType);
            if (current == null || !current.userID.equals(userID)
                    || !current.leaseID.equals(leaseID)) {
                throw reject(request, slotType, state, "无权结束该房间");
            }
            long now = System.currentTimeMillis();
            state.ended = true;
            state.endedAt = now;
            state.doctor = null;
            state.peer = null;
            mark(state, "END", null, now);
            save(roomID, state);
            audit("END", "SUCCESS", "医生结束房间", request, slotType);
        }
    }

    public void reopen(RtcSessionRequest request) {
        String roomID = normalize(request == null ? null : request.getRoomID());
        validateRoomID(roomID);

        RoomState state = rooms.computeIfAbsent(roomID, this::restoreState);
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (!state.ended) {
                mark(state, "REOPEN_SKIP", "房间未结束，无需重开", now);
                save(roomID, state);
                audit("REOPEN", "SUCCESS", "房间未结束，无需重开", request, SlotType.PEER);
                return;
            }

            state.ended = false;
            state.endedAt = 0;
            state.startedAt = 0;
            state.abnormalAt = 0;
            state.lastHeartbeatAt = 0;
            state.doctor = null;
            state.peer = null;
            mark(state, "REOPEN", "业务订单重新进入待视频问诊", now);
            save(roomID, state);
            audit("REOPEN", "SUCCESS", "业务订单重新进入待视频问诊", request, SlotType.PEER);
        }
    }

    public RtcRoomRecord findRoom(String roomID) {
        String normalizedRoomID = normalize(roomID);
        validateRoomID(normalizedRoomID);
        RoomState state = rooms.get(normalizedRoomID);
        if (state != null) {
            synchronized (state) {
                if (removeExpiredSlots(normalizedRoomID, state)) {
                    save(normalizedRoomID, state);
                }
                return snapshot(normalizedRoomID, state);
            }
        }
        RtcRoomRecord record = persistenceService.find(normalizedRoomID);
        if (record == null) {
            return null;
        }
        RoomState restored = restoreState(record);
        RoomState current = rooms.putIfAbsent(normalizedRoomID, restored);
        current = current == null ? restored : current;
        synchronized (current) {
            if (removeExpiredSlots(normalizedRoomID, current)) {
                save(normalizedRoomID, current);
            }
            return snapshot(normalizedRoomID, current);
        }
    }

    @Scheduled(fixedDelayString = "${rtc.room.sweep-interval-ms:60000}")
    public void sweepExpiredSlots() {
        for (Map.Entry<String, RoomState> entry : rooms.entrySet()) {
            RoomState state = entry.getValue();
            synchronized (state) {
                if (removeExpiredSlots(entry.getKey(), state)) {
                    save(entry.getKey(), state);
                }
            }
        }
    }

    private IllegalArgumentException reject(RtcSessionRequest request, SlotType slotType,
                                            RoomState state, String message) {
        mark(state, "REJECT", message, System.currentTimeMillis());
        save(normalize(request == null ? null : request.getRoomID()), state);
        audit("REJECT", "FAIL", message, request, slotType);
        return new IllegalArgumentException(message);
    }

    private boolean removeExpiredSlots(String roomID, RoomState state) {
        long timeoutMs = Math.max(60, slotTimeoutSeconds) * 1000L;
        long now = System.currentTimeMillis();
        boolean changed = false;
        if (state.doctor != null && now - state.doctor.lastSeenAt > timeoutMs) {
            state.doctor = null;
            state.abnormalAt = now;
            mark(state, "EXPIRE_DOCTOR", "医生心跳超时，已释放席位", now);
            persistenceService.audit("EXPIRE", "SUCCESS", "医生心跳超时，已释放席位", expireRequest(roomID), SlotType.DOCTOR.name());
            changed = true;
        }
        if (state.peer != null && now - state.peer.lastSeenAt > timeoutMs) {
            state.peer = null;
            state.abnormalAt = now;
            mark(state, "EXPIRE_PEER", "患者或药店心跳超时，已释放席位", now);
            persistenceService.audit("EXPIRE", "SUCCESS", "患者或药店心跳超时，已释放席位", expireRequest(roomID), SlotType.PEER.name());
            changed = true;
        }
        return changed;
    }

    private RtcSessionRequest expireRequest(String roomID) {
        RtcSessionRequest request = new RtcSessionRequest();
        request.setRoomID(roomID);
        request.setClientType("server");
        return request;
    }

    private RoomState restoreState(String roomID) {
        return restoreState(persistenceService.find(roomID));
    }

    private RoomState restoreState(RtcRoomRecord record) {
        RoomState state = new RoomState();
        if (record == null) {
            return state;
        }
        state.ended = "ENDED".equalsIgnoreCase(record.getStatus());
        state.startedAt = record.getStartedAt();
        state.endedAt = record.getEndedAt();
        state.abnormalAt = record.getAbnormalAt();
        state.lastHeartbeatAt = record.getLastHeartbeatAt();
        state.updatedAt = record.getUpdatedAt();
        state.lastEvent = record.getLastEvent();
        state.lastError = record.getLastError();
        if (!isBlank(record.getDoctorUserID())) {
            state.doctor = new Slot(record.getDoctorUserID(), record.getDoctorStreamID(),
                    record.getDoctorLeaseID(), record.getDoctorLastSeenAt());
        }
        if (!isBlank(record.getPeerUserID())) {
            state.peer = new Slot(record.getPeerUserID(), record.getPeerStreamID(),
                    record.getPeerLeaseID(), record.getPeerLastSeenAt());
        }
        return state;
    }

    private void save(String roomID, RoomState state) {
        if (isBlank(roomID)) {
            return;
        }
        persistenceService.save(snapshot(roomID, state));
    }

    private RtcRoomRecord snapshot(String roomID, RoomState state) {
        RtcRoomRecord record = new RtcRoomRecord(roomID);
        record.setStatus(status(state));
        if (state.doctor != null) {
            record.setDoctorUserID(state.doctor.userID);
            record.setDoctorStreamID(state.doctor.streamID);
            record.setDoctorLeaseID(state.doctor.leaseID);
            record.setDoctorLastSeenAt(state.doctor.lastSeenAt);
        }
        if (state.peer != null) {
            record.setPeerUserID(state.peer.userID);
            record.setPeerStreamID(state.peer.streamID);
            record.setPeerLeaseID(state.peer.leaseID);
            record.setPeerLastSeenAt(state.peer.lastSeenAt);
        }
        record.setStartedAt(state.startedAt);
        record.setEndedAt(state.endedAt);
        record.setAbnormalAt(state.abnormalAt);
        record.setLastHeartbeatAt(state.lastHeartbeatAt);
        record.setUpdatedAt(state.updatedAt);
        record.setLastEvent(state.lastEvent);
        record.setLastError(state.lastError);
        return record;
    }

    private String status(RoomState state) {
        if (state.ended) {
            return "ENDED";
        }
        if (state.doctor != null && state.peer != null) {
            return "ACTIVE";
        }
        if (state.doctor != null || state.peer != null) {
            return "WAITING";
        }
        if (state.abnormalAt > 0) {
            return "ABNORMAL";
        }
        return "WAITING";
    }

    private void audit(String action, String result, String message, RtcSessionRequest request, SlotType slotType) {
        persistenceService.audit(action, result, message, request, slotType.name());
    }

    private void mark(RoomState state, String event, String error, long now) {
        state.lastEvent = event;
        state.lastError = error;
        state.updatedAt = now;
    }

    private Slot slot(RoomState state, SlotType slotType) {
        return slotType == SlotType.DOCTOR ? state.doctor : state.peer;
    }

    private void setSlot(RoomState state, SlotType slotType, Slot slot) {
        if (slotType == SlotType.DOCTOR) {
            state.doctor = slot;
        } else {
            state.peer = slot;
        }
    }

    private SlotType normalizeSlot(String role) {
        String value = normalize(role).toLowerCase(Locale.ROOT);
        if ("doctor".equals(value)) {
            return SlotType.DOCTOR;
        }
        if (value.isEmpty()
                || "guest".equals(value)
                || "debug".equals(value)
                || "mp".equals(value)
                || "patient".equals(value)
                || "pharmacy".equals(value)
                || "drugstore".equals(value)) {
            return SlotType.PEER;
        }
        throw new IllegalArgumentException("无权进入该房间");
    }

    private void validateRoomID(String roomID) {
        if (roomID == null || !ROOM_ID_PATTERN.matcher(roomID).matches()) {
            throw new IllegalArgumentException("房间号只能包含字母、数字、下划线、横线、点号和冒号，长度必须为 1-128 个字符");
        }
    }

    private void validateUserID(String userID) {
        if (userID == null || !USER_ID_PATTERN.matcher(userID).matches()) {
            throw new IllegalArgumentException("用户 ID 只能包含字母、数字、下划线、横线、点号和冒号，长度必须为 1-64 个字符");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String newLeaseID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static class EnterResult {
        private final String leaseID;
        private final Slot previousSlot;
        private final boolean changed;

        private EnterResult(String leaseID, Slot previousSlot, boolean changed) {
            this.leaseID = leaseID;
            this.previousSlot = previousSlot;
            this.changed = changed;
        }

        public String getLeaseID() {
            return leaseID;
        }
    }

    private enum SlotType {
        DOCTOR,
        PEER
    }

    private static class RoomState {
        private Slot doctor;
        private Slot peer;
        private boolean ended;
        private long startedAt;
        private long endedAt;
        private long abnormalAt;
        private long lastHeartbeatAt;
        private long updatedAt;
        private String lastEvent;
        private String lastError;
    }

    private static class Slot {
        private final String userID;
        private String streamID;
        private String leaseID;
        private long lastSeenAt;

        private Slot(String userID, String streamID, String leaseID, long lastSeenAt) {
            this.userID = userID;
            this.streamID = streamID;
            this.leaseID = leaseID == null ? "" : leaseID;
            this.lastSeenAt = lastSeenAt;
        }

        private Slot copy() {
            return new Slot(userID, streamID, leaseID, lastSeenAt);
        }
    }
}
