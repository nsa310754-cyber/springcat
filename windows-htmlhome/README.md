# HTMLホーム (HtmlHome.exe)

HTML ファイルを「アプリ」として登録し、スマホのホーム画面のように並べて使う Windows 用ランチャー。
Windows では APK を入れられないので、HTML をアップロードしてアプリとして表示する。

## 使い方

`HtmlHome.exe` をダブルクリックするだけ（インストール不要・1 ファイル）。
初回起動時にデスクトップへ「HTMLホーム」のショートカットを作る（消した場合は作り直さない。exe を移動した場合はリンク先を自動で直す）。

| 最初からあるアプリ | 役割 |
|---|---|
| New app | HTML を選んでアプリを追加。JS・CSS を一緒に選ぶと 1 つの HTML にまとめる。同じアプリ名は不可 |
| Delete | 削除モード。× の付いたアプリを押すとアンインストール |
| Setting | ①アプリ情報 ②ホーム画面 ③ストレージと通信使用量 ④通信速度 ⑤バックアップ |
| exit | 終了 |

最初からある 4 つは削除・アンインストールできない（画面側でも exe 側でも拒否する）。

- **長押しドラッグ**でアイコンを並べ替え。別のアプリの上に重ねるとフォルダーができる。
- **右クリック**で「開く / アプリ情報 / アンインストール」。
- アプリ情報: サイズ（アプリ本体・セーブデータ・合計）、アンインストール、セーブデータをなくす、SHA-256、ダウンロード日。
- アプリは同じウィンドウ内に表示され、上の「← ホーム」で戻る。

## 仕組み

- 画面は WebView2（Edge の部品。Windows 11 は標準搭載、Windows 10 もほぼ入っている）。
- アプリごとに別の仮想ホスト名 `https://<id>.htmlhome.example/` を割り当てるので、
  セーブデータ（localStorage / IndexedDB / Cookie）はアプリ同士で混ざらない。
- 通信使用量は、アプリがインターネットから受け取った応答のサイズを足したもの。
- データの保存先: `%LOCALAPPDATA%\HtmlHome\`（apps / apps.json / ui.json / WebView2）。
- バックアップ（.hhbak）は zip。アプリ本体・並び・localStorage のセーブデータが入る。

## 構成

```
launcher/index.html   ホーム画面・フォルダー・設定などの UI（exe に埋め込む）
src/Program.cs        起動、埋め込み DLL の読み込み、自己テスト
src/Store.cs          アプリの保存・削除・バックアップ（WebView2 非依存）
src/MainForm.cs       ウィンドウと WebView2、画面からの命令の処理
src/SpeedTest.cs      回線速度の測定
tests/                Playwright による UI テストとスクリプトのテスト
```

## ビルド（Linux）

```bash
sudo apt-get install mono-mcs mono-runtime libmono-system-windows-forms4.0-cil \
  libmono-system-web-extensions4.0-cil libmono-system-io-compression4.0-cil \
  libmono-system-io-compression-filesystem4.0-cil
./fetch-deps.sh      # WebView2 SDK の DLL を lib/ に取得
./build.sh           # dist/HtmlHome.exe を作り、データ処理の自己テストを実行
node tests/ui.test.mjs        # 画面のテスト（Playwright + Chromium）
node tests/scripts.test.mjs   # セーブデータ用スクリプトのテスト
```

`launcher/index.html` はブラウザで直接開いても動く（その場合はメモリ上の代役ホストで動作）。

## テスト範囲について

Linux 上で確認できているのは、画面の動作（代役ホスト）、データ処理（Mono 上の自己テスト）、
セーブデータ用スクリプト（Chromium）。exe を Windows で実際に起動しての確認はしていない。
