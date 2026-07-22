#!/usr/bin/env sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LIB_DIR="$ROOT_DIR/third_party/zego-local-recording/libs"
RECORDER_JAR="$ROOT_DIR/tools/zego-recorder/build/zego-local-recorder.jar"

if [ ! -f "$RECORDER_JAR" ]; then
  "$ROOT_DIR/scripts/build-zego-recorder.sh" >/dev/null
fi

exec java \
  -Djava.library.path="$LIB_DIR" \
  -cp "$RECORDER_JAR:$LIB_DIR/ZegoLiveRoom.jar" \
  com.toyeah.dispatching.recording.ZegoLocalRecorderMain "$@"
