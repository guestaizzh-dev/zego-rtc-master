package com.toyeah.dispatching.zego;

import com.alibaba.fastjson.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Random;

public final class TokenServerAssistant {
    public static final String PRIVILEGE_KEY_LOGIN = "1";
    public static final String PRIVILEGE_KEY_PUBLISH = "2";
    public static final int PRIVILEGE_ENABLE = 1;
    public static final int PRIVILEGE_DISABLE = 0;

    private static final String VERSION_FLAG = "04";
    private static final int GCM_NONCE_LENGTH = 12;
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte GCM_MODE = 1;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private TokenServerAssistant() {
    }

    public static String generateToken04(long appId,
                                         String userId,
                                         String secret,
                                         int effectiveTimeInSeconds,
                                         String payload) {
        validate(appId, userId, secret, effectiveTimeInSeconds);

        try {
            byte[] nonceBytes = new byte[GCM_NONCE_LENGTH];
            SECURE_RANDOM.nextBytes(nonceBytes);

            long nowTime = System.currentTimeMillis() / 1000;
            long expireTime = nowTime + effectiveTimeInSeconds;

            JSONObject json = new JSONObject();
            json.put("app_id", appId);
            json.put("user_id", userId);
            json.put("ctime", nowTime);
            json.put("expire", expireTime);
            json.put("nonce", new Random().nextInt());
            json.put("payload", payload);

            byte[] contentBytes = encryptGcm(json.toJSONString().getBytes("UTF-8"), secret, nonceBytes);
            ByteBuffer buffer = ByteBuffer.wrap(new byte[contentBytes.length + GCM_NONCE_LENGTH + 13]);
            buffer.order(ByteOrder.BIG_ENDIAN);
            buffer.putLong(expireTime);
            packBytes(nonceBytes, buffer);
            packBytes(contentBytes, buffer);
            buffer.put(GCM_MODE);

            return VERSION_FLAG + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("生成 ZEGO Token04 失败", e);
        }
    }

    private static void validate(long appId, String userId, String secret, int effectiveTimeInSeconds) {
        if (appId <= 0) {
            throw new IllegalArgumentException("ZEGO appID 无效");
        }
        if (userId == null || userId.trim().isEmpty() || userId.length() > 64) {
            throw new IllegalArgumentException("ZEGO 用户 ID 无效");
        }
        if (secret == null || secret.length() != 32) {
            throw new IllegalArgumentException("ZEGO serverSecret 必须为 32 个字符");
        }
        if (effectiveTimeInSeconds <= 0) {
            throw new IllegalArgumentException("ZEGO Token 有效期必须大于 0");
        }
    }

    private static byte[] encryptGcm(byte[] content, String secretKey, byte[] nonce) throws Exception {
        SecretKeySpec key = new SecretKeySpec(secretKey.getBytes("UTF-8"), "AES");
        Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(16 * 8, nonce));
        return cipher.doFinal(content == null ? new byte[]{} : content);
    }

    private static void packBytes(byte[] buffer, ByteBuffer target) {
        target.putShort((short) buffer.length);
        target.put(buffer);
    }
}
