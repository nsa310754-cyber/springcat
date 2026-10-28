# BinRunner — 実行ファイル自動判定ランチャー (Android)

`.exe` や `.tar.gz` などを **拡張子ではなくファイルの中身(マジックナンバー)で自動判定**し、
「このまま直接実行できるか / 変換が必要か / 無理か」を振り分ける Android アプリです。

ユーザー要望のロジックをそのまま実装しています:

> **Bionic を確認したら実行。そうでなく変換できるなら変換して実行。**

## 正直な前提(ここが一番大事)

このアプリ単体で **あらゆるファイルが動くわけではありません。** 技術的な事実は次のとおりです。

- スマホの CPU はほぼ **ARM64**。`.exe` は **x86/x64 の Windows 命令**。
  **CPU が解釈する命令セットはハードウェアで固定**で、**root(スーパーユーザー)でも変更できません。**
  → 違う CPU の命令を動かすには、**ソフトでの命令変換(box64 / box86 / QEMU / Wine)** が必須です。
- Android は Linux カーネルですが libc は **Bionic**。通常 Linux(glibc/musl)のバイナリはそのままは動きません。
  → 同じ ARM64 でも **proot で通常 Linux のルートFS** を用意する必要があります(CPU 変換は不要)。
- Android 10 以降は、アプリ保存領域のファイルを `exec` することが **SELinux(W^X)で禁止**されています。
  → **直接実行には root(su)か、Termux のような実行可能領域が必要**です。アプリは root があれば `su` 経由で実行します。

つまり **実際の実行は既存エンジン(box64 / proot / Wine / Termux)に委譲**する設計です。
Wine+box64 を一から作るのは個人で数年規模(それが [Winlator](https://github.com/brunodev85/winlator))なので、
このアプリは **「正しく種類を見分けて、適切な実行経路へ渡す」司令塔**に徹しています。

## 何が「完全に動く」部分か

拡張子に頼らない判定ロジックは **純 Java で完全動作**します:

- マジックナンバー判定: ELF / PE(MZ) / Mach-O / gzip / zip / tar / `#!` スクリプト
- **ELF ヘッダ解析**: 32/64bit、エンディアン、`e_type`、`e_machine`(ARM64 / ARM / x86-64 / x86 / RISC-V ほか)
- **リンカ判定**: `PT_INTERP` を読み、`/system/bin/linker*`→**Bionic**、`ld-linux`/`ld-musl`→**glibc/musl**、無し→**静的**
- gzip は**アプリ内で展開**して中身(tar / ELF)を再判定

## 判定 → 振り分け

| 中身 | 判定 | 動作 |
|---|---|---|
| ELF・同じ CPU・Bionic/静的 | ✅ 直接実行 | そのまま `exec`(10+ は root 経由) |
| ELF・同じ CPU・glibc/musl | 🔁 変換(proot) | proot の Linux 環境で実行 |
| ELF・違う CPU | 🔁 変換(命令変換) | box64 / box86 / qemu-user で実行 |
| Windows PE(.exe) | 🍷 変換(Wine) | Wine + box64/box86 で実行 |
| gzip / zip / tar | 📦 書庫 | 展開して中身を再判定 |
| Mach-O | ⛔ 非対応 | Android で動かす手段なし |

対応エンジンが端末(PATH / Termux の `…/usr/bin`)にあれば、そのまま呼び出して実行します。
無ければ入手先を案内します。

## ビルド方法(Android Studio / Linux 版でOK)

SDK さえあれば **Android Studio にこの `binrunner/` フォルダを開くだけ**でビルドできます。

1. [Android Studio(Linux 版)](https://developer.android.com/studio) をダウンロード・起動
2. **Open** → このリポジトリの `binrunner/` を選ぶ
3. 初回に SDK(platform android-35 / build-tools 35)を自動インストール
4. 緑の ▶ でビルド & 実機/エミュレータへインストール

コマンドラインの場合(JDK 17・Android SDK が必要):

```bash
cd binrunner
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または ANDROID_HOME を設定
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk
```

- パッケージ名: `site.ragdollp.binrunner`
- minSdk 26(Android 8.0)/ targetSdk 34 / compileSdk 35 / Java 17
- AGP 8.6.0 / Gradle 8.9(wrapper 同梱)
- ネイティブコード無し・権限要求なし(ファイルは SAF で選ぶ)

## 使い方

1. 「① ファイルを選ぶ」でファイルを選択(拡張子は何でもよい)
2. 中身を見て種別・アーキ・libc を表示し、実行経路を提案
3. 「② 実行 / 変換実行」。ELF は「root(su)で実行」も選べる
4. gzip なら「展開して再判定」で中身を取り出して判定

## 構成

```
binrunner/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/                 # Gradle 8.9 wrapper
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/site/ragdollp/binrunner/
        │   ├── MainActivity.java      # 画面・ファイル選択・gzip展開・振り分け
        │   ├── BinaryAnalyzer.java    # マジックナンバー & ELF ヘッダ解析(純Java)
        │   ├── DecisionEngine.java    # Bionic確認→直接 / それ以外→変換、の判断
        │   └── Executor.java          # 実際の起動(通常/su/変換エンジン)
        └── res/                       # アイコン・テーマ・文言
```
