# Polyglot Runner

An Android app for writing code in **30+ programming languages** and running it
right from your phone. Pick a language, type some code, tap **Run**, and see the
output — stdout, stderr, compile errors and the exit code.

A **pinned action bar** stays at the bottom of the screen — **📁 File** (load a
source file into the editor via the system picker, no storage permission needed),
**Run ▶**, and **Clear** (empty the editor). Because it's pinned, Run stays
reachable no matter how long the code is, and it sits just above the keyboard
while you type. For YARA, an uploaded file goes into the *scan data* box instead
of the code box.

You can also **load code by typing a path** — the *Load code by path* field takes
something like `/sdcard/Download/x.py` or `/Android/0/…` and reads that file into
the editor. It tries the path as typed plus common storage roots
(`/storage/emulated/0/…`, `/sdcard/…`), and `/Android/0/…` and `/0/…` are treated
as the primary (user-0) storage. On Android 11+ reading arbitrary paths needs the
one-time **All files access** permission; the app opens that settings screen for
you, then you tap **Load** again.

**Results open in a popup** front-and-centre, so you never have to scroll down
to see them: text output (and YARA reports) appear in a scrollable dialog with a
**Copy** button, and HTML / JSX render in a large preview dialog. The last result
also stays in the log area at the bottom.

A **format detector** watches both sides of a run:

- **Input:** if your code looks like it reads standard input (`input()`, `scanf`,
  `Scanner`, `gets`, `readLine`, …) but the input box is empty, Run asks whether
  to fill it in first or go ahead.
- **Output:** if the result looks like a graphic — **SVG**, an **HTML** document
  or fragment, or a `data:image/…;base64` **image** — it's rendered in a viewer
  popup (with a **Raw text** button) instead of being dumped as text.

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

1. Download **`dist/PolyglotRunner-2.0-debug.apk`** onto your Android phone.
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

## A–Z languages (one letter each)

A second picker offers one language per letter, **A through Z**. It applies your
pick to the active language and then resets, so it works like a quick menu; the
**▶ Active** line shows what Run will use.

Letters backed by a runner execute for real (B Bash, C, D, E Elixir, F F#,
G Go, H Haskell, J Java, K Kotlin, L Lisp, O Objective-C, P Python, R, S Swift,
T TypeScript, V Visual Basic). The remaining single-letter/esoteric languages
that have no runner here (A+ , I Io, M/MUMPS, N Nim, Q/kdb+, U Unlambda,
W Whitespace, XSLT, Y Yorick, Z notation) are **info-only**: tapping Run pops a
short description instead of executing.

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

HTML and JSX render into a **preview popup**; other languages show their text
output in a result popup.

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

Permissions requested: `INTERNET` (remote languages), `ACCESS_NETWORK_STATE`
(to detect being offline), and storage read (`READ_EXTERNAL_STORAGE` ≤ API 32 /
`MANAGE_EXTERNAL_STORAGE` on API 30+) — only used when you load code by typing a
path. The 📁 picker and the offline languages need none of these.

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
dist/PolyglotRunner-2.0-debug.apk  prebuilt, ready to sideload
```
