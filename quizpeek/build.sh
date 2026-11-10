#!/usr/bin/env bash
# 手工打包：aapt -> javac -> d8 -> 塞 dex 并对齐 -> apksigner（不走 gradle）
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/workspace/android-sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
cd "$(dirname "$0")"

[ -f "$JAR" ] || { echo "缺 android.jar: $JAR"; exit 1; }

rm -rf build gen
mkdir -p build/classes gen

aapt package -f -M AndroidManifest.xml -S res -I "$JAR" \
  -F build/base.apk --min-sdk-version 30 --target-sdk-version 33

find src -name '*.java' > build/sources.txt
javac -nowarn --release 8 -classpath "$JAR" \
  -d build/classes @build/sources.txt

mkdir -p build/dex
"$BT/d8" --lib "$JAR" --min-api 30 --output build/dex $(find build/classes -name '*.class')

python3 pack.py build/base.apk build/dex/classes.dex build/aligned.apk

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android \
    -alias qp -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=QuizPeek, O=Local, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out QuizPeek.apk build/aligned.apk
"$BT/apksigner" verify QuizPeek.apk >/dev/null
echo "OK"; ls -lh QuizPeek.apk
