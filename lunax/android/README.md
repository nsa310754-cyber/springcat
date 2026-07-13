# Lunax for Android

A thin Android **WebView** wrapper that ships the Lunax virtual environment as
an installable app. Everything (the OS, console, LunaBASIC runtime, archive
tools) is bundled offline in `assets/` — the app needs **no network and no
permissions**.

## Prebuilt APK

[`lunax.apk`](./lunax.apk) — signed, ready to sideload.

1. Copy it to your Android phone.
2. Open it; allow "Install unknown apps" if prompted.
3. Launch **Lunax**.

- Package: `com.lunax.app` · version 1.0
- minSdk 26 (Android 8.0+) · targetSdk 34
- Signed with a debug key (fine for personal sideloading, not Play Store)

## What the wrapper does

- Hosts the web app from `file:///android_asset/index.html`
- Enables JavaScript + DOM storage (the virtual filesystem uses `localStorage`)
- Routes the in-page **Import** button to the Android file picker, so you can
  bring real `.exe` / `.tar.gz` files into Lunax
- Back button navigates WebView history, then exits

## Building it yourself

Needs a JDK and the Android SDK (`build-tools`, a `platform`). No Gradle — the
script drives `aapt2`, `d8`, `zipalign` and `apksigner` directly.

```bash
export ANDROID_HOME=/path/to/android-sdk
./build.sh
# → build/lunax.apk
```

| file | purpose |
|------|---------|
| `AndroidManifest.xml` | package, launcher activity, icon, SDK levels |
| `src/com/lunax/app/MainActivity.java` | the WebView host + file-chooser bridge |
| `res/…` | app name + adaptive moon launcher icon |
| `build.sh` | assemble & sign the APK from raw SDK tools |
