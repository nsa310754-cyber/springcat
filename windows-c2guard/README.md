# C2Guard — C2 マルウェア通信の検出・ブロック (Windows)

PC の通信先を、公開されている「C2 サーバー（マルウェアの遠隔操作の司令塔）」の IP リストと照らし合わせ、
一致したら知らせる。見つけたら **ブロックする / 放置する** を選べる。

## 使い方

1. `C2Guard.exe` を **右クリック →「管理者として実行」**（ブロックに必要。管理者でなくても検出はできる）
2. 起動すると C2 リストをダウンロードし、5 秒ごとに PC の通信を見張る
3. 一致する通信があると確認画面が出る
   - **ブロックする**: Windows ファイアウォールにその IP との送受信を止める規則（`C2Guard-Block-<IP>`）を追加。
     「このプロセスも終了する」にチェックすると通信していたプログラムも終了する
   - **放置する**: 以後その IP は知らせない
   - × で閉じる: 今回は保留（次に起動したときにまた聞く）
4. タブ: 検出 / 今の通信 / ブロック中（解除できる）/ 放置リスト（外せる）/ ログ
5. 最小化すると通知領域で見張りを続ける

## 使っている C2 リスト（無料・登録不要）

- abuse.ch Feodo Tracker（Emotet・QakBot などのボットネット C2）
- C2IntelFeeds（Cobalt Strike などの C2。Censys の調査結果）
- 自分用リスト `%LOCALAPPDATA%\C2Guard\custom.txt`（1 行 1 IP）

6 時間ごとに自動更新。取れなかったときは前回のリストを使う。

## できないこと

- リストに載っていない C2（新しいもの・ドメインを次々変えるもの）は見つけられない。ウイルス対策ソフトの代わりにはならない
- IPv4 の TCP 通信だけを見る（IPv6・UDP は対象外）
- 共有のクラウドサーバーの IP がリストに載ることがあり、まれに誤検出がある

## データの場所

`%LOCALAPPDATA%\C2Guard\`（feeds/、state.json、custom.txt）

## ビルド（Linux）

```bash
sudo apt-get install mono-mcs mono-runtime libmono-system-windows-forms4.0-cil libmono-system-web-extensions4.0-cil
./build.sh     # dist/C2Guard.exe を作り、照合・保存・ファイアウォール引数の自己テストを実行
```

Windows 実機での起動確認はしていない（自己テストは Mono 上で Windows 以外の部分だけ）。
