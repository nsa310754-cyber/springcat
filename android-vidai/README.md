# Vid-AI — Claude で動画をAI編集する Android アプリ

Claude Messages API の vision 機能で動画のフレームを解析し、「残すべき区間」を
JSON で返してもらって自動カット編集する実験的アプリ。

## 入力
- Claude API キー (`sk-ant-...`)
- 保有クレジット額 (USD)
- 編集指示の自由テキスト(例: 「無音部分を飛ばして」「ハイライトだけ」「10秒に縮めて」)

## 予算 → モデル自動選択
| クレジット | 使用モデル | ID | サンプリング |
|---|---|---|---|
| **\$100+** | Opus 5.5 (最高精度) | `claude-opus-5-5` | 2秒に1枚 |
| **\$50+**  | Sonnet 5.5 (高精度) | `claude-sonnet-5-5` | 3秒に1枚 |
| **\$25+**  | Haiku 5.5 (標準) | `claude-haiku-5-5` | 5秒に1枚 |
| **\$5+**   | Haiku 4.5 (節約) | `claude-haiku-4-5` | 10秒に1枚 |

※ ユーザー指定の "HAIKU 4.8" は存在しないので `claude-haiku-4-5` を代替採用。
※ 入力コストが保有額の半分を超えないよう、最大フレーム数を自動で絞ります。

## 処理フロー
1. `MediaMetadataRetriever` で等間隔にフレーム抽出 → 640x360 JPEG に縮小
2. 全フレームを 1 リクエストで Claude に送信(timestamps 付き)
3. Claude が `{"segments":[{start,end,why}, ...]}` 形式で「残す区間」を返す
4. `MediaExtractor` + `MediaMuxer` でその区間だけを抜き出して連結
   - 再エンコードなし(コピーコーデック)なので高速・画質無劣化
   - カット位置は前方キーフレームに丸められます(多少はみ出ます)

## 出力
`/sdcard/Movies/VidAI/edited_<timestamp>.mp4`

## 制限
- 音声トラックは 1 本目のみコピー。再エンコードしないので複数トラックは落ちます。
- API キーは端末内 SharedPreferences に保存(平文)。Keystore 封入は TODO。
- vision リクエストは 1 回で送るので、長い動画 + 高密度サンプリングだと 1 回の API 呼び出しが重くなります。
- フレームサンプリングのタイムスタンプと実際の keyframe 位置がずれることがあります。

## ビルド
```bash
cd android-vidai
./gradlew assembleDebug
```
