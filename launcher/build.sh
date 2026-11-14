#!/usr/bin/env bash
# Builds launcher/build/dumbtv-home.apk from the bare SDK tools: no Gradle, no libraries.
# Needs a JDK 17+ and an Android SDK with platforms;android-$MIN_SDK and any build-tools.
#   MIN_SDK=25 (default)  one APK for Fire OS 6, 7 and 8: the published release. Compiling
#                         against android-25 makes javac reject any android.* API Fire OS 6
#                         lacks; java.* calls are checked against JDK 8 by --release 8, so keep
#                         them to what Android 7.1 has. Fire OS 7/8 see two normal permissions,
#                         RECEIVE_BOOT_COMPLETED and WAKE_LOCK; the Keeper's other two are capped
#                         at maxSdkVersion 27.
#   MIN_SDK=28            Android 9+ only, as releases up to 1.1 were
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# shellcheck disable=SC1091
source version.env
MIN_SDK=${MIN_SDK:-25}
WINDOWS=0
case $(uname -s) in MINGW*|MSYS*|CYGWIN*) WINDOWS=1 ;; esac

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ $WINDOWS == 1 && $SDK =~ ^[A-Za-z]: ]]; then SDK=$(cygpath -u "$SDK"); fi
if [[ -z $SDK ]]; then
  if [[ $WINDOWS == 1 && -n ${LOCALAPPDATA:-} ]]; then
    d=$(cygpath -u "$LOCALAPPDATA/Android/Sdk")
    [[ -f $d/platforms/android-$MIN_SDK/android.jar ]] && SDK=$d
  fi
fi
if [[ -z $SDK ]]; then
  for d in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk"; do
    if [[ -f $d/platforms/android-$MIN_SDK/android.jar ]]; then SDK=$d; break; fi
  done
fi
JAR=$SDK/platforms/android-$MIN_SDK/android.jar
BT=$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)
[[ -f $JAR ]] || { echo "error: $JAR missing — sdkmanager 'platforms;android-$MIN_SDK'" >&2; exit 1; }
[[ -x $BT/aapt2 ]] || { echo "error: no build-tools in $SDK — sdkmanager 'build-tools;34.0.0'" >&2; exit 1; }

JDK17_PATHS=(/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-17-openjdk)
if [[ $WINDOWS == 1 ]]; then
  for j in /c/Program\ Files/Eclipse\ Adoptium/jdk-17* /c/Program\ Files/Java/jdk-17*; do
    [[ -x $j/bin/javac.exe ]] && JDK17_PATHS+=("$j")
  done
