# ZEGO 本地录制子程序

这是基于官方 `ZegoServerRecordingDemo-Java` 改造的独立录制入口，用于配合主后端的 `RECORDING_COMMAND_TEMPLATE`。

## 构建

```bash
./scripts/build-zego-recorder.sh
```

构建产物：

```text
tools/zego-recorder/build/zego-local-recorder.jar
```

## 运行

```bash
./scripts/run-zego-recorder.sh \
  --app-id 563636194 \
  --room-id demo-room \
  --user-id rec_demo_room \
  --token 'ZEGO_TOKEN' \
  --output /data/rtc-recordings/demo-room/rec_xxx.mp4 \
  --mode mix
```

默认 `--mode mix`，会把房间内最多 3 路流混成一个 MP4。这个模式最适合当前问诊回放和 OSS 存储。

## 支持参数

- `--app-id`：ZEGO AppID。
- `--room-id`：要录制的房间 ID。
- `--user-id`：录制机器人用户 ID。
- `--token`：录制机器人登录房间使用的 Token。
- `--output`：最终 MP4 输出路径。
- `--mode`：`mix` 或 `single`，默认 `mix`。
- `--duration-seconds`：录制时长。默认 `0`，表示持续运行直到进程被停止。
- `--log-dir`：ZEGO SDK 日志目录。
- `--width`：混流输出宽度，默认 `1280`。
- `--height`：混流输出高度，默认 `720`。
- `--fps`：混流输出帧率，默认 `15`。
- `--bitrate`：混流输出码率，默认 `1200000`。
- `--max-mix-streams`：最多混流路数，默认 `3`。

## 后端命令模板

主后端推荐配置：

```bash
RECORDING_ENABLED=true
RECORDING_WORK_DIR=/data/rtc-recordings
RECORDING_COMMAND_TEMPLATE='/opt/yunxin-rtc-master/scripts/run-zego-recorder.sh --app-id {appID} --room-id {roomID} --user-id {userID} --token {token} --output {outputFile} --mode mix --log-dir /data/rtc-recordings/zego-logs'
RECORDING_MAX_SECONDS=600
```

注意：`libzegoliveroomrecorder.so` 是 Linux native 库，真实录制必须在 Linux 服务器上验证。macOS 本机只能验证 Java 编译和打包。
