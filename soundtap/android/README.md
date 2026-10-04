# SoundTap — 音検知オートクリッカー (Android / Kotlin + Jetpack Compose)

音が鳴った瞬間に、画面の指定位置を自動でタップするアプリです。

## 成果物 (ビルド済み APK)

| ファイル | 用途 |
|---|---|
| `dist/SoundTap-1.7-release.apk` | 署名済みリリース版 (実機インストール用, 約 0.9MB) |
| `dist/SoundTap-1.7-debug.apk`   | デバッグ版 |

- パッケージ名: `site.ragdollp.soundtap` / versionName `1.7`
- minSdk 26 (Android 8.0) / targetSdk 34 / 「端末内の音」は Android 10 以上

## ADOFAI 自動モード (A Dance of Fire and Ice)

音は使わず、譜面ファイル (`.adofai`) から全タップの時刻を計算して叩くモードです。

1. 「ADOFAI 自動」タブでコースを登録・選択 (`.adofai` 追加、または root でゲームから読み込み)
2. スピードトライアルで遊ぶなら倍率 (×1.0〜×3.0) をゲームと同じにする
3. 「▶ ADOFAI を開いて準備」→ ゲームが開き、左上に操作バー (▶ / − / + / ✕) が出る
4. 「タップしてスタート」の画面でバーの ▶ → **アプリがスタートのタップを押し**、そこから最後まで全自動 (既定)
   - 「スタートは自分で」を選ぶと、自分の「タップしてスタート」の時刻を 1px の監視窓
     (`FLAG_WATCH_OUTSIDE_TOUCH`) で受け取り、そこから自動 (タップはそのままゲームに届く)
   - 「1タイル目を自分で」: スタート後のカウントダウン中に ▶ → 1 タイル目を自分で押すと、そのタップを
     1 枚目として 2 枚目から自動 (開始までの間に関係なく合う)
5. スタート後に間があるステージは「1枚目を自分で押して測る」をオンにして 1 回遊ぶ → 1 枚目の時刻から
   「開始の間・補正」を自動で記録し、2 枚目から自動。次回からは完全自動
6. Early が多ければ ＋、Late が多ければ −。開始の間・補正はコース × 倍率ごとに保存

計算しているもの (`AdofaiChart.kt`, ADOFAI-JS / ADOCAO / ADOFAI-Map-Converter と同じ方式):

- 相対角 = 前の向き − 次の向き + 360/惑星数 (通常 +180°)。`angleData` と旧 `pathData` の両方に対応
- 999 = ミッドスピン (同時刻として 1 タップにまとめる)、555/666/777/888 = 直前からの ±72° / ±52°
- `Twirl` (逆回転)、`SetSpeed` (BPM / 倍率)、`Pause` (拍。一周タイルでは 1 拍引くゲーム仕様に合わせる)、
  `Hold` (回転数 × 2 拍。連続ホールドは 1 回の長押し、最初の非ホールドタイルで離す)、
  `MultiPlanet` (惑星 N 個)、`FreeRoam` (拍数ぶん待つ・その間は叩かない)、`AutoPlayTiles`、
  `Multitap` (指の本数。設定でオンにすると複数本同時に押す)
- 1 拍 = 180°。1 枚目は開始から max(offset, 2 拍) 後。`pitch` / スピードトライアルで全体を伸縮
- Neo Cosmos・Muse Dash などコラボ / DLC のステージも同じ形式。root スキャンはアプリのデータ領域
  (後からダウンロードされた分や Unity キャッシュ) も含めて探す

公式コースの譜面はゲーム内にのみ含まれ公開されていないため同梱していません。

### 公式コースを端末から直接読み込む (root 端末のみ, 1.2〜)

「ゲームから直接読み込む (root)」→「ADOFAI のデータをスキャン」で、`su` 権限を使って端末内の ADOFAI
(`com.fizzd.connectedworlds`) のファイルを **読み取りのみ** で走査し、埋め込まれた譜面を取り出します (`GameScan.kt` / `UnityScanner.kt`)。

- 対象: `pm path` の APK 群 (base / split / Play Asset Delivery のインストール時パック)、`files/assetpacks`、obb、`Android/data`
- APK (zip) → UnityFS バンドル (LZ4 / LZ4HC / LZMA ブロックを順に展開) → 生データ の順に流し読み
- `"angleData"` / `"pathData"` で始まる JSON を見つけたら、直前の TextAsset ヘッダからアセット名 (例 `AR-X`) と長さを取得
- 見つかったコースを一覧表示 (「○○-X のみ」でワールド順の X ステージだけに絞り込み) → 「追加」で自動モードのコースに登録
- 取り出した譜面はアプリの領域にだけ保存 (外部送信なし)。見つからない場合は「診断レポート」(ファイル名と大きさだけ) をコピー可能

## 使い方 (音で反応モード)

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