fi
javac_major() { "$1/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p'; }

if [[ $WINDOWS == 1 && ${JAVA_HOME:-} =~ ^[A-Za-z]: ]]; then JAVA_HOME=$(cygpath -u "$JAVA_HOME"); fi
if [[ -z ${JAVA_HOME:-} || ! -x ${JAVA_HOME:-}/bin/javac ]]; then
  for j in "${JDK17_PATHS[@]}" /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
           "$(/usr/libexec/java_home 2>/dev/null || true)"; do
    if [[ -n $j && -x $j/bin/javac ]]; then JAVA_HOME=$j; break; fi
  done
fi
# build-tools 34's bundled d8 threw an internal NullPointerException dexing an anonymous inner
# class (Keeper$1) compiled by JDK 27, even with --release 8. JDK 17 builds it cleanly. Only 27
# was seen failing; 21 is a conservative cut-off, since macOS's java_home and a Linux distro's
# default javac both commonly land on something newer than 17 now.
if [[ -z ${JAVA_HOME:-} ]] || [[ $(javac_major "$JAVA_HOME") -ge 21 ]]; then
  for j in "${JDK17_PATHS[@]}"; do [[ -x $j/bin/javac ]] && { JAVA_HOME=$j; break; }; done
fi
[[ -x ${JAVA_HOME:-}/bin/javac ]] || {
  echo "error: no JDK found — brew install openjdk@17 (macOS) or pacman -S jdk17-openjdk (Arch)" >&2
  exit 1
}
if [[ $(javac_major "$JAVA_HOME") -ge 21 ]]; then
  echo "warning: $JAVA_HOME is JDK $(javac_major "$JAVA_HOME"); build-tools' d8 crashed on JDK 27 output — install a JDK 17 if this fails" >&2
fi
export JAVA_HOME

KEYSTORE=${DUMBTV_KEYSTORE:-$PWD/release.keystore}
KEYPASS=${DUMBTV_KEYPASS:-dumbtvhome}
if [[ ! -f $KEYSTORE ]]; then
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -alias home -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KEYPASS" -keypass "$KEYPASS" -dname "CN=dumbtv home" >/dev/null
  echo "created signing key $KEYSTORE (keep it: updates must be signed with the same key)"
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/dumbtv-home.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" build

"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml "$WORK/res.zip" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$WORK/gen" -o "$WORK/app.apk"

find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
if [[ $WINDOWS == 1 ]]; then
  # javac reads @files itself, so Git Bash cannot translate their POSIX paths.
  while IFS= read -r path; do printf '"%s"\n' "$(cygpath -am "$path")"; done \
    < "$WORK/sources.txt" > "$WORK/sources.windows.txt"
  SOURCES="$WORK/sources.windows.txt"
else
  SOURCES="$WORK/sources.txt"
fi
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$SOURCES"

find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
if [[ $WINDOWS == 1 ]]; then
  # The Windows SDK ships these as .bat files; invoking their bundled jars avoids
  # cmd.exe argument quoting and lets Git Bash use the same build flow as Unix.
  while IFS= read -r path; do cygpath -am "$path"; done \
    < "$WORK/classes.txt" > "$WORK/classes.windows.txt"
  "$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api "$MIN_SDK" --lib "$JAR" --output "$WORK" @"$WORK/classes.windows.txt"
else
  "$BT/d8" --release --min-api "$MIN_SDK" --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
fi
(cd "$WORK" && zip -q -X app.apk classes.dex)

"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
if [[ $WINDOWS == 1 ]]; then
  "$JAVA_HOME/bin/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
    --out build/dumbtv-home.apk "$WORK/aligned.apk"
  "$JAVA_HOME/bin/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify build/dumbtv-home.apk
else
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
    --out build/dumbtv-home.apk "$WORK/aligned.apk"
  "$BT/apksigner" verify build/dumbtv-home.apk
fi

# Amazon Home filters the package currently registered as HOME from its app drawer on some
# Fire OS builds. A tiny second package keeps a regular visible app entry for returning to us.
ENTRY_WORK="$WORK/returnhome"
mkdir -p "$ENTRY_WORK/gen" "$ENTRY_WORK/classes"
"$BT/aapt2" compile --dir returnhome/res -o "$ENTRY_WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest returnhome/AndroidManifest.xml "$ENTRY_WORK/res.zip" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$ENTRY_WORK/gen" -o "$ENTRY_WORK/app.apk"
find returnhome/src "$ENTRY_WORK/gen" -name '*.java' > "$ENTRY_WORK/sources.txt"
if [[ $WINDOWS == 1 ]]; then
  while IFS= read -r path; do printf '"%s"\n' "$(cygpath -am "$path")"; done \
    < "$ENTRY_WORK/sources.txt" > "$ENTRY_WORK/sources.windows.txt"
  ENTRY_SOURCES="$ENTRY_WORK/sources.windows.txt"
else
  ENTRY_SOURCES="$ENTRY_WORK/sources.txt"
fi
"$JAVA_HOME/bin/javac" --release 8 -Xlint:all -Xlint:-options -Werror -encoding UTF-8 \
  -classpath "$JAR" -d "$ENTRY_WORK/classes" @"$ENTRY_SOURCES"
find "$ENTRY_WORK/classes" -name '*.class' > "$ENTRY_WORK/classes.txt"
if [[ $WINDOWS == 1 ]]; then
  while IFS= read -r path; do cygpath -am "$path"; done \
    < "$ENTRY_WORK/classes.txt" > "$ENTRY_WORK/classes.windows.txt"
  "$JAVA_HOME/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api "$MIN_SDK" --lib "$JAR" --output "$ENTRY_WORK" \
    @"$ENTRY_WORK/classes.windows.txt"
else
  "$BT/d8" --release --min-api "$MIN_SDK" --lib "$JAR" --output "$ENTRY_WORK" \
    @"$ENTRY_WORK/classes.txt"
fi
(cd "$ENTRY_WORK" && zip -q -X app.apk classes.dex)
"$BT/zipalign" -f -p 4 "$ENTRY_WORK/app.apk" "$ENTRY_WORK/aligned.apk"
if [[ $WINDOWS == 1 ]]; then
  "$JAVA_HOME/bin/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
    --out build/dumbtv-returnhome.apk "$ENTRY_WORK/aligned.apk"
  "$JAVA_HOME/bin/java" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool verify \
    build/dumbtv-returnhome.apk
else
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
    --out build/dumbtv-returnhome.apk "$ENTRY_WORK/aligned.apk"
  "$BT/apksigner" verify build/dumbtv-returnhome.apk
fi

perms=$("$BT/aapt2" dump permissions build/dumbtv-home.apk | grep -c 'uses-permission' || true)
printf 'built build/dumbtv-home.apk  %s bytes  %s uses-permission entries\n' \
  "$(wc -c < build/dumbtv-home.apk | tr -d ' ')" "$perms"
printf 'built build/dumbtv-returnhome.apk  %s bytes\n' \
  "$(wc -c < build/dumbtv-returnhome.apk | tr -d ' ')"
