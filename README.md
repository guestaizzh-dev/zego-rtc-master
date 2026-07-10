# ZEGO 音视频问诊服务

本项目是处惠宝音视频问诊封装，包含 Spring Boot 后端和 Vue 2 Web 通话页。Vue 构建产物会打包进 Spring Boot JAR。

## 当前范围

- Web 端使用本地保存的 ZEGO Web SDK，不依赖线上 CDN。
- Java 后端负责生成 ZEGO Token04。
- 微信小程序通过同一套 `/api/rtc/session` 接口获取通话配置。
- 已移除网易云信云端录制，改为本地服务器录制进程桥接，并在录制文件生成后上传 OSS。

## 必需环境变量

```bash
ZEGO_APP_ID=563636194
ZEGO_SERVER=wss://accesshub-wss.zego.im/accesshub
ZEGO_SERVER_SECRET=请填写服务端密钥
```

`ZEGO_SERVER_SECRET` 只能配置在服务器环境变量中，不能提交到代码仓库。

## 本地服务器录制

本地录制默认关闭。项目内已提供基于 ZEGO Linux Java 本地录制 SDK 的独立录制子程序：

```bash
./scripts/build-zego-recorder.sh
```

构建产物位于：

```text
tools/zego-recorder/build/zego-local-recorder.jar
```

上线录制时，在 Linux 服务器配置：

```bash
RECORDING_ENABLED=true
RECORDING_WORK_DIR=/data/rtc-recordings
RECORDING_COMMAND_TEMPLATE='/opt/yunxin-rtc-master/scripts/run-zego-recorder.sh --app-id {appID} --room-id {roomID} --user-id {userID} --token {token} --output {outputFile} --mode mix --log-dir /data/rtc-recordings/zego-logs'
RECORDING_MAX_SECONDS=600
```

`RECORDING_COMMAND_TEMPLATE` 支持这些占位符：

- `{roomID}`：房间号。
- `{taskID}`：录制任务 ID。
- `{outputFile}`：录制输出文件路径。
- `{appID}`：ZEGO AppID。
- `{server}`：ZEGO Server 地址。当前录制子程序暂不需要这个参数，保留给后续 SDK 版本兼容。
- `{userID}`：录制进程使用的用户 ID。
- `{token}`：录制进程登录房间使用的 Token。

录制命令必须持续运行到录制结束，并将 MP4 文件写入 `{outputFile}`。`libzegoliveroomrecorder.so` 是 Linux native 库，真实录制需要在 Linux 服务器验证。

## OSS 上传

录制文件生成后可上传到 OSS。需要配置：

```bash
OSS_ENABLED=true
OSS_ENDPOINT=oss-cn-beijing.aliyuncs.com
OSS_ACCESS_KEY_ID=请填写 AccessKeyId
OSS_ACCESS_KEY_SECRET=请填写 AccessKeySecret
OSS_BUCKET=请填写 bucket
OSS_PUBLIC_BASE_URL=https://your-bucket.oss-cn-beijing.aliyuncs.com
OSS_OBJECT_PREFIX=rtc-recordings/
```

## 构建

```bash
cd front
npm install
npm run build
cd ..
./scripts/build-zego-recorder.sh
mvn clean package -DskipTests
```

Vue 构建产物会写入 `src/main/resources/static`，随后 Maven 会将静态资源打包进 `target/rtc-backend-1.0.0.jar`。
