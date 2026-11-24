#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
TOOLS="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
if [[ ! -x "$TOOLS/aapt2" || ! -f "$PLATFORM" ]]; then
  echo 'Install Android SDK platform 35 and build-tools 35.0.0, then set ANDROID_HOME.' >&2
  exit 1
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
rm -rf build/work
mkdir -p build/work/generated build/work/classes build/work/dex
WORK="$PWD/build/work"
SRC="$PWD/app/src/main"
"$TOOLS/aapt2" compile --dir "$SRC/res" -o "$WORK/res.zip"
"$TOOLS/aapt2" link -I "$PLATFORM" --manifest "$SRC/AndroidManifest.xml" --java "$WORK/generated" -o "$WORK/resources.apk" "$WORK/res.zip"
# Relative paths in this list keep javac's argument file valid if the project path contains spaces.
(cd "$WORK"; find ../../app/src/main/java generated -name '*.java' | sort > sources)
(cd "$WORK"; javac --release 11 -Xlint:-options -classpath "$PLATFORM" -d classes @sources)
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$TOOLS/d8" --release --min-api 31 --lib "$PLATFORM" --output "$WORK/dex" "$WORK/classes.jar"
(cd "$WORK/dex"; zip -q "$WORK/resources.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$WORK/resources.apk" "$WORK/aligned.apk"
# Use a dedicated local sideload key; the standard Android debug keystore password is not a secret.
KEY="${HOME}/.android/solarbar-sideload.jks"
if [[ ! -f "$KEY" ]]; then
  (umask 077; mkdir -p "$(dirname "$KEY")"; keytool -genkeypair -keystore "$KEY" -storepass android -keypass android \
    -alias solarbar -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=SolarBar Android Local Sideload' -noprompt)
fi
"$TOOLS/apksigner" sign --ks "$KEY" --ks-key-alias solarbar --ks-pass pass:android --key-pass pass:android \
  --out "$WORK/SolarBar.apk" "$WORK/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$WORK/SolarBar.apk"
mv -f "$WORK/SolarBar.apk" build/SolarBar.apk
printf '\nAPK: %s/build/SolarBar.apk\n' "$PWD"
