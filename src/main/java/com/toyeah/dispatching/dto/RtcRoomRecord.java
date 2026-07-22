package com.toyeah.dispatching.dto;

public class RtcRoomRecord {
    private String roomID;
    private String status;
    private String doctorUserID;
    private String doctorStreamID;
    private String doctorLeaseID;
    private long doctorLastSeenAt;
    private String peerUserID;
    private String peerStreamID;
    private String peerLeaseID;
    private long peerLastSeenAt;
    private long startedAt;
    private long endedAt;
    private long abnormalAt;
    private long lastHeartbeatAt;
    private long updatedAt;
    private String lastEvent;
    private String lastError;

    public RtcRoomRecord() {
    }

    public RtcRoomRecord(String roomID) {
        this.roomID = roomID;
    }

    public String getRoomID() {
        return roomID;
    }

    public void setRoomID(String roomID) {
        this.roomID = roomID;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDoctorUserID() {
        return doctorUserID;
    }

    public void setDoctorUserID(String doctorUserID) {
        this.doctorUserID = doctorUserID;
    }

    public String getDoctorStreamID() {
        return doctorStreamID;
    }

    public void setDoctorStreamID(String doctorStreamID) {
        this.doctorStreamID = doctorStreamID;
    }

    public String getDoctorLeaseID() {
        return doctorLeaseID;
    }

    public void setDoctorLeaseID(String doctorLeaseID) {
        this.doctorLeaseID = doctorLeaseID;
    }

    public long getDoctorLastSeenAt() {
        return doctorLastSeenAt;
    }

    public void setDoctorLastSeenAt(long doctorLastSeenAt) {
        this.doctorLastSeenAt = doctorLastSeenAt;
    }

    public String getPeerUserID() {
        return peerUserID;
    }

    public void setPeerUserID(String peerUserID) {
        this.peerUserID = peerUserID;
    }

    public String getPeerStreamID() {
        return peerStreamID;
    }

    public void setPeerStreamID(String peerStreamID) {
        this.peerStreamID = peerStreamID;
    }

    public String getPeerLeaseID() {
        return peerLeaseID;
    }

    public void setPeerLeaseID(String peerLeaseID) {
        this.peerLeaseID = peerLeaseID;
    }

    public long getPeerLastSeenAt() {
        return peerLastSeenAt;
    }

    public void setPeerLastSeenAt(long peerLastSeenAt) {
        this.peerLastSeenAt = peerLastSeenAt;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(long endedAt) {
        this.endedAt = endedAt;
    }

    public long getAbnormalAt() {
        return abnormalAt;
    }

    public void setAbnormalAt(long abnormalAt) {
        this.abnormalAt = abnormalAt;
    }

    public long getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(long lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getLastEvent() {
        return lastEvent;
    }

    public void setLastEvent(String lastEvent) {
        this.lastEvent = lastEvent;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
