# Zip2Exe — ZIP を Windows 自己解凍 EXE に変換

アプリで ZIP を選ぶと、Info-ZIP の `unzipsfx.exe` を頭に連結した自己解凍形式 (SFX) の
Windows PE 実行可能ファイル (.exe) を書き出します。この EXE を Windows 上でダブルクリックすると
同階層に ZIP の中身が展開されます。

## 仕組み
```
出力.exe = [unzipsfx.exe バイナリ (~100KB)] + [元の ZIP の全バイト]
```
unzipsfx は自身を開き、追記された ZIP を検出して展開する実装になっています。

## ライセンス
- 同梱の `unzipsfx.exe` は Info-ZIP (BSD 系) のソース (unzip 6.0) を mingw-w64 で
  32bit Windows 向けクロスコンパイルしたものです。
- Info-ZIP ライセンスの全文は `app/src/main/assets/unzipsfx-LICENSE.txt` に同梱されています。

## 制限
- 出力 EXE は Windows 32bit PE です。Windows 10/11 でも動きますが SmartScreen 警告が出ます(署名なし)。
- Android/Linux/macOS では実行できません(フォーマットが PE)。
- パスワード付き ZIP は unzipsfx が対応していないため展開できません。
- Mac/Linux ではこの EXE を `unzip` で開いても ZIP の中身を取り出せます(先頭の実行バイナリ部を無視するため)。

## ビルド
```bash
cd android-zip2exe
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```
