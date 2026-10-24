#!/usr/bin/env bash
# 手工构建 DoubleTapTorch.apk（不依赖 Gradle / aapt2）
#
# 依赖：
#   - JDK 17+
#   - Android SDK 的 android.jar（API >= 33）
#   - Android build-tools（d8、apksigner）
#   - Xposed API jar（de.robv.android.xposed:api:82）
set -euo pipefail

cd "$(dirname "$0")"

# ============ 改这里 ============
ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-36/android.jar}"
D8="${D8:-d8}"
APKSIGNER_JAR="${APKSIGNER_JAR:-$HOME/android-sdk/build-tools/34.0.0/lib/apksigner.jar}"
XPOSED_API="${XPOSED_API:-$HOME/xposed-api-82.jar}"
KS="${KS:-debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-k}"
OUT_APK="${OUT_APK:-DoubleTapTorch.apk}"
# ===============================

for f in "$ANDROID_JAR" "$XPOSED_API" "$APKSIGNER_JAR"; do
    [ -f "$f" ] || { echo "缺少文件: $f"; exit 1; }
done

rm -rf out
mkdir -p out/classes out/dex

echo "[1/5] 生成二进制 AndroidManifest.xml"
python3 tools/mkaxml.py out/AndroidManifest.xml

echo "[2/5] 编译 Java"
javac -encoding UTF-8 -source 8 -target 8 \
      -bootclasspath "$ANDROID_JAR" \
      -cp "$XPOSED_API" \
      -d out/classes \
      src/com/jcy/doubletorch/MainHook.java

echo "[3/5] 转 dex"
"$D8" --lib "$ANDROID_JAR" --min-api 26 \
      --output out/dex out/classes/com/jcy/doubletorch/*.class

echo "[4/5] 打包"
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('out/unsigned.apk', 'w', zipfile.ZIP_DEFLATED) as z:
    z.write('out/AndroidManifest.xml', 'AndroidManifest.xml')
    z.write('out/dex/classes.dex', 'classes.dex')
    z.write('assets/xposed_init', 'assets/xposed_init')
PY

echo "[5/5] 签名"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"

java -jar "$APKSIGNER_JAR" sign \
     --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
     --ks-key-alias "$KS_ALIAS" \
     --out "$OUT_APK" out/unsigned.apk

echo "完成: $OUT_APK"
ls -l "$OUT_APK"
