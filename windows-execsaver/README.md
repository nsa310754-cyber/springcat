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
.NET Framework 4.6.2 の参照アセンブリに対して Mono の mcs でビルドし、自己テストを実行します。
