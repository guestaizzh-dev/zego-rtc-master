# ZEGO 本地服务端录制 SDK

本目录用于保存 ZEGO Linux Java 本地录制 SDK 的二进制依赖。

## 已放入文件

- `libs/ZegoLiveRoom.jar`
- `libs/libzegoliveroomrecorder.so`

这两个文件是 Java/JNI SDK 依赖，不是可直接运行的录制程序。当前项目已经在 `tools/zego-recorder` 中实现了一个调用 `ZegoLiveRoom.jar` 的独立录制入口。

## 还需要补充

- Linux 服务器运行时需要让 JVM 能加载 native 库，`scripts/run-zego-recorder.sh` 已通过 `-Djava.library.path` 指向本目录下的 `libs`。
- 需要在 Linux 服务器真实验证 `.so` 加载、登录房间、混流录制和 MP4 落盘。
- 录制进程输出 MP4 后，当前后端会负责按配置上传到 OSS。

## SDK 可用能力确认

`ZegoLiveRoom.jar` 暴露了这些关键录制 API：

- `initSDK`
- `setCustomToken`
- `setUser`
- `loginRoom`
- `startRecordSingleStream`
- `stopRecordSingleStream`
- `startRecordMixStream`
- `stopRecordMixStream`

因此这里不使用 FFmpeg。当前已补充基于 ZEGO 本地录制 SDK 的 Java 录制封装，下一步是 Linux 真机验证。
