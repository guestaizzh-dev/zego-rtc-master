package com.toyeah.dispatching.dto;

public class RtcSessionResponse {
    private long appID;
    private String server;
    private String token;
    private int expiresIn;
    private String roomID;
    private String userID;
    private String streamID;
    private String leaseID;

    public long getAppID() {
        return appID;
    }

    public void setAppID(long appID) {
        this.appID = appID;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public int getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(int expiresIn) {
        this.expiresIn = expiresIn;
    }

    public String getRoomID() {
        return roomID;
    }

    public void setRoomID(String roomID) {
        this.roomID = roomID;
    }

    public String getUserID() {
        return userID;
    }

    public void setUserID(String userID) {
        this.userID = userID;
    }

    public String getStreamID() {
        return streamID;
    }

    public void setStreamID(String streamID) {
        this.streamID = streamID;
    }

    public String getLeaseID() {
        return leaseID;
    }

    public void setLeaseID(String leaseID) {
        this.leaseID = leaseID;
    }
}
