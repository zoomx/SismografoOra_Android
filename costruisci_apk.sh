#!/usr/bin/env bash
# Compila l'APK di "Sismografo Ora" in un Codespace GitHub (o qualsiasi Linux con Internet).
# Uso, dalla cartella del progetto:   bash costruisci_apk.sh
# Fa, se manca, tutto il necessario: JDK 17, wrapper di Gradle (gradlew), Android SDK da riga di
# comando, poi lancia  ./gradlew assembleDebug.
set -e
cd "$(dirname "$0")"

# ---------------------------------------------------------------- 1) Java 17..21
java_major() { java -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/'; }
if ! command -v java >/dev/null 2>&1 || [ "$(java_major)" -lt 17 ] || [ "$(java_major)" -gt 21 ]; then
  echo ">> Installo OpenJDK 17"
  sudo apt-get update -y
  sudo apt-get install -y openjdk-17-jdk-headless unzip
  export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac || ls /usr/lib/jvm/java-17-openjdk-*/bin/javac | head -1)")")")"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
echo ">> Java: $(java -version 2>&1 | head -1)"

# ---------------------------------------------------------------- 2) gradlew
if [ ! -f gradle/wrapper/gradle-wrapper.jar ] || [ ! -f gradlew ]; then
  echo ">> Genero gradlew (Gradle 8.9)"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/gradle.zip" https://services.gradle.org/distributions/gradle-8.9-bin.zip
  unzip -q "$tmp/gradle.zip" -d "$tmp"
  mkdir "$tmp/vuoto"
  # in una cartella vuota: il task "wrapper" non ha bisogno dell'Android SDK
  (cd "$tmp/vuoto" && "$tmp/gradle-8.9/bin/gradle" wrapper --gradle-version 8.9 --no-daemon -q)
  cp "$tmp/vuoto/gradlew" "$tmp/vuoto/gradlew.bat" .
  mkdir -p gradle/wrapper
  cp "$tmp/vuoto/gradle/wrapper/gradle-wrapper.jar" gradle/wrapper/
fi
chmod +x gradlew

# ---------------------------------------------------------------- 3) Android SDK
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKM" ]; then
  echo ">> Scarico gli strumenti da riga di comando dell'Android SDK"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fsSL -o /tmp/cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
yes | "$SDKM" --licenses >/dev/null 2>&1 || true
"$SDKM" "platform-tools" "platforms;android-35" "build-tools;34.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties

# ---------------------------------------------------------------- 4) compilazione
./gradlew assembleDebug
echo
echo "FATTO. APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"
echo "In Codespaces: tasto destro sul file nell'Esplora risorse > Download."
