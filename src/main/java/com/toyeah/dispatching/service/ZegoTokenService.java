package com.toyeah.dispatching.service;

import com.alibaba.fastjson.JSONObject;
import com.toyeah.dispatching.dto.RtcSessionRequest;
import com.toyeah.dispatching.dto.RtcSessionResponse;
import com.toyeah.dispatching.zego.TokenServerAssistant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class ZegoTokenService {

    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,128}$");
    private static final Pattern USER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    private static final Pattern STREAM_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");

    @Value("${zego.app-id:0}")
    private long appID;

    @Value("${zego.server:}")
    private String server;

    @Value("${zego.server-secret:}")
    private String serverSecret;

    @Value("${zego.token-ttl-seconds:3600}")
    private int defaultTtlSeconds;

    public RtcSessionResponse createSession(RtcSessionRequest request) {
        String roomID = normalize(request == null ? null : request.getRoomID());
        String userID = normalize(request == null ? null : request.getUserID());
        String streamID = normalize(request == null ? null : request.getStreamID());
        int ttl = clampTtl(request == null ? null : request.getTtlSec());

        validateConfigured();
        validateRoomID(roomID);
        validateUserID(userID);
        if (streamID == null || streamID.isEmpty()) {
            streamID = sanitizeStreamID(roomID + "_" + userID);
        }
        validateStreamID(streamID);

        String token = createToken(roomID, userID, streamID, ttl, true);

        RtcSessionResponse response = new RtcSessionResponse();
        response.setAppID(appID);
        response.setServer(server);
        response.setToken(token);
        response.setExpiresIn(ttl);
        response.setRoomID(roomID);
        response.setUserID(userID);
        response.setStreamID(streamID);
        return response;
    }

    public String createToken(String roomID, String userID, String streamID, int ttl, boolean allowPublish) {
        validateConfigured();
        validateRoomID(roomID);
        validateUserID(userID);
        if (streamID != null && !streamID.isEmpty()) {
            validateStreamID(streamID);
        }

        JSONObject payloadData = new JSONObject();
        payloadData.put("room_id", roomID);
        JSONObject privilege = new JSONObject();
        privilege.put(TokenServerAssistant.PRIVILEGE_KEY_LOGIN, TokenServerAssistant.PRIVILEGE_ENABLE);
        privilege.put(TokenServerAssistant.PRIVILEGE_KEY_PUBLISH,
                allowPublish ? TokenServerAssistant.PRIVILEGE_ENABLE : TokenServerAssistant.PRIVILEGE_DISABLE);
        payloadData.put("privilege", privilege);
        payloadData.put("stream_id_list", allowPublish && streamID != null && !streamID.isEmpty()
                ? new String[]{streamID}
                : null);

        return TokenServerAssistant.generateToken04(appID, userID, serverSecret, ttl, payloadData.toJSONString());
    }

    public String createRecordingToken(String roomID, String userID, int ttl) {
        validateConfigured();
        validateRoomID(roomID);
        validateUserID(userID);

        JSONObject payloadData = new JSONObject();
        payloadData.put("room_id", roomID);
        JSONObject privilege = new JSONObject();
        privilege.put(TokenServerAssistant.PRIVILEGE_KEY_LOGIN, TokenServerAssistant.PRIVILEGE_ENABLE);
        privilege.put(TokenServerAssistant.PRIVILEGE_KEY_PUBLISH, TokenServerAssistant.PRIVILEGE_DISABLE);
        payloadData.put("privilege", privilege);
        payloadData.put("stream_id_list", null);

        return TokenServerAssistant.generateToken04(appID, userID, serverSecret, ttl, payloadData.toJSONString());
    }

    public long getAppID() {
        return appID;
    }

    public String getServer() {
        return server;
    }

    public int getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public String sanitizeStreamID(String value) {
        return normalize(value).replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private int clampTtl(Integer ttlSec) {
        int ttl = ttlSec == null || ttlSec <= 0 ? defaultTtlSeconds : ttlSec;
        if (ttl < 60) {
            return 60;
        }
        return Math.min(ttl, 24 * 60 * 60);
    }

    private void validateConfigured() {
        if (appID <= 0) {
            throw new IllegalStateException("ZEGO appID 未配置");
        }
        if (server == null || server.trim().isEmpty()) {
            throw new IllegalStateException("ZEGO server 未配置");
        }
        if (serverSecret == null || serverSecret.length() != 32) {
            throw new IllegalStateException("ZEGO serverSecret 未配置或长度不正确");
        }
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

    private void validateStreamID(String streamID) {
        if (streamID == null || !STREAM_ID_PATTERN.matcher(streamID).matches()) {
            throw new IllegalArgumentException("流 ID 只能包含字母、数字、下划线和横线，长度必须为 1-256 个字符");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
