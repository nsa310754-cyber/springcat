# SpringCat 解凍・圧縮 🐱

たくさんの拡張子に対応した、Android 用の多形式 解凍／圧縮 アプリです。
ストリーミング処理でメモリを使いすぎないため、**5GB 級の大きなアーカイブでも軽快**に扱えます。

## 対応形式

### 解凍（展開）

| 種類 | 拡張子 |
|------|--------|
| アーカイブ | `.zip` `.7z` `.rar` / RAR5 `.tar` `.cpio` `.ar` `.arj` |
| 単体圧縮 | `.gz` `.bz2` `.xz` `.lzma` `.zst`(Zstandard) `.br`(Brotli) `.lz4` `.sz`(Snappy) `.Z` |
| 複合形式 | `.tar.gz` / `.tgz` `.tar.bz2` / `.tbz2` `.tar.xz` / `.txz` `.tar.zst` など |
| ディスクイメージ | `.iso` `.img`（ISO9660 / Joliet / Rock Ridge） |
| パッケージ | `.rpm`（cpioペイロードを展開） `.deb`（ar内の各メンバーを展開） |
| ZIP系コンテナ | `.jar` `.war` `.ear` `.epub`（実体がZIPのもの） |

> **未対応**（保守された純Javaライブラリが存在しないため）: `.sit` / `.sitx`（StuffIt）, `.cab`, `.ace`, `.dmg`

### 圧縮（作成）

`.zip` · `.7z` · `.tar.gz` · `.tar.xz` · `.tar.zst` · `.tar.bz2`
（複数ファイル選択、またはフォルダ丸ごとの圧縮に対応）

すべて **Pure-Java 実装**（Apache Commons Compress / tukaani-xz / aircompressor / brotli-dec / junrar）を
採用しているため、ネイティブライブラリの ABI 依存がなく、どの端末でも同じように動作します。

## 特長

- **大容量対応** — アーカイブをメモリに丸ごと読み込まず、1MB バッファでストリーミング展開。
  5GB のファイルでも RAM を圧迫しません。
- **7z / RAR も対応** — ランダムアクセスが必要な 7z / RAR は、`FileChannel` 経由でシークして読み込み、
  一時ファイルへの全体コピーを行いません。
- **最新ストレージ準拠** — Storage Access Framework (SAF) を使用。ストレージ全体への権限は不要で、
  ユーザーが選んだファイル／フォルダにだけアクセスします。
- **バックグラウンド処理＋進捗表示** — UI をブロックせず、進捗バーと展開件数を表示。中止も可能。
- **パスワード付きアーカイブ対応** — 解凍画面のパスワード欄に入力すると、暗号化された
  ZIP（AES / ZipCrypto）・7z（AES-256）・RAR を解凍できます。空欄なら通常どおり動作します。
- **Zip Slip 対策** — `../` を含む不正なパスは展開時に拒否します。

## 使い方

画面上部の **「解凍 / 圧縮」** で操作を切り替えます。

### 解凍

1. 「① アーカイブを選択」で解凍したいファイルを選ぶ
2. 「② 出力先フォルダを選択」で展開先フォルダを選ぶ
3. 暗号化アーカイブの場合はパスワード欄に入力（任意）
4. 「③ 解凍開始」を押す

### 圧縮

1. 「① ファイルを選択（複数可）」またはフォルダを選ぶ
2. 「② 圧縮形式」で出力形式を選ぶ
3. 「③ 圧縮開始」を押し、保存先とファイル名を決める

## ビルド方法

Android SDK (API 34) が必要です。

```bash
# デバッグ版
./gradlew assembleDebug
# 生成物: app/build/outputs/apk/debug/app-debug.apk

# リリース版（署名付き）
# ルートに keystore.properties と .jks を用意してください（下記参照）
./gradlew assembleRelease
# 生成物: app/build/outputs/apk/release/app-release.apk
```

### 署名設定（リリースビルド）

`keystore.properties` はリポジトリに含まれません。以下の形式で用意してください。

```properties
keystore.password=あなたのパスワード
key.alias=エイリアス名
key.password=キーのパスワード
store.file=your-release.jks
```

キーストアが無い場合、リリースビルドは未署名になります（デバッグビルドは常に可能）。

## 動作環境

- Android 7.0 (API 24) 以上
- `minSdk 24` / `targetSdk 34`
