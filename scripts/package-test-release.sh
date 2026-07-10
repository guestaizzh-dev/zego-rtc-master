#!/usr/bin/env sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-$(date +%Y%m%d%H%M%S)}"
RELEASE_NAME="chbzg-zego-rtc-test-$VERSION"
BUILD_DIR="$ROOT_DIR/build/releases/$RELEASE_NAME"
ARCHIVE="$ROOT_DIR/build/releases/$RELEASE_NAME.tar.gz"

"$ROOT_DIR/scripts/build-zego-recorder.sh" >/dev/null
(cd "$ROOT_DIR" && mvn -q -DskipTests clean package)

rm -rf "$BUILD_DIR"
mkdir -p \
  "$BUILD_DIR/target" \
  "$BUILD_DIR/scripts" \
  "$BUILD_DIR/tools/zego-recorder/build" \
  "$BUILD_DIR/third_party/zego-local-recording/libs" \
  "$BUILD_DIR/db"

cp "$ROOT_DIR/target/rtc-backend-1.0.0.jar" "$BUILD_DIR/target/"
cp "$ROOT_DIR/scripts/run-zego-recorder.sh" "$BUILD_DIR/scripts/"
cp "$ROOT_DIR/tools/zego-recorder/build/zego-local-recorder.jar" "$BUILD_DIR/tools/zego-recorder/build/"
cp "$ROOT_DIR/third_party/zego-local-recording/libs/ZegoLiveRoom.jar" "$BUILD_DIR/third_party/zego-local-recording/libs/"
cp "$ROOT_DIR/third_party/zego-local-recording/libs/libzegoliveroomrecorder.so" "$BUILD_DIR/third_party/zego-local-recording/libs/"
cp "$ROOT_DIR/src/main/resources/db/rtc_mysql.sql" "$BUILD_DIR/db/"
cp "$ROOT_DIR/测试服务器部署方案.md" "$BUILD_DIR/"

chmod 755 "$BUILD_DIR/scripts/run-zego-recorder.sh"
tar -C "$ROOT_DIR/build/releases" -czf "$ARCHIVE" "$RELEASE_NAME"

if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$ARCHIVE" > "$ARCHIVE.sha256"
else
  sha256sum "$ARCHIVE" > "$ARCHIVE.sha256"
fi

printf '%s\n' "$ARCHIVE"
