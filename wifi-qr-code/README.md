# Wi-Fi QRコード生成 (wifi-qr-code)

現在接続中のWi-FiのSSID(ネットワーク名)を自動検出し、パスワードを入力するだけで
「Wi-Fi接続用QRコード」を生成・保存・共有できるAndroidアプリです。

生成されるQRコードは標準的な `WIFI:T:<種別>;S:<SSID>;P:<パスワード>;;` 形式なので、
標準カメラアプリ(Android/iOS)で読み取ると、そのままそのWi-Fiに接続できます。

## 重要な制約について(なぜパスワードは手入力なのか)

Android OSの仕様(セキュリティ制限)により、Android 10以降ではサードパーティアプリが
**保存済みWi-Fiのパスワードをプログラムから読み取ることはできません**。
そのため本アプリは、

1. 接続中のSSID(ネットワーク名)は `WifiManager` から自動検出
2. パスワードはユーザー自身が画面に入力

という構成になっています。root化端末やADB経由のバックアップ抽出を使えば
既存のWi-Fi設定(パスワード込み)を取得できる場合がありますが、それは端末の
セキュリティ制限を回避する行為であり本アプリでは行いません。

## 主な機能

- 現在接続中のWi-Fi SSIDの自動検出(再検出ボタンあり)
- セキュリティ種別の選択(WPA/WPA2/WPA3, WEP, パスワードなし)
- 非表示(ステルス)ネットワーク対応
- QRコード生成(ZXingライブラリ、オフラインで動作・通信なし)
- QR画像の端末への保存(ピクチャ/WifiQrCode)
- QR画像の共有(LINE/メール等)

## 必要な権限

| 権限 | 用途 |
|---|---|
| `ACCESS_WIFI_STATE` / `ACCESS_NETWORK_STATE` | 接続中のWi-Fi情報取得 |
| `ACCESS_FINE_LOCATION` (Android 12以下) | OS仕様上、SSID取得に位置情報権限が必要なため。位置情報自体は使用・保存しません |
| `NEARBY_WIFI_DEVICES` (Android 13以降) | Wi-Fi情報取得用 (`neverForLocation` 指定、位置情報用途ではありません) |

SSIDが取得できない場合は、端末設定で「位置情報(GPS)」をONにしてから
アプリ内の「再検出」を押してください。それでも取得できない場合は、
ネットワーク名欄に手入力することもできます。

## ビルド方法

```bash
export ANDROID_HOME=/path/to/android-sdk
cd wifi-qr-code
gradle assembleDebug     # デバッグ用APK
gradle assembleRelease   # リリース用APK(署名済み)
```

出力先:
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

## 署名鍵について

同梱の `wifiqrcode-release.keystore` は動作確認・個人配布用の使い捨て鍵です
(ストアパスワード/キーパスワードとも `wifiqrcode`、エイリアス `wifiqrcode`)。
Google Play等で正式に公開する場合は、必ず各自で新しい鍵を作成し差し替えてください。

## 使い方

1. アプリを起動すると、現在接続中のWi-FiのSSIDが自動表示されます
2. セキュリティ種別を選択し、パスワードを入力します
3. 「QRコードを生成」をタップ
4. 表示されたQRコードを保存または共有します
5. 他の端末のカメラでQRコードを読み取ると、そのWi-Fiに接続できます
