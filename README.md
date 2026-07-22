# springcat

A minimal, dependency-free Node.js HTTP server built with the built-in `http` module.

## Requirements

- Node.js >= 18

## Run

```bash
npm start
```

The server listens on `http://0.0.0.0:3000` by default. Override with env vars:

```bash
PORT=8080 HOST=127.0.0.1 npm start
```

For development with auto-reload:

```bash
npm run dev
```

## Endpoints

| Method | Path        | Description                          |
| ------ | ----------- | ------------------------------------ |
| GET    | `/`         | Welcome message and endpoint listing |
| GET    | `/health`   | Health check with uptime             |
| GET    | `/api/time` | Current server time (ISO 8601)       |

Unknown routes return `404` with a JSON error body.

## Test

```bash
npm test
```

## Android app (APK)

An Android companion app lives in [`android/`](android/). It shows the
server's start command and URL and lets you copy them to the clipboard with
one tap (e.g. to paste into Termux and launch the server on-device).

A prebuilt debug APK is available at
[`dist/springcat-1.0.0-debug.apk`](dist/springcat-1.0.0-debug.apk).

Build it yourself:

```bash
cd android
./gradlew assembleDebug   # requires the Android SDK (build-tools 34, platform 34)
# output: app/build/outputs/apk/debug/app-debug.apk
```

See [`android/README.md`](android/README.md) for details.
