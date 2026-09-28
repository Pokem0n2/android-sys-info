#!/usr/bin/env bash
# 构建产物：asi-z-v{version}.apk（asi = android-sys-info 缩写）
# Requires: ANDROID_HOME set, build-tools 34.0.0, platforms;android-34
# On aarch64 (e.g. DGX Spark), uses box64 for x86_64 aapt2/zipalign/aapt
# and calls d8/apksigner through their JARs (the bash wrappers confuse box64)
set -e
WS="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
BT="$ANDROID_HOME/build-tools/34.0.0"
D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"
HOST_ARCH=$(uname -m)
if [ "$HOST_ARCH" = "x86_64" ]; then
  X=aapt2; Y="bash $BT/d8"; Z="bash $BT/apksigner"; W=zipalign; A=aapt
else
  X="box64 $BT/aapt2"
  Y="java -cp $D8_JAR com.android.tools.r8.D8"
  Z="java -jar $APKSIGNER_JAR"
  W="box64 $BT/zipalign"
  A="box64 $BT/aapt"
fi
APK_DIR="$WS/apk"
BUILD_DIR="$APK_DIR/build"
VERSION_TAG=$(cd "$WS" && git describe --tags --abbrev=0 2>/dev/null || echo "v0.0.0")
VERSION="${VERSION_TAG#v}"
# versionCode: semver "0.2.1" → integer (major*10000 + minor*100 + patch)
VC_MAJOR=$(echo "$VERSION" | cut -d. -f1)
VC_MINOR=$(echo "$VERSION" | cut -d. -f2)
VC_PATCH=$(echo "$VERSION" | cut -d. -f3)
VC_PATCH="${VC_PATCH:-0}"
VC_MINOR="${VC_MINOR:-0}"
VC_MAJOR="${VC_MAJOR:-0}"
VERSION_CODE=$(( VC_MAJOR * 10000 + VC_MINOR * 100 + VC_PATCH ))
APK_NAME="asi-z-v${VERSION}"
echo "Building: $APK_NAME.apk (versionCode=$VERSION_CODE, versionName=$VERSION)"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/compiled"
# Inject version into AndroidManifest.xml (aapt2 --version-name is unreliable under box64)
MANIFEST_TMP="$BUILD_DIR/AndroidManifest.xml"
sed -e "s/android:versionCode=\"[^\"]*\"/android:versionCode=\"$VERSION_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION\"/" \
    "$APK_DIR/AndroidManifest.xml" > "$MANIFEST_TMP"
echo "Manifest version: code=$VERSION_CODE name=$VERSION"
echo "=== 1. Compile Java ==="
find "$APK_DIR/src" -name '*.java' > "$BUILD_DIR/sources.txt"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$BUILD_DIR/obj" \
  @"$BUILD_DIR/sources.txt"
echo "=== 2. DEX ==="
find "$BUILD_DIR/obj" -name '*.class' > "$BUILD_DIR/classes.txt"
$Y --output "$BUILD_DIR/dex" \
  --min-api 21 \
  --lib "$ANDROID_JAR" \
  --release \
  $(cat "$BUILD_DIR/classes.txt")
echo "=== 3. aapt2 compile ==="
$X compile -o "$BUILD_DIR/compiled/" --dir "$APK_DIR/res"
echo "=== 4. aapt2 link ==="
$X link -o "$BUILD_DIR/${APK_NAME}.unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST_TMP" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION" \
  -A "$APK_DIR/assets" \
  "$BUILD_DIR/compiled/"*.flat
echo "=== 5. Add classes.dex (must sit at APK root) ==="
cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/classes.dex"
cd "$BUILD_DIR"
$A add "${APK_NAME}.unsigned.apk" classes.dex
echo "=== 6. zipalign ==="
cd "$WS"
$W -f 4 "$BUILD_DIR/${APK_NAME}.unsigned.apk" "$BUILD_DIR/${APK_NAME}.aligned.apk"
echo "=== 7. Sign (v1+v2+v3) ==="
if [ ! -f "$HOME/.android/debug.keystore" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair -keystore "$HOME/.android/debug.keystore" \
    -alias androiddebugkey -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 4096 -validity 10000
fi
$Z sign --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
  --out "$WS/${APK_NAME}.apk" \
  "$BUILD_DIR/${APK_NAME}.aligned.apk"
echo "=== Done ==="
ls -la "$WS/${APK_NAME}.apk"
