# springcat モニター (Android)

端末のシステム状態をリアルタイム表示するネイティブ Android アプリです。
リポジトリ本体の Spring Boot 版ダッシュボードと同じデザイン言語で、端末単体で動作します。

## 機能

- **折れ線グラフ** — CPU 使用率とメモリ使用率の直近 60 秒の推移を、依存ライブラリなしの
  自作 `LineChartView` で描画（0–100% 軸）。
- **CPU 取得のフォールバック** — システム全体の CPU は以下の優先順で取得します。
  値の出所はラベルで明示します（`root` / `アプリ`）。
  1. 権限なしで `/proc/stat`（多くの Android 8+ 端末では OS が遮断）→ 「CPU」
  2. **root モード**: 端末が root 化されている場合、`su -c cat /proc/stat` で読取り → 「CPU (root)」
  3. 上記が不可なら、このアプリ自身のプロセス CPU 使用率 → 「CPU (アプリ)」

  > **root について**: アプリ側から root を「付与」することはできません。端末が root 化
  > （Magisk 等）されていて、初回に端末のスーパーユーザー管理アプリが許可した場合のみ
  > root モードが有効になります。非 root 端末では自動的に 3. のフォールバックになります。
  >
  > **システムアプリ権限（platform 署名）** は通常配布 APK では取得できません。ROM の
  > platform 鍵で署名し `/system/priv-app` へ配置する必要があり、実質カスタム ROM の
  > ビルドが前提です。本アプリは通常アプリとして動作し、root がある場合のみ上記 2. を使います。
- **ネットワーク情報（状態のみ）** — 接続種別（Wi-Fi / モバイル / イーサネット）、SSID、
  IP アドレス、リンク速度、電波強度（RSSI）、受信/送信スループット。
  - ※ 保存済み Wi-Fi パスワードの表示は行いません。通常アプリ（非 root）では OS の
    セキュリティ制約により取得できず、資格情報の抽出にあたるためです。
- **CPU / メモリ / ストレージ** — 使用率をしきい値で色分け（70% で黄、90% で赤）。
- **デバイス情報** — 機種、Android バージョン、コア数、稼働時間。

すべて端末自身のローカル計測のみで、外部送信や他アプリのデータ参照は行いません。

## バックグラウンド継続（Foreground Service）

計測ロジックは `MonitorService`（Foreground Service）に集約されており、アプリを閉じても
画面を消しても継続します。Activity は計測せず、`MonitorState` を通じて結果を表示するだけです。

- **常駐通知** — 「CPU ◯% · メモリ ◯%」を通知領域に表示し続けます（タップで画面を開く／
  「停止」アクションで計測終了）。
- **WakeLock** — 画面オフ時も CPU が計測を続けられるよう partial WakeLock を保持します
  （電池消費が増えるため、不要になったら通知の「停止」で解放してください）。
- **電池最適化の解除** — 画面下部のボタンから `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  を起動し、OS によるプロセス停止を防ぐ設定へ誘導します。
- **Android 14 対応** — Foreground Service のタイプを `specialUse`
  （サブタイプ `system_resource_monitoring`）として宣言しています。

> `specialUse` は Google Play では審査時に用途説明が求められる区分です。本アプリはサイド
> ロード（`adb install` / 手動インストール）前提のため問題なく動作します。

## 権限

| 権限 | 用途 |
|------|------|
| `ACCESS_NETWORK_STATE` | 接続種別・IP・検証状態の取得 |
| `ACCESS_WIFI_STATE` | リンク速度・RSSI・周波数の取得 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | 接続中 Wi-Fi の SSID 表示（Android の仕様。拒否しても SSID 以外は動作） |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | バックグラウンド計測の常駐 |
| `WAKE_LOCK` | 画面オフ時の計測継続 |
| `POST_NOTIFICATIONS` | 常駐通知の表示（Android 13+） |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 電池最適化からの除外を依頼 |

## ビルド

Android SDK（Platform 34 / Build-Tools 34.0.0）が必要です。SDK の場所を `local.properties`
に記述してください（`ANDROID_HOME` 環境変数でも可）。

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle :app:assembleDebug
# 生成物: app/build/outputs/apk/debug/app-debug.apk
```

- `minSdk` 26 (Android 8.0) / `targetSdk` 34 / Kotlin + View Binding
- デバッグ APK は Android デバッグ証明書で署名済み → そのまま端末にインストール可能

## インストール

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

または APK ファイルを端末に転送し、「提供元不明のアプリ」を許可して開いてください。

## 構成

```
app/src/main/
  AndroidManifest.xml
  java/com/springcat/monitor/
    MainActivity.kt      1秒ごとにサンプリングし UI とグラフを更新
    SystemStats.kt       端末メトリクスの収集（/proc/stat・ActivityManager・ConnectivityManager 等）
    LineChartView.kt     自作の折れ線グラフ View
  res/layout/activity_main.xml
```
