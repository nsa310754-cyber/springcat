# 実行コード保存 (ExecSaver.exe)

パソコンで何かが実行されるたびに、**実際に実行されたコード**を見つけて、中身から形式を判定し、

```
ダウンロード\exe実行コード\(実行したファイル名)\(実行したファイル名).(形式に合った拡張子)
```

に保存する常駐アプリです。インストール不要・1 ファイル。

## 何を保存するか
| 実行のされ方 | 保存するもの |
|---|---|
| `game.exe` などを普通に起動 | その exe 自体 |
| `cmd /c x.bat` / バッチをダブルクリック | x.bat |
| `powershell -File x.ps1` | x.ps1 |
| `powershell -EncodedCommand ...` | Base64 を元に戻したコード → `powershell\powershell.ps1` |
| `powershell -Command ...` / `cmd /c 直接書いたコマンド` | そのコード (.ps1 / .bat) |
| `wscript` / `cscript x.vbs` / `x.js` | そのスクリプト |
| `python x.py` / `python -c ...`、node・ruby・perl・php も同様 | スクリプト / コード |
| `java -jar x.jar` | x.jar |
| `rundll32 x.dll,関数` / `regsvr32 x.dll` | x.dll |
| `msiexec /i x.msi` | x.msi |
| `mshta x.hta` / `mshta javascript:...` | HTA / コード |

## exe は「読めるコード」にして保存（既定でオン）
exe などのバイナリはそのままでは読めないので、テキストのプログラムコードに変換して保存します。

| 元のファイル | 保存されるもの |
|---|---|
| .NET (C# など) で作られた exe / dll | **C# のソースコード** `名前.cs`（ILSpy のエンジンで逆コンパイル） |
| C / C++ などの機械語の exe / dll（32bit / 64bit） | **アセンブリ言語** `名前.asm`（Iced で逆アセンブル）。使っている Windows の機能 (インポート) の一覧、埋め込まれた文字列の一覧つき。命令には `__imp_CreateFileA` のように API 名、`; "C:\temp\log.txt"` のように参照している文字列が付きます |
| ZIP / JAR / APK / Office | 中身の一覧と、中のテキスト（スクリプトなど） `名前.txt` |
| そのほかのバイナリ | 埋め込まれた文字列と先頭の 16 進ダンプ `名前.txt` |
| スクリプト（.bat .ps1 .vbs .py …） | もともとテキストなのでそのまま |

※ 機械語のプログラムは、元の C/C++ のソースコードには原理的に戻せません（コンパイルで変数名などが消えるため）。
　アセンブリ言語が、その exe が実際に実行する命令をそのまま読める形です。
※「元のバイナリも一緒に保存」をオンにすると、変換したものと一緒に元の exe も保存します。
※ 変換したファイルの先頭に元ファイルの SHA256 を書いておき、同じ exe は二度変換しません。

## 形式の判定
拡張子ではなく**中身**で判定します（例: 中身が Python の `readme.txt` → `readme.py`、拡張子のない実行ファイル → `.exe`）。
PE (exe / dll / sys、32/64bit、.NET)、ZIP / JAR / APK / Office、MSI、PDF、CAB、RAR、7z、ELF、
バッチ、PowerShell、VBScript、JavaScript、Python、シェル、HTA、WSF、REG、HTML、XML、JSON などに対応。

## そのほか
- 同じ内容は二重に保存しません。中身が変わったら `名前_2.拡張子` で保存します
- 各フォルダーの `実行記録.txt` に、いつ・どのコマンドラインで実行されたかを残します
- 既定では Windows / Program Files 内のプログラム自体は保存しません（そこから実行されたスクリプトは保存します）
- 200MB を超えるファイルは保存しません
- × で通知領域にしまっても監視は続きます。終了は通知領域の右クリック →「終了」
- 「Windows の起動時に自動で監視を始める」をオンにすると、ログオン時に通知領域で起動します
- 管理者として実行すると、管理者権限で動くプログラムのコマンドラインも読み取れます
- 監視は WMI で 0.5 秒ごとに確認するため、0.5 秒未満で終わるプログラムは取りこぼすことがあります

## ビルド (Linux)
```
./build.sh
```
.NET Framework 4.8 の参照アセンブリに対して Mono の mcs でビルドし、自己テストを実行します。
ICSharpCode.Decompiler 8.2 / Iced 1.21 など (MIT ライセンス) を NuGet から取得し、gzip して exe の中に埋め込みます。

必要環境: Windows 10 / 11（.NET Framework 4.8。標準で入っています）
