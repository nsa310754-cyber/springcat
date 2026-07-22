# Polyglot Runner

An Android app for writing code in **30+ programming languages** and running it
right from your phone. Pick a language, type some code, tap **Run**, and see the
output — stdout, stderr, compile errors and the exit code.

Because a phone can't ship compilers for every language, execution is delegated
to the public [paiza.io](https://paiza.io) runner API (`api_key=guest`, no
account needed). The app is a thin, dependency-free client, so **an internet
connection is required** to run code.

## Install the APK

1. Download **`dist/PolyglotRunner-1.0-debug.apk`** onto your Android phone.
2. Open it. Android will ask you to allow installing from this source — accept.
3. Launch **Polyglot Runner**.

> This is a **debug-signed** build, meant for personal sideloading. It is not
> intended for the Play Store. Requires **Android 8.0 (API 26)** or newer.

## Supported languages

Python 3 & 2, JavaScript (Node), TypeScript, Java, C, C++, C#, Go, Rust, Ruby,
PHP, Kotlin, Swift, Scala, Perl, Haskell, Objective-C, Elixir, Erlang, Clojure,
F#, Visual Basic, D, Bash, R, Scheme, Common Lisp, COBOL, CoffeeScript.

Each language ships with a "Hello, world" sample that loads when you select it
(your own edits are never overwritten).

## How it works

```
create job   ->  POST https://api.paiza.io/runners/create
poll result  ->  GET  https://api.paiza.io/runners/get_details?id=...
```

The app polls until the job status is `completed`, then formats the result. See
[`app/src/main/java/com/springcat/polyglot/MainActivity.java`](app/src/main/java/com/springcat/polyglot/MainActivity.java).

The only permission requested is `INTERNET`.

## Build from source

Requirements: JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0).

```bash
# point Gradle at your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

gradle :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

The project has **no third-party dependencies** — plain Android framework APIs
plus `org.json`, so the build is small and fast.

## Project layout

```
app/
  build.gradle                     module config (minSdk 26, targetSdk 34)
  src/main/
    AndroidManifest.xml            INTERNET permission, launcher activity
    java/.../MainActivity.java     UI (built in code) + networking
    res/                           launcher icon (adaptive) + colors
build.gradle · settings.gradle · gradle.properties
dist/PolyglotRunner-1.0-debug.apk  prebuilt, ready to sideload
```
