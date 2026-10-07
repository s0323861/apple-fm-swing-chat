#!/bin/zsh
set -e

APP_DIR="${0:A:h}"
BUILD_DIR="$APP_DIR/build"

mkdir -p "$BUILD_DIR"
javac -encoding UTF-8 -d "$BUILD_DIR" "$APP_DIR/src/LocalLLMChat.java"
exec java -cp "$BUILD_DIR" LocalLLMChat
