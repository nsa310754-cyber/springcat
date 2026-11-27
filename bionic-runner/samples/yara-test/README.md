# YARA ルールのサンプル

- `sample.yar` — 害の無いサンプルルール。「実行ファイル」から選ぶと、書き方を確かめたうえで
  「検知に追加」か「ファイルをスキャン」を選べる。
- `marker_test.sh` — `sample.yar` の `BR_Sample_Marker` に一致するだけの、何もしないスクリプト。

試し方:

1. 「実行ファイル」→ `sample.yar` →「ファイルをスキャン」→ `marker_test.sh` を選ぶと、
   `● 一致: BR_Sample_Marker` と表示される。
2. 「検知に追加」したあとで「実行ファイル」から `marker_test.sh` を開くと、赤い警告画面が出る。
   設定の「個人設定」→ セキュリティ で `sample.yar` を外すと、出なくなる。
