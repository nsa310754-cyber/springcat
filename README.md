# Keystore Decoder (Android)

An Android app that **decodes and inspects keystore files** — like
`keytool -list -v`, but on your phone. Pick a keystore, optionally type its
password, and see every alias, its certificates, validity, and the
**SHA-1 / SHA-256 / MD5 fingerprints** (the values Firebase and Google APIs
ask for).

## Features

- Supported formats:
  - **JKS** / `.keystore` (the classic Android signing/debug keystore)
  - **JCEKS**
  - **PKCS12** (`.p12` / `.pfx`) — the modern `keytool` default
  - **BKS**, **BCFKS** (BouncyCastle formats)
- Password support: enter the store password if the keystore is encrypted.
  Leave it blank for keystores without one.
  - For **JKS/JCEKS**, certificates are readable even without the password
    (they are stored unencrypted); when you supply a password the app
    verifies it against the keystore's integrity hash and tells you whether
    it is correct.
  - For **PKCS12/BKS/BCFKS**, the correct password is required to open the
    file at all — a wrong one is reported clearly.
- For each certificate it shows: Subject, Issuer, serial number, validity
  window (with an EXPIRED flag), version, signature algorithm, public-key
  type/size, and SHA-256 / SHA-1 / MD5 fingerprints.
- **Private key extraction**: for key entries, if you supply the correct
  key password, the app decrypts the private key and shows its algorithm,
  size and the key itself as **PKCS#8 PEM** (`-----BEGIN PRIVATE KEY-----`),
  ready to copy. ⚠️ This is secret material — the UI warns you not to share it.
- Every field (including the PEM) is **individually copyable**, plus a
  "Copy all" button for the full report.
- Everything runs **fully on-device** — no file ever leaves the phone.

## How the formats are handled

Android's built-in security providers cannot read Sun's proprietary **JKS**
format, so this app ships a small, dependency-free JKS/JCEKS reader
(`JksParser.kt`) that parses the certificate entries directly and verifies
the store password using JKS's SHA-1 integrity hash. JKS private keys are
protected by Sun's "JavaSoft JDKKeyProtector" algorithm, which no provider on
Android implements either, so `JksKeyProtector.kt` re-implements that
decryption (a faithful port of `sun.security.provider.KeyProtector`).
**PKCS12**, **BKS** and **BCFKS** (and their private keys) are read through a
bundled **BouncyCastle** provider.

## Getting the APK

### Option A — download from CI (no toolchain needed)
Every push runs the **Build APK** GitHub Actions workflow
(`.github/workflows/build-apk.yml`). Open the latest run under the repo's
**Actions** tab and download the `keystore-decoder-debug-apk` artifact.

### Option B — build locally
Requires the Android SDK (API 34, build-tools 34.0.0) and JDK 17.

```bash
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Install on a device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Tap **Select keystore file** and choose a `.jks` / `.keystore` / `.p12` /
   `.pfx` / `.bks` file.
2. If it's password-protected, type the password.
3. Tap **Decode**. Long-press the output to copy fingerprints.

## Project layout

```
app/src/main/java/com/example/keystoredecoder/
  MainActivity.kt      – file picker, password field, result screen
  KeystoreDecoder.kt   – routing + certificate description + fingerprints
  JksParser.kt         – pure-Kotlin JKS/JCEKS reader + password check
  ReportBuilder.kt     – formats the keytool-style text report
```

## Note

Use this only on keystores you own or are authorized to inspect.
