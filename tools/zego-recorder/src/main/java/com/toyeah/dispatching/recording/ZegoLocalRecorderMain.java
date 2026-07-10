package com.toyeah.dispatching.recording;

import com.zego.zegoliveroom.ZegoLiveRoom;
import com.zego.zegoliveroom.ZegoLiveRoomJNI;
import com.zego.zegoliveroom.callback.IZegoInitSDKCompletionCallback;
import com.zego.zegoliveroom.callback.IZegoLoginCompletionCallback;
import com.zego.zegoliveroom.callback.IZegoRoomCallback;
import com.zego.zegoliveroom.callback.IZegoStreamRecordCallback;
import com.zego.zegoliveroom.constants.ZegoMuxerOutType;
import com.zego.zegoliveroom.constants.ZegoMuxerStreamType;
import com.zego.zegoliveroom.constants.ZegoStreamUpdateType;
import com.zego.zegoliveroom.entity.ZegoAudioFrame;
import com.zego.zegoliveroom.entity.ZegoMixStreamRecordConfig;
import com.zego.zegoliveroom.entity.ZegoRecordSingleStreamConfig;
import com.zego.zegoliveroom.entity.ZegoStreamConfig;
import com.zego.zegoliveroom.entity.ZegoStreamInfo;
import com.zego.zegoliveroom.entity.ZegoUserState;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ZegoLocalRecorderMain {

    private static final int MODE_MIX = 1;
    private static final int MODE_SINGLE = 2;

    private final ZegoLiveRoom liveRoom = new ZegoLiveRoom();
    private final List<ZegoStreamInfo> streams = new ArrayList<>();
    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private final CountDownLatch loginLatch = new CountDownLatch(1);

    private Options options;
    private volatile boolean mixRecording;
    private volatile boolean stopping;
    private volatile int initErrorCode = -1;
    private volatile int loginErrorCode = -1;

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        ZegoLocalRecorderMain recorder = new ZegoLocalRecorderMain();
        Runtime.getRuntime().addShutdownHook(new Thread(recorder::stopSafely, "zego-recorder-shutdown"));
        int exitCode = recorder.run(options);
        System.exit(exitCode);
    }

    private int run(Options options) throws Exception {
        this.options = options;
        validateOptions(options);

        File outputFile = new File(options.output);
        File outputDir = outputFile.getParentFile();
        if (outputDir != null && !outputDir.exists() && !outputDir.mkdirs()) {
            throw new IllegalStateException("创建录制输出目录失败：" + outputDir.getAbsolutePath());
        }

        start();
        if (!loginLatch.await(options.loginTimeoutSeconds, TimeUnit.SECONDS)) {
            System.err.println("登录 ZEGO 房间超时，roomID=" + options.roomID);
            stopSafely();
            return 2;
        }
        if (initErrorCode != 0 || loginErrorCode != 0) {
            System.err.println("登录 ZEGO 房间失败，initErrorCode=" + initErrorCode + "，loginErrorCode=" + loginErrorCode);
            stopSafely();
            return 3;
        }

        if (options.durationSeconds > 0) {
            stopLatch.await(options.durationSeconds, TimeUnit.SECONDS);
        } else {
            stopLatch.await();
        }

        stopSafely();
        if (!outputFile.exists() || !outputFile.isFile() || outputFile.length() <= 0) {
            System.err.println("录制结束，但未生成有效 MP4 文件：" + outputFile.getAbsolutePath());
            return 4;
        }
        System.out.println("录制完成：" + outputFile.getAbsolutePath());
        return 0;
    }

    private void start() {
        if (options.logDir != null && !options.logDir.isEmpty()) {
            liveRoom.setLogDirAndSize(options.logDir, 5 * 1024 * 1024L);
        }
        liveRoom.setConfig("play_loop_retry=true");
        liveRoom.setZegoRoomCallback(roomCallback);
        liveRoom.setZegoStreamRecordCallback(recordCallback);
        liveRoom.setMuxerOutType(ZegoMuxerOutType.WRITE_FILE_ONLY);
        liveRoom.setUser(options.userID, options.userID);

        System.out.println("初始化 ZEGO 本地录制 SDK，appID=" + options.appID + "，roomID=" + options.roomID);
        liveRoom.initSDK(options.appID, new IZegoInitSDKCompletionCallback() {
            @Override
            public void onInitSDK(int errorCode) {
                initErrorCode = errorCode;
                System.out.println("ZEGO 本地录制 SDK 初始化结果：" + errorCode);
                if (errorCode != 0) {
                    loginLatch.countDown();
                    return;
                }
                liveRoom.setCustomToken(options.token);
                liveRoom.loginRoom(options.roomID, new IZegoLoginCompletionCallback() {
                    @Override
                    public void onLoginCompletion(int errorCode, String roomID) {
                        loginErrorCode = errorCode;
                        System.out.println("ZEGO 录制用户登录房间结果：" + errorCode + "，roomID=" + roomID);
                        loginLatch.countDown();
                    }
                });
            }
        });
    }

    private final IZegoRoomCallback roomCallback = new IZegoRoomCallback() {
        @Override
        public void onDisconnect(int errorCode, String roomID) {
            System.out.println("录制用户断开连接，errorCode=" + errorCode + "，roomID=" + roomID);
        }

        @Override
        public void onStreamUpdate(int type, ZegoStreamInfo[] listStreamInfo, String roomID) {
            System.out.println("房间流更新，type=" + type + "，streamCount=" + listStreamInfo.length + "，roomID=" + roomID);
            synchronized (streams) {
                if (type == ZegoStreamUpdateType.ADDED) {
                    for (ZegoStreamInfo streamInfo : listStreamInfo) {
                        streams.add(streamInfo);
                        System.out.println("发现可录制流：" + streamInfo.streamID);
                        if (options.mode == MODE_SINGLE) {
                            startSingleStreamRecord(streamInfo);
                        }
                    }
                    if (options.mode == MODE_MIX) {
                        startOrUpdateMixRecord();
                    }
                    return;
                }

                if (type == ZegoStreamUpdateType.DELETED) {
                    for (ZegoStreamInfo streamInfo : listStreamInfo) {
                        removeStream(streamInfo.streamID);
                        if (options.mode == MODE_SINGLE) {
                            liveRoom.stopRecordSingleStream(streamInfo.streamID);
                        }
                    }
                    if (options.mode == MODE_MIX) {
                        if (streams.isEmpty()) {
                            stopMixRecord();
                        } else {
                            liveRoom.updateInputStreamConfig(createMixStreamConfig());
                        }
                    }
                }
            }
        }

        @Override
        public void onExternalVideoDataSource(String roomID, ZegoLiveRoomJNI.IExternalVideoDataSource source) {
            System.out.println("收到外部视频数据源回调，roomID=" + roomID);
        }

        @Override
        public void onExternalMediaDataSource(String roomID, ZegoLiveRoomJNI.IExternalMediaDataSource source) {
            System.out.println("收到外部媒体数据源回调，roomID=" + roomID);
        }

        @Override
        public void onKickOut(int errorCode, String reason) {
            System.out.println("录制用户被踢出房间，errorCode=" + errorCode + "，reason=" + reason);
            stopLatch.countDown();
        }

        @Override
        public void onUserUpdate(ZegoUserState[] listUser, int updateType, String roomID) {
            System.out.println("房间用户更新，updateType=" + updateType + "，userCount=" + listUser.length + "，roomID=" + roomID);
        }

        @Override
        public void onUpdateOnlineCount(int onlineCount, String roomID) {
            System.out.println("房间在线人数更新，onlineCount=" + onlineCount + "，roomID=" + roomID);
        }
    };

    private final IZegoStreamRecordCallback recordCallback = new IZegoStreamRecordCallback() {
        @Override
        public void onStreamRecordBegin(String streamID, String pathAndName) {
            System.out.println("开始录制流，streamID=" + streamID + "，path=" + pathAndName);
        }

        @Override
        public void onStreamRecordEnd(String streamID, String pathAndName, int reason) {
            System.out.println("录制流结束，streamID=" + streamID + "，path=" + pathAndName + "，reason=" + reason);
        }

        @Override
        public void onStreamRecordEvent(String streamID, int event) {
            System.out.println("录制事件，streamID=" + streamID + "，event=" + event);
        }

        @Override
        public void onStreamRecordData(String streamID, byte[] data) {
            System.out.println("收到录制数据回调，streamID=" + streamID + "，bytes=" + data.length);
        }

        @Override
        public void onSeek(String streamID, long offset, int whence) {
            System.out.println("录制 seek 回调，streamID=" + streamID + "，offset=" + offset + "，whence=" + whence);
        }

        @Override
        public void onMixStreamRecordUpdate(String[] listStreamID) {
            System.out.println("混流录制输入流更新，streamCount=" + listStreamID.length);
        }

        @Override
        public void onRecordFilePath(String streamID, String filePath, int muxerType, long startTimestamp, long stopTimestamp) {
            System.out.println("录制文件生成，streamID=" + streamID + "，filePath=" + filePath
                    + "，muxerType=" + muxerType + "，start=" + startTimestamp + "，stop=" + stopTimestamp);
        }
    };

    private void startSingleStreamRecord(ZegoStreamInfo streamInfo) {
        ZegoRecordSingleStreamConfig config = new ZegoRecordSingleStreamConfig();
        String path = options.output;
        if (streams.size() > 1) {
            path = appendStreamID(options.output, streamInfo.streamID);
        }
        boolean ok = liveRoom.startRecordSingleStream(streamInfo.streamID, path, ZegoMuxerStreamType.BOTH, 0, config);
        System.out.println("启动单流录制：" + ok + "，streamID=" + streamInfo.streamID + "，path=" + path);
    }

    private void startOrUpdateMixRecord() {
        if (!mixRecording) {
            ZegoMixStreamRecordConfig config = new ZegoMixStreamRecordConfig();
            config.outputWidth = options.width;
            config.outputHeight = options.height;
            config.outputFPS = options.fps;
            config.outputBitrate = options.bitrate;
            config.outputBackgroundColor = 0x00000000;
            config.pathAndName = options.output;
            config.generateMP3 = false;
            config.muxerStreamType = ZegoMuxerStreamType.BOTH;
            config.inputStreams = new ArrayList<ZegoStreamConfig>();
            boolean ok = liveRoom.startRecordMixStream(config);
            mixRecording = ok;
            System.out.println("启动混流录制：" + ok + "，path=" + options.output);
        }
        liveRoom.updateInputStreamConfig(createMixStreamConfig());
    }

    private ZegoStreamConfig[] createMixStreamConfig() {
        int count = Math.min(streams.size(), options.maxMixStreams);
        ZegoStreamConfig[] configs = new ZegoStreamConfig[count];
        for (int i = 0; i < count; i++) {
            ZegoStreamInfo streamInfo = streams.get(i);
            ZegoStreamConfig config = new ZegoStreamConfig();
            config.streamID = streamInfo.streamID;
            config.layer = i;
            if (count == 1 || i == 0) {
                config.left = 0;
                config.top = 0;
                config.right = options.width;
                config.bottom = options.height;
            } else {
                int smallWidth = options.width / 3;
                int smallHeight = options.height / 3;
                int margin = 16;
                config.left = options.width - smallWidth - margin;
                config.top = options.height - (smallHeight + margin) * i;
                config.right = options.width - margin;
                config.bottom = config.top + smallHeight;
            }
            configs[i] = config;
            System.out.println("混流布局，streamID=" + config.streamID + "，left=" + config.left
                    + "，top=" + config.top + "，right=" + config.right + "，bottom=" + config.bottom);
        }
        return configs;
    }

    private void stopSafely() {
        if (stopping) {
            return;
        }
        stopping = true;
        try {
            synchronized (streams) {
                if (options != null && options.mode == MODE_SINGLE) {
                    for (ZegoStreamInfo streamInfo : streams) {
                        liveRoom.stopRecordSingleStream(streamInfo.streamID);
                    }
                }
                stopMixRecord();
                streams.clear();
            }
            liveRoom.logoutRoom();
            sleepQuietly(3000);
            liveRoom.setZegoRoomCallback(null);
            liveRoom.setZegoStreamRecordCallback(null);
            liveRoom.unInitSDK();
        } catch (Exception e) {
            System.err.println("停止录制时发生异常：" + e.getMessage());
        } finally {
            stopLatch.countDown();
        }
    }

    private void stopMixRecord() {
        if (mixRecording) {
            boolean ok = liveRoom.stopRecordMixStream();
            mixRecording = false;
            System.out.println("停止混流录制：" + ok);
        }
    }

    private void removeStream(String streamID) {
        Iterator<ZegoStreamInfo> iterator = streams.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().streamID.equals(streamID)) {
                iterator.remove();
            }
        }
    }

    private static String appendStreamID(String output, String streamID) {
        int dot = output.lastIndexOf('.');
        if (dot <= 0) {
            return output + "_" + sanitize(streamID);
        }
        return output.substring(0, dot) + "_" + sanitize(streamID) + output.substring(dot);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void validateOptions(Options options) {
        require(options.appID > 0, "appID 不能为空");
        require(notBlank(options.roomID), "roomID 不能为空");
        require(notBlank(options.userID), "userID 不能为空");
        require(notBlank(options.token), "token 不能为空");
        require(notBlank(options.output), "output 不能为空");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class Options {
        private long appID;
        private String roomID;
        private String userID;
        private String token;
        private String output;
        private String logDir;
        private int durationSeconds = 0;
        private int loginTimeoutSeconds = 30;
        private int mode = MODE_MIX;
        private int width = 1280;
        private int height = 720;
        private int fps = 15;
        private int bitrate = 1200 * 1000;
        private int maxMixStreams = 3;

        private static Options parse(String[] args) {
            Map<String, String> values = new ConcurrentHashMap<String, String>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    continue;
                }
                String key = arg.substring(2);
                String value = "true";
                int eq = key.indexOf('=');
                if (eq >= 0) {
                    value = key.substring(eq + 1);
                    key = key.substring(0, eq);
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                }
                values.put(key, value);
            }

            Options options = new Options();
            options.appID = longValue(values, "app-id", 0);
            options.roomID = values.get("room-id");
            options.userID = values.get("user-id");
            options.token = values.get("token");
            options.output = values.get("output");
            options.logDir = values.get("log-dir");
            options.durationSeconds = intValue(values, "duration-seconds", options.durationSeconds);
            options.loginTimeoutSeconds = intValue(values, "login-timeout-seconds", options.loginTimeoutSeconds);
            options.width = intValue(values, "width", options.width);
            options.height = intValue(values, "height", options.height);
            options.fps = intValue(values, "fps", options.fps);
            options.bitrate = intValue(values, "bitrate", options.bitrate);
            options.maxMixStreams = intValue(values, "max-mix-streams", options.maxMixStreams);
            String mode = values.get("mode");
            if ("single".equalsIgnoreCase(mode)) {
                options.mode = MODE_SINGLE;
            } else if ("mix".equalsIgnoreCase(mode) || mode == null || mode.trim().isEmpty()) {
                options.mode = MODE_MIX;
            } else {
                throw new IllegalArgumentException("不支持的录制模式：" + mode);
            }
            return options;
        }

        private static int intValue(Map<String, String> values, String key, int defaultValue) {
            String value = values.get(key);
            return notBlank(value) ? Integer.parseInt(value) : defaultValue;
        }

        private static long longValue(Map<String, String> values, String key, long defaultValue) {
            String value = values.get(key);
            return notBlank(value) ? Long.parseLong(value) : defaultValue;
        }
    }
}
