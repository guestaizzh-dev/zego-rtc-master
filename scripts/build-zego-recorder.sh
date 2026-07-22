#!/usr/bin/env sh
set -eu

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RECORDER_DIR="$ROOT_DIR/tools/zego-recorder"
LIB_DIR="$ROOT_DIR/third_party/zego-local-recording/libs"
BUILD_DIR="$RECORDER_DIR/build"
CLASSES_DIR="$BUILD_DIR/classes"
JAR_FILE="$BUILD_DIR/zego-local-recorder.jar"

rm -rf "$CLASSES_DIR"
mkdir -p "$CLASSES_DIR"

javac --release 8 -encoding UTF-8 \
  -cp "$LIB_DIR/ZegoLiveRoom.jar" \
  -d "$CLASSES_DIR" \
  $(find "$RECORDER_DIR/src/main/java" -name '*.java')

jar cfe "$JAR_FILE" com.toyeah.dispatching.recording.ZegoLocalRecorderMain -C "$CLASSES_DIR" .

echo "$JAR_FILE"
