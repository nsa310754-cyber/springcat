# PCリモート (VNC) — スマホから PC を操作

自作 RFB 3.8 クライアント実装。TightVNC / TigerVNC / UltraVNC いずれの標準設定にも接続可能。

## PC 側セットアップ (TightVNC 無料版 の例)
1. https://www.tightvnc.com/ から Windows 64-bit 版インストーラをダウンロード
2. インストール中「TightVNC Service」を選択、Primary password を 8 文字以内で設定
3. Windows ファイアウォールでポート 5900 (TCP) を許可
4. PC の IP: `cmd` で `ipconfig` → IPv4 アドレス

## スマホ側
1. APK インストール → 開く
2. IP アドレス / ポート 5900 / パスワードを入力 → 「接続」
3. ピンチでズーム、2 本指ドラッグでパン
4. タップ = 左クリック、2 本指タップ = 右クリック、3 本指タップ = 中クリック
5. 下部ツールバー: ⌨ キーボード表示 / Esc / Tab / Win / Ctrl+Alt+Del / 切断

## 対応エンコーディング
- Raw (0) — サーバの全画面を無圧縮で転送 (遅いが最も確実)
- CopyRect (1) — スクロール時の最適化

## 制限
- 圧縮エンコーディング (Hextile, Tight, ZRLE) は未実装 → 大きい画面では遅い
- TLS (RFB セキュリティ 18, 19) は未実装 → 公衆 Wi-Fi 上の使用は注意
- 音声は流れない (VNC プロトコルの範囲外)
