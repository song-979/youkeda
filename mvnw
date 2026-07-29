#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MAVEN_VERSION=3.9.9
DIST_NAME=apache-maven-"$MAVEN_VERSION"
WRAPPER_DIR="$SCRIPT_DIR/.mvn/wrapper"
DIST_DIR="$SCRIPT_DIR/.mvn/$DIST_NAME"
ZIP_FILE="$WRAPPER_DIR/$DIST_NAME-bin.zip"
DOWNLOAD_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$MAVEN_VERSION/$DIST_NAME-bin.zip"

if [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then
  exec "$MAVEN_HOME/bin/mvn" "$@"
fi

if [ -n "${M2_HOME:-}" ] && [ -x "$M2_HOME/bin/mvn" ]; then
  exec "$M2_HOME/bin/mvn" "$@"
fi

if command -v mvn >/dev/null 2>&1; then
  exec mvn "$@"
fi

mkdir -p "$WRAPPER_DIR"

if [ ! -x "$DIST_DIR/bin/mvn" ]; then
  if [ ! -f "$ZIP_FILE" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "$DOWNLOAD_URL" -o "$ZIP_FILE"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP_FILE" "$DOWNLOAD_URL"
    else
      echo "curl or wget is required to bootstrap Maven." >&2
      exit 1
    fi
  fi

  unzip -oq "$ZIP_FILE" -d "$SCRIPT_DIR/.mvn"
fi

exec "$DIST_DIR/bin/mvn" "$@"
