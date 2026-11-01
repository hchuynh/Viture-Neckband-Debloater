#!/usr/bin/env bash
# Unit tests for TV Home's pure logic, on the computer (no device). Needs JDK 11+ and android.jar.
# Usage: ANDROID_JAR=/path/to/platforms/android-33/android.jar tvhome/test/run-unit-tests.sh
set -e; cd "$(dirname "$0")/.."
: "${ANDROID_JAR:?set ANDROID_JAR to platforms/android-33/android.jar}"
out=$(mktemp -d); trap 'rm -rf "$out"' EXIT
javac -nowarn -classpath "$ANDROID_JAR" -d "$out" \
  src/com/hoang/vhome/*.java test/com/hoang/vhome/*.java 2>&1 | grep -v "^Note:" || true
java -cp "$out:$ANDROID_JAR" com.hoang.vhome.UnitTests
