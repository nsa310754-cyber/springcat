# SpringCat File Store

A lightweight Android app for saving files — **stays light even with multi-GB files**.

The APK is at [`dist/springcat-file-store.apk`](dist/springcat-file-store.apk).

## Why it stays light with large files

| Concern | How it's handled |
| --- | --- |
| Loading a huge file into RAM | Never happens. Files are copied in **64 KB streamed chunks**, so memory use is constant regardless of file size ([`FileRepository.importFile`](app/src/main/java/com/springcat/filestore/FileRepository.kt)). |
| A heavy file list | The list shows **metadata only** (name / size / date) read straight from the filesystem — the contents are never touched while browsing. |
| Scrolling many items | `RecyclerView` recycles rows, so a store with thousands of files scrolls smoothly. |
| A database that grows | There is **no database**. The store directory *is* the source of truth; listing it is instant. |

## Features

- Tap **+** to save any file into the app (via the system file picker).
- Live progress bar while a large file is copied.
- Open, share, or delete saved files.
- Storage summary (file count + total size) in the toolbar.
- No runtime permissions required — files live in app-specific storage.

## Install the APK

1. Copy `dist/springcat-file-store.apk` to your Android phone.
2. Open it and allow "install from unknown sources" if prompted.
3. Launch **SpringCat File Store**.

This is a **debug-signed** build — fine for personal/side-loading use. For Play Store
distribution you'd sign a release build with your own keystore.

## Build from source

Requires the Android SDK (platform 34, build-tools 34.0.0) and JDK 17+.

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Tech

- Kotlin, minSdk 24 (Android 7.0), targetSdk 34 (Android 14)
- AndroidX + Material 3, ViewBinding
- Storage Access Framework (`ACTION_OPEN_DOCUMENT`) + `FileProvider` for opening/sharing
