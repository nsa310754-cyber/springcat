# 4分割ランチャー (Split4) — Android アプリ (APK)

スマホ 1 台で **4 つのアプリを画面 2×2 の 4 分割** で同時に開くアプリです。
Android のフリーフォーム (自由サイズ) ウィンドウ機能を使い、各アプリを画面の 1/4 の位置に配置します。
root 化済み端末で最も確実に動作します。

## 成果物

| ファイル | 用途 |
|---|---|
| `dist/Split4-1.0-release.apk` | 署名済みリリース版 |
| `dist/Split4-1.0-debug.apk`   | デバッグ版 |

- パッケージ名: `site.ragdollp.split4` / minSdk 26 (Android 8.0) / targetSdk 34

## 使い方

1. APK をインストールして「4分割ランチャー」を開く
2. **「フリーフォームを有効化 (root)」** を押す (Magisk 等の root 許可ダイアログで許可)
   → 初回は **端末を再起動** してください
3. 左上・右上・左下・右下の各マスをタップして表示したいアプリを選ぶ (長押しで解除)
4. **「4分割で起動」** を押す

起動方法は 4 通り: アプリ内のボタン / 常駐通知のボタン / クイック設定タイル「4分割起動」 / ホーム画面ショートカット。

## 仕組み

- **root あり (推奨)**
  1. `settings put global enable_freeform_support 1` / `force_resizable_activities 1` などでフリーフォームを強制有効化
  2. (オプション) `am force-stop` で 4 アプリを一度終了 — 全画面のタスクが残っているとフリーフォームにならないため
  3. `am start --windowingMode 5` で各アプリをフリーフォームで起動
  4. `dumpsys activity activities` からタスク ID を調べ、`am task resize <id> l t r b` で 2×2 の位置に合わせる
- **root なし**: `ActivityOptions#setLaunchBounds` で位置を指定して起動 (端末側でフリーフォームが有効な場合のみ)

デバイス管理権限は不要です (root だけで動作します)。

## 注意

- メーカー独自 ROM (One UI / MIUI / HyperOS など) はフリーフォームの実装が異なり、うまく並ばないことがあります。
  AOSP 系 ROM (Pixel / LineageOS など) で最も安定します。
- 「起動前に4つのアプリを一度終了する」が ON だと、保存していない入力内容は失われます。
- 画面の大きさによっては、最小ウィンドウサイズを持つアプリが 1/4 に収まらない場合があります。

## ビルド

```sh
cd split4/android
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease
```
