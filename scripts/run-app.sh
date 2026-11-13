#!/usr/bin/env bash
# Builds Vanish, starts an emulator if none is running, installs and launches the app (macOS / Linux).
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
fail() { echo "ERROR: $1" >&2; exit 1; }

SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
[ -z "$SDK" ] && [ -d "$HOME/Library/Android/sdk" ] && SDK="$HOME/Library/Android/sdk"
[ -z "$SDK" ] && [ -d "$HOME/Android/Sdk" ] && SDK="$HOME/Android/Sdk"
[ -d "$SDK" ] || fail "Android SDK not found. Install Android Studio once or set ANDROID_HOME."
export ANDROID_HOME="$SDK"
echo "sdk.dir=$SDK" > "$APP/local.properties"

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA="$JAVA_HOME/bin/java"
elif [ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; JAVA="$JAVA_HOME/bin/java"
else JAVA="$(command -v java || true)"; fi
[ -n "$JAVA" ] || fail "Java not found. Install a JDK 17."

JAR="$APP/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$JAR" ]; then
  echo "Downloading gradle-wrapper.jar (one time)..."
  curl -fsSL -o "$JAR" "https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar" \
    || fail "Download failed. Open the 'app' folder once in Android Studio, then run this again."
fi

ADB="$SDK/platform-tools/adb"; EMU="$SDK/emulator/emulator"
[ -x "$ADB" ] || fail "adb not found (SDK Manager > SDK Tools > Platform-Tools)."
"$ADB" start-server >/dev/null
if ! "$ADB" devices | tail -n +2 | grep -q "device$"; then
  [ -x "$EMU" ] || fail "Emulator not installed (SDK Manager > SDK Tools > Android Emulator)."
  AVD="$("$EMU" -list-avds | grep -v ' ' | head -n 1)"
  [ -n "$AVD" ] || fail "No virtual device. Android Studio > Device Manager > Create Device (once)."
  echo "Starting emulator $AVD ..."
  nohup "$EMU" -avd "$AVD" >/dev/null 2>&1 &
  "$ADB" wait-for-device
  for _ in $(seq 1 80); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 3
  done
fi

echo "Building and installing (first run downloads Gradle and libraries: several minutes)..."
cd "$APP"
"$JAVA" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain installDebug --console=plain
"$ADB" shell am start -n com.example.vanish/.MainActivity >/dev/null
echo "Vanish is running. Relay address in the app: ws://10.0.2.2:8000/ws"
