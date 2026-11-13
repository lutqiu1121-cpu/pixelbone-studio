#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
# ============================================================================
#  像素骨骼 PixelBone Studio —— 单文件网页 → Android WebView 壳 APK
#  不需要 Gradle / Android Studio / 网络，四步工具链直接出包：
#    网页 → 注入原生桥 → aapt2 编资源并链接 → javac/d8 出 dex → 合包 → apksigner 签名
#
#  用法：
#    ./build.sh                            # → dist/PixelBone-1.0.apk
#    VERSION_NAME=1.1 VERSION_CODE=2 ./build.sh
#
#  可用环境变量覆盖默认路径：JAVA_HOME / ANDROID_HOME / BUILD_TOOLS / AAPT2 / ANDROID_JAR
#  签名：默认用 build/pixelbone.jks（首次自动生成，debug 用途）；可用 KEYSTORE 等覆盖。
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

VERSION_NAME="${VERSION_NAME:-1.0}"
VERSION_CODE="${VERSION_CODE:-1}"
APP_SLUG="${APP_SLUG:-PixelBone}"

export JAVA_HOME="${JAVA_HOME:-$HOME/android-env/root/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="${BUILD_TOOLS:-$SDK/build-tools/37.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.0/android.jar}"
# build-tools 里的 aapt2 是 x86_64 主机版，手机（aarch64）上跑不了，必须用 Termux 的 aarch64 版
AAPT2="${AAPT2:-$HOME/android-env/root/data/data/com.termux/files/usr/bin/aapt2}"

KEYSTORE="${KEYSTORE:-build/pixelbone.jks}"
KS_PASS="${KS_PASS:-pixelbone}"
KEY_ALIAS="${KEY_ALIAS:-pixelbone}"

ANDROID="android"
mkdir -p build/{obj,dex,tools,preview,add} dist
rm -rf build/obj build/dex build/add
mkdir -p build/obj build/dex build/add

echo "==> 1/6 生成图标（居中骨形，5 档密度 PNG + 自适应 vector）"
javac -d build/tools scripts/IconGen.java
java -Djava.awt.headless=true -cp build/tools IconGen "$ANDROID/res" build/preview

echo "==> 2/6 网页 → android/assets/index.html（注入 blob 下载桥，单次幂等）"
python3 scripts/inject_shim.py web/index.html "$ANDROID/assets/index.html"

echo "==> 3/6 Java → class → dex"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -encoding UTF-8 \
      -d build/obj $(find "$ANDROID/src" -name '*.java')
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 24 \
     --lib "$ANDROID_JAR" --output build/dex $(find build/obj -name '*.class')

echo "==> 4/6 资源编译 + 链接（assets 直接进包）"
rm -f build/res.zip build/base.apk build/unsigned.apk
"$AAPT2" compile --dir "$ANDROID/res" -o build/res.zip
"$AAPT2" link -o build/base.apk -I "$ANDROID_JAR" \
        --manifest "$ANDROID/AndroidManifest.xml" -A "$ANDROID/assets" \
        --min-sdk-version 24 --target-sdk-version 34 \
        --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
        build/res.zip

echo "==> 5/6 合入 classes.dex + 对齐自检"
cp build/base.apk build/unsigned.apk
cp build/dex/classes.dex build/add/
( cd build/add && zip -q -X ../unsigned.apk classes.dex )
python3 - <<'PY'
import sys, zipfile
z = zipfile.ZipFile('build/unsigned.apk')
arc = z.getinfo('resources.arsc')
off = arc.header_offset + 30 + len(arc.filename) + len(arc.extra)
if arc.compress_type != zipfile.ZIP_STORED or off % 4:
    sys.exit('resources.arsc 未满足 targetSdk30+ 要求：compress=%d offset=%d' % (arc.compress_type, off))
names = set(z.namelist())
for need in ('AndroidManifest.xml', 'classes.dex', 'assets/index.html', 'resources.arsc'):
    if need not in names:
        sys.exit('缺少 %s' % need)
print('    resources.arsc: STORED + 4 字节对齐（offset=%d）；entries=%d' % (off, len(names)))
PY

echo "==> 6/6 签名（v2 + v3）"
if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 \
          -validity 10000 -storepass "$KS_PASS" -keypass "$KS_PASS" \
          -dname "CN=PixelBone Studio, OU=App, O=PixelBone, L=NA, S=NA, C=CN"
fi
java -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
     --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
     --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
     --out "dist/$APP_SLUG-$VERSION_NAME.apk" build/unsigned.apk

echo "==> 自检"
java -jar "$BT/lib/apksigner.jar" verify --verbose "dist/$APP_SLUG-$VERSION_NAME.apk" | head -4
"$AAPT2" dump badging "dist/$APP_SLUG-$VERSION_NAME.apk" | head -5
echo
echo "OK  →  $ROOT/dist/$APP_SLUG-$VERSION_NAME.apk  ($(stat -c%s "dist/$APP_SLUG-$VERSION_NAME.apk") bytes)"
