# 細密バッテリー (PreciseBattery)

Android の標準バッテリー表示を 0〜1000 (= 0.1% 刻み) に拡張して見せる
浮動オーバーレイ + 常駐通知アプリ。

## 仕組み
- **非root**: `BatteryManager.BATTERY_PROPERTY_CAPACITY` の整数 % を 10 倍して 0..1000 として表示(50% なら 500)。
- **rootあり**(設定で ON、`su` が通る端末): `/sys/class/power_supply/battery/charge_counter` ÷ `charge_full` を 1000 倍して本当の 0.1% 刻みを表示。無ければ `energy_now`/`energy_full` にフォールバック、それも無ければ CAPACITY に戻る。

ステータスバーのネイティブ電池アイコン自体は OEM 署名のシステムアプリでないと差し替えられないため、
**画面常駐の浮動オーバーレイ**と**通知領域の数値**で代替しています。

## 画面
- メイン画面 — オーバーレイ権限の許可、開始/停止、「root を使う」「起動時に自動開始」
- 浮動オーバーレイ — ドラッグで好きな場所へ移動 (位置は記憶)
- 通知 — 詳細 (電圧 mV、温度 °C、取得ソース)

## ビルド
```bash
cd android-precisebattery
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```
