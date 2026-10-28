# BionicDesktop

スマホを「PC的に使う」ためのスタンドアロン Android アプリ(Termux 不使用)。
**権限/root はダイアログで付与**、**コマンドプロンプト**、**Bionic ネイティブ ELF 実行**、
**ダウンロード対応ブラウザ**、**glibc ランタイム(フレーム同梱＋初回DL)** を備える雛形。

> ⚠️ 個人利用向け。Google Play には出せない設計(targetSdk 28・広範な権限)。
> `.exe`(Windows実行ファイル)は対象外。ここでいう「exe」は **Bionic/Linux 向けネイティブ ELF**。

---

## 1. なぜ動くのか(調査で裏取りした要点)

- **Android の libc は Bionic**。NDK で Bionic 向けにビルドした ELF はそのまま実行できる。
- **targetSdk 28 がキモ**: 29 以上だと W^X / SELinux で `filesDir` のファイルを `exec()` 禁止。
  Termux も長年この回避のため targetSdk 28 を維持していた(出典下記)。
- **glibc バイナリ**は Bionic 上では直接動かない(ローダ `ld-linux-*.so.1` と glibc が無い)。
  → **ローダ経由起動**(`ld.so --library-path <libs> ./prog`)か **patchelf 書き換え**で動かす。
  Termux も glibc 本体は自作せず「配送(APT/rootfs DL)＋起動補助(grun)」だけ。本アプリは
  Termux を使わず同じ原理を自前で行う。root は不要(単純なバイナリの範囲)。

出典:
- Termux and Android 10 — <https://github.com/termux/termux-packages/wiki/Termux-and-Android-10>
- About glibc runner (grun) — <https://github.com/termux-pacman/glibc-packages/wiki/About-glibc-runner-%28grun%29>
- Running native glibc binaries without proot — <https://dev.to/opassoca/running-native-glibc-debian-binaries-on-android-15-without-proot-1hmo>

---

## 2. ビルド

1. このフォルダを **Android Studio** で開く(Gradle wrapper の jar は Studio が生成)。
   - CLI のみの場合は `gradle wrapper --gradle-version 8.2` を一度実行してから `./gradlew assembleDebug`。
2. `app/build/outputs/apk/debug/app-debug.apk` が生成物。
3. 端末に転送してインストール(「不明なアプリ」を許可)。

---

## 3. 使い方

| ボタン | 動作 |
|---|---|
| ① 権限をまとめて許可 | カメラ/マイク/位置/ストレージ/通知を**ダイアログ**で要求 |
| ② 特別なアクセスを許可 | 全ファイル/重ね表示/不明アプリ導入/電池最適化を**設定トグル**へ誘導 |
| ③ root を要求 | `su` を叩く → Magisk 等が「許可しますか?」を表示。`WRITE_SECURE_SETTINGS` を `pm grant` |
| コマンドプロンプト | `/system/bin/sh` 実行。`root <cmd>`=su経由、`glibc <prog>`=ローダ経由 |
| ブラウザ | WebView + DownloadManager。Android Studio や `.crx` 等を DL |
| glibc 取得 | `GlibcRuntime` の URL から本体を初回 DL → 展開 |

### Bionic ELF を実行する
1. NDK で `aarch64-linux-android` 向けにビルド。
2. `filesDir/bin/`(端末では `/data/data/com.example.bionicdesktop/files/bin/`)へ置く。
3. ターミナルで `./bin/myprog` 実行(targetSdk 28 なので filesDir から実行可)。
   - **恒久同梱**したいものは `app/src/main/jniLibs/<abi>/lib<名>.so` に入れる
     → `nativeLibraryDir` から常に実行可能。

### glibc を有効化する
- `MainActivity` の `btnSetupGlibc` の `url` を、自分で用意した配布先に差し替える
  (ld.so + libc 一式、または最小 rootfs tarball)。
- 展開に使う `tar`/`busybox`(Bionic ELF)を `jniLibs/<abi>/libtar.so` として同梱推奨。

---

## 3.5 権限と Device Admin の方針

- **権限はほぼ全グループを宣言**し、起動時にダイアログで一括要求する(連絡先/電話/SMS/カメラ/
  位置/センサー/Bluetooth 等)。不具合回避のため広めに宣言しているが、**使わない権限は端末設定で
  個別に拒否して問題ない**(アプリは宣言済み & 未許可のものだけを要求する)。
- **Device Admin(デバイス管理)は必須ではない**。調査結論:
  - Device Admin API は 2017 年に非推奨化。カメラ/キーガード/パスワード系ポリシーは
    Android 10 以降で無効化(例外送出)。現行の後継は **Device Owner / Profile Owner**。
  - 本アプリの用途(バイナリ実行・ターミナル・デスクトップ)には不要で、深いシステム制御は
    **root** で足りる。画面ロック/ワイプ/ログイン監視を使いたい時だけ ④ ボタンで有効化する。
  - 出典: [Device admin deprecation (Google)](https://developers.google.com/android/work/device-admin-deprecation)、
    [Device administration overview](https://developer.android.com/work/device-admin)

## 4. 正直な限界

- rootless の glibc 実行は**単純なバイナリ向け**。`/proc`・fakeroot 多用の複雑なものは
  **proot バイナリ同梱**か **root** が要る(将来対応)。
- **Android Studio の実行**は ARM64 glibc + X サーバ + 大量 RAM 前提。DL は容易だが実行は高負荷。
- `.crx` は **ダウンロードのみ**。拡張を動かすには拡張対応 Chromium 本体が別途必要。
- `WRITE_SECURE_SETTINGS` 等はダイアログ不可。root か `adb shell pm grant` が必要。

---

## 5. 構成

```
app/src/main/
├── AndroidManifest.xml          権限宣言 + 3画面 + FileProvider
├── java/com/example/bionicdesktop/
│   ├── MainActivity.kt          ランチャー(権限/root/各画面への導線)
│   ├── PermissionManager.kt     ダイアログ権限 + 特別アクセス誘導
│   ├── RootShell.kt             su 実行 / secure settings 付与
│   ├── NativeExecutor.kt        Bionic ELF 実行エンジン(filesDir/nativeLibDir)
│   ├── GlibcRuntime.kt          glibc フレーム + 初回DL + ローダ経由実行
│   ├── TerminalActivity.kt      コマンドプロンプト
│   └── BrowserActivity.kt       DL対応ブラウザ
└── res/…                        レイアウト等
```
