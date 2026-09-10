# FlockYou Companion (Android)

Dependency-free Android app that pairs with the flock-you detector over BLE:
a foreground service keeps the GATT link and a GPS watch alive with the
screen off, geotags every detection and mark-button press, and syncs batches
to the dashboard's `/api/companion/sync` endpoint whenever the phone can
reach the server. GPX export built in.

## Build (no Android Studio needed)

One-time toolchain (Linux, ~1GB total under `~/Android`):

```bash
mkdir -p ~/Android && cd ~/Android
curl -sL -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
tar xzf jdk17.tar.gz && mv jdk-17* jdk17 && rm jdk17.tar.gz
curl -sL -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
mkdir -p sdk/cmdline-tools && unzip -q cmdtools.zip -d sdk/cmdline-tools
mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest && rm cmdtools.zip
curl -sL -o gradle.zip "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
unzip -q gradle.zip && rm gradle.zip
export JAVA_HOME=~/Android/jdk17
yes | ./sdk/cmdline-tools/latest/bin/sdkmanager --licenses
```

Build:

```bash
cd companion-android
echo "sdk.dir=$HOME/Android/sdk" > local.properties
JAVA_HOME=~/Android/jdk17 ~/Android/gradle-8.9/bin/gradle assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Sideload it
(Taildrop, a file share, or a USB cable) — Android will ask once to allow
installs from that source, and may warn about an unknown developer since
it's debug-signed; that's expected for a self-built app.

## First run

Tap **Start logging** and grant location, nearby devices, and notifications.
Set the sync server URL (default `http://fagaceaeserver:5000` — point it at
wherever `api/flockyou.py` runs). The notification's **MARK CAMERA** action
logs a sighting from the lock screen.
