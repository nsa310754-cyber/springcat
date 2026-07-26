# Polyglot Runner

An Android app for writing code in **30+ programming languages** and running it
right from your phone. Pick a language, type some code, tap **Run**, and see the
output — stdout, stderr, compile errors and the exit code.

A **pinned action bar** stays at the bottom of the screen — **📁 File** (load a
file's contents via the system picker, no storage permission needed), **Run ▶**,
and **Clear** (empty the editor). Because it's pinned, Run stays reachable no
matter how long the code is, and it sits just above the keyboard while you type.
For YARA, an uploaded file goes into the *scan data* box instead of the code box.

Because a phone can't ship compilers for every language, execution is delegated
to the public [paiza.io](https://paiza.io) runner API (`api_key=guest`, no
account needed). The app is a thin, dependency-free client, so **an internet
connection is required** to run code.

Several languages run **entirely on-device, with no internet**:

- **HTML (offline preview)** — rendered in a live preview pane.
- **JSX / React (offline)** — transpiled by bundled Babel and rendered with
  bundled React.
- **JavaScript (offline)** — evaluated in the system WebView engine.
- **YARA (on-device scan)** — a built-in rule engine.

If you're offline and pick a remote language, the app says so and points you at
the offline options instead of failing with a network error.

## Install the APK

1. Download **`dist/PolyglotRunner-1.5-debug.apk`** onto your Android phone.
2. Open it. Android will ask you to allow installing from this source — accept.
3. Launch **Polyglot Runner**.

> This is a **debug-signed** build, meant for personal sideloading. It is not
> intended for the Play Store. Requires **Android 8.0 (API 26)** or newer.

## Supported languages

Python 3 & 2, JavaScript (Node), TypeScript, Java, C, C++, C#, Go, Rust, Ruby,
PHP, Kotlin, Swift, Scala, Perl, Haskell, Objective-C, Elixir, Erlang, Clojure,
F#, Visual Basic, D, Bash, R, Scheme, Common Lisp, COBOL, CoffeeScript — plus
on-device **HTML**, **JSX/React**, offline **JavaScript**, and **YARA** (see
below).

Each language ships with a "Hello, world" sample that loads when you select it
(your own edits are never overwritten).

## Offline languages

These work with the phone in airplane mode — no server round-trip:

| Language | Engine | Notes |
|---|---|---|
| **HTML (offline preview)** | WebView | Your HTML is rendered live in a **Preview** pane. Inline `<script>`/`<style>` work. |
| **JSX / React (offline)** | WebView + bundled Babel & React | JSX is transpiled by `@babel/standalone` and rendered with React 18 — all shipped in `assets/`, so it works with no network. Render into `document.getElementById('root')`. |
| **JavaScript (offline)** | system WebView (V8) | Browser-style JS. `console.log`/`warn`/`error` are captured; runtime **and** syntax errors are reported. No Node APIs (`require`, `process`, `fs`). |
| **YARA (on-device scan)** | built-in `YaraEngine` | See below. |

Everything else (Python, Go, Rust, …) runs remotely on paiza.io and needs
internet.

HTML and JSX render into a **Preview** pane instead of the text output.

## YARA (on-device scanning)

Selecting **YARA (on-device scan)** switches the app into a local mode — no
network call. Write [YARA](https://virustotal.github.io/yara/) rules in the
**YARA rules** box, put the bytes to scan in the **Data to scan** box, and tap
Run. The report lists which rules matched and how many times each string hit.

The bundled engine
([`YaraEngine.java`](app/src/main/java/com/springcat/polyglot/YaraEngine.java))
implements a practical **subset** of YARA:

- **Strings:** text (`"..."`), hex (`{ 4D 5A ?? [2-4] 90 }`) with `??`/nibble
  wildcards and `[n-m]` jumps, and regex (`/.../`).
- **Modifiers:** `nocase`, `wide`, `ascii`, `fullword`.
- **Conditions:** `and` / `or` / `not` / parentheses, `true` / `false`, `$a`,
  `all|any|N of them`, `all|any|N of ($a, $b*, $*)`, `#a <op> N`,
  `filesize <op> N` (with `KB` / `MB`), and references to other rules by name.

It is **not** full libyara: modules (`pe`, `math`, `hash`, …), string offsets
(`@a`, `at`, `in`), and PCRE features beyond `java.util.regex` are not supported.
Anything it can't parse is reported as an error rather than silently ignored.

## How it works

```
create job   ->  POST https://api.paiza.io/runners/create
poll result  ->  GET  https://api.paiza.io/runners/get_details?id=...
```

The app polls until the job status is `completed`, then formats the result. See
[`app/src/main/java/com/springcat/polyglot/MainActivity.java`](app/src/main/java/com/springcat/polyglot/MainActivity.java).

Permissions requested: `INTERNET` (remote languages) and `ACCESS_NETWORK_STATE`
(to detect being offline). The offline languages use neither.

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
    java/.../MainActivity.java     UI + networking + offline JS/HTML/JSX (WebView)
    java/.../YaraEngine.java       on-device YARA rule engine (pure Java)
    assets/                        react, react-dom, babel (bundled for offline JSX)
    res/                           launcher icon (adaptive) + colors
build.gradle · settings.gradle · gradle.properties
dist/PolyglotRunner-1.5-debug.apk  prebuilt, ready to sideload
```
