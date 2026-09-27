# SoundTap — 音検知オートクリッカー (Android / Kotlin + Jetpack Compose)

音が鳴った瞬間に、画面の指定位置を自動でタップするアプリです。

## 成果物 (ビルド済み APK)

| ファイル | 用途 |
|---|---|
| `dist/SoundTap-1.0-release.apk` | 署名済みリリース版 (実機インストール用, 約 0.9MB) |
| `dist/SoundTap-1.0-debug.apk`   | デバッグ版 |

- パッケージ名: `site.ragdollp.soundtap` / versionName `1.0`
- minSdk 26 (Android 8.0) / targetSdk 34 / 「端末内の音」は Android 10 以上

## 使い方

1. APK をインストールして起動
2. 「ユーザー補助 (タップ操作)」→ 設定を開く → SoundTap をオン
   - 「制限付き設定」で押せないとき: アプリ情報 → 右上︙ → 制限付き設定を許可 → もう一度オン
3. 「位置を設定」→ タップしたい画面を開き、オレンジの照準をドラッグ (◀▲▼▶ で 1dp 微調整) → ✓ 決定
4. メーターを見ながらしきい値を調整 → 「監視を開始」
5. 画面上の丸いボタン: タップで一時停止/再開、長押しで停止、ドラッグで移動

## 高精度のための仕組み

- **小ブロック読み取り**: 端末ネイティブのサンプルレート (通常 48kHz) で 64 フレーム (≒1.3ms) ごとに判定。
  32/64/128/256 から選択可
- **生の入力**: マイクは対応端末なら `UNPROCESSED` (ノイズ抑制・AGC なし)、非対応なら `VOICE_RECOGNITION`
- **サンプル単位の判定**: RMS 平均ではなく各サンプルの絶対値でしきい値を判定するので、
  音の立ち上がりの 1 サンプル目で反応
- **スレッド切り替えなし**: `THREAD_PRIORITY_URGENT_AUDIO` の音声スレッドから直接 `dispatchGesture`
- **発生時刻の推定**: `AudioRecord.getTimestamp()` で「その音が実際に鳴った時刻」を求め、
  「音→タップ」の実測遅延を表示。遅延タップはその時刻を基準に、最後の 1〜2ms をスピン待ちして合わせる
- **連打**: 1 つの `GestureDescription` に時刻をずらしたストロークとして入れ、システム側で正確な間隔で刻む
- **誤反応対策**: ノイズ追従 (周囲音 + N dB を超えた急な音だけ)、低音カット (1次ハイパス)、クールダウン、
  「端末内の音」(AudioPlaybackCapture でゲーム音を直接取得)

## 構成

```
soundtap/android/app/src/main/java/site/ragdollp/soundtap/
├── Config.kt          # 設定値 (@Volatile, SharedPreferences 保存)
├── Engine.kt          # 画面・サービス間の共有状態
├── DetectorService.kt # 音の監視 (フォアグラウンドサービス: microphone / mediaProjection)
├── TapService.kt      # ユーザー補助サービス: タップ送信 + 照準/位置合わせ/フローティングボタン
└── MainActivity.kt    # 設定画面 (Compose)
```

## ビルド

前提: JDK 17+、Android SDK (platform android-35 / build-tools 35.0.0)。

```bash
cd soundtap/android
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

`soundtap-release.keystore` は個人配布用の使い捨て自己署名鍵です (パスワード・エイリアスとも `soundtap`)。

## 注意

- ゲームやサービスによっては自動操作が利用規約で禁止されています。利用は自己責任で。
- 「端末内の音」は、再生側アプリがキャプチャを禁止していると無音になります (その場合はマイクを使用)。
