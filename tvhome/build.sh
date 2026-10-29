#!/usr/bin/env bash
# Build TVHome.apk from source. Needs: JDK 11+, Android build-tools (aapt2, d8, zipalign, apksigner) and an android.jar (API 33).
# Usage: BUILD_TOOLS=/path/to/build-tools/34.0.0 ANDROID_JAR=/path/to/platforms/android-33/android.jar ./build.sh
set -e; cd "$(dirname "$0")"
: "${BUILD_TOOLS:?set BUILD_TOOLS to an Android build-tools folder}"; : "${ANDROID_JAR:?set ANDROID_JAR to platforms/android-33/android.jar}"
KS="${KEYSTORE:-tvhome.keystore}"; KP="${KEYPASS:-tvhome}"
rm -rf build && mkdir -p build/obj
"$BUILD_TOOLS/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" -d build/obj src/com/hoang/vhome/*.java
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 29 --output build $(find build/obj -name '*.class')
cp build/base.apk build/unsigned.apk && (cd build && zip -qj unsigned.apk classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 build/unsigned.apk build/aligned.apk
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$KP" -keypass "$KP" -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TV Home"
ALIAS="${KEYALIAS:-$(keytool -list -keystore "$KS" -storepass "$KP" 2>/dev/null | awk -F, '/PrivateKeyEntry/{print $1; exit}')}"
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-pass "pass:$KP" --ks-key-alias "$ALIAS" --out TVHome.apk build/aligned.apk
rm -f TVHome.apk.idsig; "$BUILD_TOOLS/apksigner" verify TVHome.apk && echo "built $(pwd)/TVHome.apk"
# Note: an APK signed with a different key cannot update an installed copy; vnd.sh tvhome install reinstalls in that case.
