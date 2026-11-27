# Windows Script File (.wsf) の確認用

Bionic Runner で Windows Script File (.wsf) を動かす確認用のファイルです。どちらも害はありません。

| ファイル | 中身 |
|---|---|
| `hello.wsf` | job「js」(JScript) と job「vbs」(VBScript) を持つ。文字を出すだけ。`<object>` (Scripting.FileSystemObject) と `<resource>` (getResource) も使う |
| `redscreen-test.wsf` | 赤い警告画面の確認用。ネットから取ってきて保存する書き方 (XMLHTTP と ADODB.Stream) を **コメントの中に** 書いてあるので、検査は「危険」と判定するが、動かしても文字を出すだけ |

## 動かし方

1. Wine を入れる (X11 の画面の「追加機能」→「Windows のプログラム (Wine)」)
2. 「実行ファイル」で .wsf を選ぶ
   - 危険な書き方があれば赤い警告画面、初めて見るファイルなら青いスマートスクリーンが出る
     (「詳細情報」→「それでも実行する」で進む。同じ中身のファイルは次から出ない)
   - job が複数あれば、動かす job を選ぶ
3. 選んだ job を .js / .vbs にして D: ドライブに置き、Wine の cscript (画面を使うものは wscript) で動かす

日本語を出すスクリプトは、追加機能の Wine の「日本語の環境で動かす」をオンにすると正しく出ます
(オフのときは ? になります)。

## しくみと制限

Wine 10 の cscript / wscript は .wsf に対応していない (何もせずに終わる) ので、アプリが .wsf を読んで、
選んだ job を 1 つの .js / .vbs にまとめてから動かします (`WsfFile.java`)。

- `<object id progid>` は、先頭でそのオブジェクトを作る文に、`<resource>` は getResource("id") になります
- 1 つの job の中に JScript と VBScript が混ざっているもの、`src=` で別のファイルを読み込むもの、
  暗号化されたスクリプト (.encode) は動かせません (理由を表示します)
- Wine の VBScript の制限: `ChrW` / `AscW` は使えません。VBScript のファイルは Windows の文字コードで書きます
  (日本語の環境なら Shift_JIS、そうでなければ日本語は ? になります)
