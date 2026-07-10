package com.toyeah.dispatching.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.CopyObjectRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URL;
import java.util.Date;

@Service
public class OssStorageService {

    @Value("${recording.oss.enabled:false}")
    private boolean enabled;

    @Value("${recording.oss.endpoint:}")
    private String endpoint;

    @Value("${recording.oss.access-key-id:}")
    private String accessKeyId;

    @Value("${recording.oss.access-key-secret:}")
    private String accessKeySecret;

    @Value("${recording.oss.bucket:}")
    private String bucket;

    @Value("${recording.oss.public-base-url:}")
    private String publicBaseUrl;

    @Value("${recording.oss.object-prefix:rtc-recordings/}")
    private String objectPrefix;

    public boolean isEnabled() {
        return enabled;
    }

    public UploadResult upload(File file, String roomID, String taskID) {
        if (!enabled) {
            return null;
        }
        validateConfigured();
        if (file == null || !file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("录制文件不存在");
        }

        String objectKey = buildObjectKey(roomID, taskID, file.getName());
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ObjectMetadata metadata = previewMetadata(file.length());
            ossClient.putObject(new PutObjectRequest(bucket, objectKey, file, metadata));
        } finally {
            ossClient.shutdown();
        }
        String url = objectKey;
        if (publicBaseUrl == null || publicBaseUrl.trim().isEmpty()) {
            return new UploadResult(bucket, objectKey, url);
        }
        url = trimRight(publicBaseUrl, "/") + "/" + objectKey;
        return new UploadResult(bucket, objectKey, url);
    }

    public String generateSignedUrl(String objectKey, long expireSeconds) {
        if (!enabled) {
            throw new IllegalStateException("OSS 未启用，无法生成播放地址");
        }
        validateConfigured();
        if (isBlank(objectKey)) {
            throw new IllegalArgumentException("录制文件 OSS object key 为空");
        }
        long safeExpireSeconds = Math.max(60L, expireSeconds);
        Date expiration = new Date(System.currentTimeMillis() + safeExpireSeconds * 1000L);
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            ensureInlinePreviewMetadata(ossClient, objectKey);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.GET);
            request.setExpiration(expiration);
            ResponseHeaderOverrides responseHeaders = new ResponseHeaderOverrides();
            responseHeaders.setContentDisposition("inline");
            request.setResponseHeaders(responseHeaders);
            URL url = ossClient.generatePresignedUrl(request);
            return url.toString();
        } finally {
            ossClient.shutdown();
        }
    }

    public String getBucket() {
        return bucket;
    }

    private String buildObjectKey(String roomID, String taskID, String fileName) {
        String prefix = safePrefix(objectPrefix);
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        return prefix + safeSegment(roomID) + "/" + safeSegment(taskID) + "/" + safeFileName(fileName);
    }

    private void validateConfigured() {
        if (isBlank(endpoint) || isBlank(accessKeyId) || isBlank(accessKeySecret) || isBlank(bucket)) {
            throw new IllegalStateException("OSS 上传已启用，但 endpoint、accessKey 或 bucket 未完整配置");
        }
    }

    private ObjectMetadata previewMetadata(long contentLength) {
        ObjectMetadata metadata = new ObjectMetadata();
        if (contentLength >= 0) {
            metadata.setContentLength(contentLength);
        }
        metadata.setContentType("video/mp4");
        metadata.setContentDisposition("inline");
        return metadata;
    }

    private void ensureInlinePreviewMetadata(OSS ossClient, String objectKey) {
        ObjectMetadata metadata = ossClient.getObjectMetadata(bucket, objectKey);
        String contentDisposition = metadata == null ? null : metadata.getContentDisposition();
        String contentType = metadata == null ? null : metadata.getContentType();
        if ("inline".equalsIgnoreCase(contentDisposition) && "video/mp4".equalsIgnoreCase(contentType)) {
            return;
        }
        ObjectMetadata newMetadata = previewMetadata(-1);
        CopyObjectRequest copyRequest = new CopyObjectRequest(bucket, objectKey, bucket, objectKey);
        copyRequest.setNewObjectMetadata(newMetadata);
        ossClient.copyObject(copyRequest);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimRight(String value, String suffix) {
        String result = value;
        while (result.endsWith(suffix)) {
            result = result.substring(0, result.length() - suffix.length());
        }
        return result;
    }

    private String safePrefix(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String[] parts = value.trim().replace("\\", "/").split("/");
        for (String part : parts) {
            String safe = safeSegment(part);
            if (safe.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("/");
            }
            builder.append(safe);
        }
        return builder.toString();
    }

    private String safeFileName(String value) {
        return safeSegment(new File(value == null ? "recording.mp4" : value).getName());
    }

    private String safeSegment(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    public static class UploadResult {
        private final String bucket;
        private final String objectKey;
        private final String url;

        public UploadResult(String bucket, String objectKey, String url) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.url = url;
        }

        public String getBucket() {
            return bucket;
        }

        public String getObjectKey() {
            return objectKey;
        }

        public String getUrl() {
            return url;
        }
    }
}
