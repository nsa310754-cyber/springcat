# 新しいアプリを作るときのメモ / チェックリスト

このリポジトリには複数のアプリ（Block Destroy / OshiLog / codeconv …）が同居しています。
新しいアプリを追加するときの約束ごとをここにまとめます。

## ⭐ アイコンは必ずアプリごとに別々のものにする

**新しいアプリを作る際は、アイコン（launcher icon）を必ずそのアプリ専用の別デザインにすること。**
他アプリのアイコン（`mipmap-*/ic_launcher*.png` や `ic_launcher_background` など）を
そのまま流用しない。ホーム画面やストアで別アプリと見分けがつかなくなり、取り違えの原因になる。

- 既存アプリの `res/mipmap-*` をコピーして使い回さない（背景画像・前景・丸アイコンすべて）。
- アダプティブアイコン（`mipmap-anydpi-v26/ic_launcher.xml`）の前景/背景も、
  そのアプリ専用のレイヤーを指すこと。
- レガシー（API<26）用の `ic_launcher.png` / `ic_launcher_round.png` も各密度で用意する。
- ストア提出用の 512px アイコンも用意しておくとよい（例: `dist/playstore/`）。

> 参考実装: `android-converter/icon/` にアイコン生成スクリプトがある。
> SVG を組み立てて Chromium で 512px マスターを描き、`pngtool.js`（Node 標準 zlib だけの
> 自前 PNG リサイザ）で各密度へ縮小している。他アプリでも同じ方式で専用アイコンを作れる。

## その他の約束

- **パッケージ名（applicationId）もアプリごとに別にする**（例: `site.ragdollp.codeconv`）。
  他アプリと同じにすると端末上で共存できない／上書きインストールになる。
- **署名鍵はアプリごとに用意**し、公開配布時は使い捨て鍵から各自の本番鍵に差し替える。
- ビルド生成物（`build/`・`.gradle/`・`local.properties`）は各プロジェクトの `.gitignore` で除外する。
- 署名済み APK は `dist/` に置く（このリポジトリの慣習）。
