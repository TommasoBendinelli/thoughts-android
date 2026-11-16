# Fresh Linux Setup

This guide describes how to build and run Thoughts from a fresh Linux install.

## 1. Install System Packages

Install Java 17, Python, CMake, Git, and basic build tools.

Arch Linux:

```bash
sudo pacman -S jdk17-openjdk python python-requests git base-devel cmake
```

Debian/Ubuntu:

```bash
sudo apt install openjdk-17-jdk python3 python3-requests git build-essential cmake
```

## 2. Install Android SDK Components

Install Android Studio, then open:

`More Actions` > `SDK Manager`

Install these SDK components:

- Android SDK Platform `API 34`
- Android SDK Build-Tools
- Android SDK Platform-Tools
- Android SDK Command-line Tools
- NDK (Side by side)
- CMake

The default Android Studio SDK path on Linux is usually:

```text
/home/user/Android/Sdk
```

Use your actual username/path if different.

## 3. Configure `local.properties`

Create `local.properties` in the project root if it does not already exist:

```properties
sdk.dir=/home/user/Android/Sdk
AI=
AK=
AU=
```

`AI`, `AK`, and `AU` are crash-reporting values. Leave them empty for local debug builds.

## 4. Restore or Build Native Dependencies

Native `.so` files are intentionally excluded from Git. For a normal app build, restore the exact libraries shipped in the pinned official v3.0.0 universal APK:

```bash
python3 build_dependencies/bootstrap_release_libs.py
```

The command verifies the APK checksum, package, version, and complete library inventory before placing all four supported ABI sets under:

```text
PdfiumAndroid/src/main/libs
```

To use an already downloaded copy of the official universal APK:

```bash
python3 build_dependencies/bootstrap_release_libs.py --apk /path/to/mj-pdf-v3.0.0-universal-release.apk
```

Only rebuild PDFium and the JNI libraries from source when changing native code. Find the installed NDK version:

```bash
ls /home/user/Android/Sdk/ndk
```

Then run the dependency build script with `ANDROID_NDK` pointing to that NDK directory:

```bash
ANDROID_NDK=/home/user/Android/Sdk/ndk/30.0.14904198 python build_dependencies.py
```

Replace `30.0.14904198` with the NDK version installed on your machine. The script stages dependencies under `PdfiumAndroid/src/main/jni/lib` and produces package-ready libraries under `PdfiumAndroid/src/main/libs`.

## 5. Run it with Android Studio
At this point Android Studio should be able to run it with no issues.

## 6. Build the Debug APK

From the project root:

```bash
./gradlew :app:assembleDebug
```

The APK will be generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 7. Build the Release APKs

From the project root:

```bash
./gradlew :app:assembleRelease
```

This produces one APK per architecture plus a universal one under:

```text
app/build/outputs/apk/release/
```

The release APKs are unsigned. Sign each APK you distribute with your release keystore:

```bash
apksigner sign --ks your-keystore.jks --out mj-pdf.apk app-arm64-v8a-release-unsigned.apk
```

`apksigner` is in the SDK's `build-tools` directory.

## 8. Optional: Push for Agent Notes

Without any setup the app builds without push: no Firebase or Google Play services library is compiled in, agent notes arrive while Thoughts is open, and the "Instant notes" switch keeps a listener running in the background. To let your server push notes to the phone through Firebase Cloud Messaging, create a Firebase project with an Android app for each application ID you build (`org.tbend.thoughts`, and `org.tbend.thoughts.sandbox` for tests), download its `google-services.json` and keep it outside the repository:

```text
~/.config/thoughts/firebase/google-services.json
```

or pass another path with `-PthoughtsFirebaseConfig=/path/to/google-services.json`. With that file present the build includes push (Firebase Cloud Messaging and Google Play services); `-PthoughtsPush=off` leaves push out anyway and `-PthoughtsPush=on` puts it in even without the file (push then stays off at run time). The build prints one line per run saying whether push is on or off for each variant; it never prints the values. The server must advertise the `push_fcm_v1` capability.
