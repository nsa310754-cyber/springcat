# 画面オン維持 (StayAwake)

スマホの自動スリープ(画面消灯)を停止させる超シンプルなツール APK。
スイッチ 1 つで ON/OFF。

- **ON** のとき: 画面は暗転・消灯しない。他のアプリに切り替えても OFF にするまで継続。
- **OFF** のとき: 通常の OS スリープ設定に従う。
- 通知バーに常駐、「停止」ボタンから即 OFF。
- バッテリー最適化を除外するボタン付き(除外しないと機種によっては切られる)。

## 仕組み
- Activity 前面時: `FLAG_KEEP_SCREEN_ON`
- バックグラウンド継続: `KeepAwakeService` が `SCREEN_DIM_WAKE_LOCK` を保持
- `foregroundServiceType="specialUse"` のフォアグラウンドサービスとして常駐
- 必要権限: `WAKE_LOCK` / `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS`

## ビルド
```bash
cd android-stayawake
./gradlew assembleDebug
```
