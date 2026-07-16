# SpringCat AutoClicker

A **root-free auto-clicker (auto tapper) for Amazon Fire tablets** and any
Android 7.0+ device. Built with **no Google Play Services**, so it runs on
Fire OS 6, 7 and 8, where the Play Store and GMS are not available.

## How it works

An auto-clicker needs a way to inject taps into other apps. There are two ways
to do that on Android:

| Approach | Needs root? | Used here |
|----------|-------------|-----------|
| `input tap` via a root shell | Yes | ❌ |
| **AccessibilityService `dispatchGesture()`** (API 24+) | No | ✅ |

This app uses the second approach. When you turn the accessibility service on,
it can dispatch tap gestures at a point you choose — without root and without
any special "draw over other apps" permission.

The floating controls (the panel and the blue target) are drawn as a
**`TYPE_ACCESSIBILITY_OVERLAY`** window that belongs to the accessibility
service itself. That window type needs no overlay permission, which is why it
behaves consistently on Fire OS where the overlay-permission screen is often
missing.

## Features

- Root-free automatic tapping at a fixed point
- Draggable on-screen target — the taps pass straight through it while running
- Adjustable interval (50 ms – 10 s) with − / + buttons
- Draggable control panel
- Interval and target position are remembered
- No internet permission, no ads, no trackers, no Google dependencies

## Install on a Fire tablet

1. Download `app-debug.apk` (see **Getting the APK** below).
2. On the tablet: **Settings ▸ Security & Privacy ▸ Apps from Unknown Sources**
   and allow your browser or file manager to install apps.
3. Open the APK to install it.
4. Launch **SpringCat AutoClicker** and tap **Enable accessibility service**.
5. In the Accessibility list turn **SpringCat AutoClicker** ON.
6. Go back to the app — a floating panel and a blue target appear.
7. Drag the target where you want the taps, set the interval, press **START**.

## Getting the APK

This repo builds the APK automatically with GitHub Actions — no local Android
SDK needed:

1. Push to GitHub (any branch) or open the **Actions** tab and run
   **Build APK** manually (*Run workflow*).
2. When the run finishes, open it and download the
   **SpringCat-AutoClicker-debug** artifact.
3. Unzip it to get `app-debug.apk`.

### Building locally (optional)

With the Android SDK installed:

```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Compatibility

- **Minimum:** Android 7.0 / API 24 → **Fire OS 6** (2016+ Fire tablets) and newer.
- Fire OS 5 (Android 5.1) is **not** supported, because `dispatchGesture()`
  requires API 24.

## Notes

- The app only performs taps you configure; it never reads screen content
  (`canRetrieveWindowContent="false"`).
- If the floating controls do not appear, toggle the accessibility service
  OFF and then ON again.
