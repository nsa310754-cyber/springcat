# springcat — Android app

A small native Android app (Java) that displays the springcat Node server's
**start command** and **URL**, each with a one-tap **copy** button.

The app does not run Node itself — it hands you the command to copy and paste
into a terminal such as [Termux](https://termux.dev/) to start the server.

## Screens / features

- Start command (`npm start`) shown in a monospace box with a **コピー** button
- Server URL (`http://localhost:3000`) with its own copy button
- Copy shows a "コピーしました" toast

## Build

Requirements:

- JDK 17+
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`
- Set the SDK location via `local.properties` (`sdk.dir=/path/to/android-sdk`)
  or the `ANDROID_HOME` environment variable

Then:

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The included Gradle wrapper (`./gradlew`) downloads Gradle 8.14.3 on first run.

## Project layout

```
android/
├── app/
│   ├── build.gradle              # app module config (minSdk 26, targetSdk 34)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/springcat/app/MainActivity.java
│       └── res/                  # layout, strings, colors, theme, launcher icon
├── build.gradle                  # root build (AGP 8.7.3)
├── settings.gradle
└── gradlew / gradlew.bat         # Gradle wrapper
```

## Config

- Application ID: `com.springcat.app`
- minSdk 26 (Android 8.0), targetSdk 34
- versionName `1.0.0`

To change the displayed command or URL, edit
`app/src/main/res/values/strings.xml` (`start_command`, `server_url`).
