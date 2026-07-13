# SpringCat 🐱

**Code · Sign · Ship** — an Android Studio project for a mobile developer
toolkit that runs entirely on-device.

SpringCat is a native Android app (Jetpack Compose) that lets you:

- ✍️ **Write code in many programming languages** — a multi-language editor
  with syntax highlighting, automatic language detection, on-device syntax
  checking, and **import from any file** on the device.
- ▶️ **Run HTML & React/JSX/JS** on-device in a WebView, with a live
  console/error panel (React + Babel are bundled and run offline).
- 🔑 **Generate keystores & certificates** — create Android-compatible signing
  keystores (`PKCS12`/`JKS`) and self-signed X.509 certificates, just like
  desktop `keytool`.
- ✅ **Sign APKs and AABs** — sign packages with the official signing schemes
  (APK v1/v2/v3 for `.apk`, JAR signing for `.aab`), then export the result.

Everything runs offline. No account, no server, no storage permissions —
exports go through the system file picker (Storage Access Framework).

---

## APK size

The shipped release APK is a few megabytes — **far under the 500 MB ceiling.**
Release builds enable R8 shrinking and resource shrinking:

```kotlin
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
    }
}
```

The heaviest dependencies (BouncyCastle + apksig) add only a couple of MB.

---

## Supported languages

Kotlin, Java, Python, JavaScript, TypeScript, C, C++, C#, Go, Rust, Swift,
Ruby, PHP, Dart, Gradle (Kotlin DSL), HTML, CSS, XML, JSON, YAML, Markdown,
Shell, SQL — with lexical syntax highlighting (keywords, strings, numbers,
comments) that works across all of them. See
[`Language.kt`](app/src/main/java/com/springcat/ide/core/lang/Language.kt).

---

## How the crypto works

| Feature | Backed by | File |
| --- | --- | --- |
| Keystore + self-signed cert | BouncyCastle (`bcpkix`) | [`KeystoreManager.kt`](app/src/main/java/com/springcat/ide/core/keystore/KeystoreManager.kt) |
| APK v1/v2/v3 signing | Google `apksig` | [`ApkSigner.kt`](app/src/main/java/com/springcat/ide/core/signing/ApkSigner.kt) |
| AAB (JAR) signing | Hand-rolled JAR signer + BC CMS | [`JarSigner.kt`](app/src/main/java/com/springcat/ide/core/signing/JarSigner.kt) |

The JAR signer's byte-level output was validated against the JDK `jarsigner`
verifier (`jar verified`) — the only remaining notice is the expected
"self-signed certificate" warning, which is normal and accepted for Android
app signing keys.

---

## Building

Requirements: **Android Studio Ladybug (or newer)**, **JDK 17**,
**Android SDK 34**.

```bash
git clone <this-repo>
cd springcat
./gradlew :app:assembleRelease   # or open the project in Android Studio
```

The Gradle wrapper (`8.7`) and version catalog are committed, so the project
builds on a clean checkout. Toolchain versions:

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.0 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| Jetpack Compose | BOM 2024.06 |

---

## Scope

SpringCat **edits source in many languages** and **produces the signing
artifacts** (keystores, certificates) and **signs finished packages**
(APK/AAB). Compiling arbitrary source code into an APK on-device requires the
full `aapt2` + `d8` toolchain and is intentionally out of scope for a
lightweight, sub-500 MB app — the recommended flow is to build your `.apk` /
`.aab` (e.g. from a CI or desktop build) and use SpringCat to generate the
signing key and sign it anywhere.

---

## Project layout

```
app/src/main/java/com/springcat/ide/
├── MainActivity.kt              # Compose entry point + navigation
├── core/
│   ├── lang/                    # Language definitions + syntax highlighter
│   ├── keystore/                # Keystore & X.509 certificate generation
│   ├── signing/                 # APK (apksig) and AAB (JAR) signers
│   └── file/                    # Private workspace for source files
└── ui/
    ├── theme/                   # Material 3 theme
    └── screen/                  # Home, Editor, Keystore, Signer screens
```
