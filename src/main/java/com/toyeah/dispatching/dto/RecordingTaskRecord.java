package com.toyeah.dispatching.dto;

public class RecordingTaskRecord {
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
    private long startedAt;
    private long endedAt;
    private long uploadedAt;
    private int uploadAttempts;
    private long nextUploadAt;
    private String message;
    private String lastError;
    private long createdAt;
    private long updatedAt;

    public String getTaskID() {
        return taskID;
    }

    public void setTaskID(String taskID) {
        this.taskID = taskID;
    }

    public String getRoomID() {
        return roomID;
    }

    public void setRoomID(String roomID) {
        this.roomID = roomID;
    }

    public String getOrderID() {
        return orderID;
    }

    public void setOrderID(String orderID) {
        this.orderID = orderID;
    }

    public String getDoctorUserID() {
        return doctorUserID;
    }

    public void setDoctorUserID(String doctorUserID) {
        this.doctorUserID = doctorUserID;
    }

    public String getPeerUserID() {
        return peerUserID;
    }

    public void setPeerUserID(String peerUserID) {
        this.peerUserID = peerUserID;
    }

    public String getDoctorName() {
        return doctorName;
    }

    public void setDoctorName(String doctorName) {
        this.doctorName = doctorName;
    }

    public String getPeerName() {
        return peerName;
    }

    public void setPeerName(String peerName) {
        this.peerName = peerName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(String outputFile) {
        this.outputFile = outputFile;
    }

    public String getLogFile() {
        return logFile;
    }

    public void setLogFile(String logFile) {
        this.logFile = logFile;
    }

    public boolean isLocalFileDeleted() {
        return localFileDeleted;
    }

    public void setLocalFileDeleted(boolean localFileDeleted) {
        this.localFileDeleted = localFileDeleted;
    }

    public long getLocalFileDeletedAt() {
        return localFileDeletedAt;
    }

    public void setLocalFileDeletedAt(long localFileDeletedAt) {
        this.localFileDeletedAt = localFileDeletedAt;
    }

    public String getOssBucket() {
        return ossBucket;
    }

    public void setOssBucket(String ossBucket) {
        this.ossBucket = ossBucket;
    }

    public String getOssObjectKey() {
        return ossObjectKey;
    }

    public void setOssObjectKey(String ossObjectKey) {
        this.ossObjectKey = ossObjectKey;
    }

    public String getOssUrl() {
        return ossUrl;
    }

    public void setOssUrl(String ossUrl) {
        this.ossUrl = ossUrl;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public Double getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Double durationSeconds) {
        this.durationSeconds = durationSeconds;
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

    public long getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(long uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public int getUploadAttempts() {
        return uploadAttempts;
    }

    public void setUploadAttempts(int uploadAttempts) {
        this.uploadAttempts = uploadAttempts;
    }

    public long getNextUploadAt() {
        return nextUploadAt;
    }

    public void setNextUploadAt(long nextUploadAt) {
        this.nextUploadAt = nextUploadAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
