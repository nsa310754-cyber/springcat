# SpringCat Files (ファイルマネージャ)

Android 向けの多機能ファイルマネージャ。ネイティブの `MainActivity` が WebView で
UI (`assets/app.html`) を描画し、実際のファイル操作は `FileBridge`(`@JavascriptInterface`)
が担当する構成です（既存のゲームアプリと同じ WebView + ネイティブ橋渡し方式）。

パッケージ名: `site.ragdollp.filemanager` / アプリ名: **SpringCat Files**

## 機能

| 要望 | 実装 |
|------|------|
| 圧縮ファイルをアプリ内で解凍して保存 | `extract()` — zip / 7z / tar / tar.gz(tgz) / gz / jar / apk / **rar** の展開に対応 |
| テキストはタップだけで閲覧 | タップで即ビューア/エディタが開く |
| ファイル形式を変えて開く（.md を txt、zip を apk 等） | 「別の形式で開く」+ リネームで拡張子変更、`openAs()` で任意 MIME 委譲 |
| txt の編集 | エディタで編集して保存 (`writeText`) |
| 複数選択して圧縮 | 選択モード → `compress()`。zip / 7z / tar / gz / tar.gz / jar に対応 |
| ストレージ確認 | `storageInfo()` — 各ボリュームの使用量/空き容量 |
| 簡易セキュリティ（APK 検査してインストール） | `inspectApk()` で権限/署名/リスクスコアを表示 → 警告後でも「それでもインストール」で導入可 |
| オンライン時に JS/TS を実行して出力表示 | サンドボックス iframe で console 出力を捕捉。TS はオンライン時に CDN でトランスパイル |
| HTML / JSX プレビュー | HTML は srcdoc、JSX はオンライン時に React + Babel で描画 |
| ファイル追加（名前と拡張子を入力） | ＋ ボタン → 名前(拡張子込み)と初期内容を入力して作成 |

### v1.3.1 で追加した機能

| 機能 | 実装 |
|------|------|
| root化 確認ボタン | ツールバーの「🔎 root確認」で端末の root 状態を確認。su バイナリの有無/パス・test-keys 署名・ルートモード状態を表示し、「権限を取得してテスト」で実際に `su -c id` を実行して uid=0 が取れるか検証。そのままルートモードを ON にできる (`rootStatus`) |

### v1.3.0 で追加した機能 (ルート対応)

| 機能 | 実装 |
|------|------|
| スーパーユーザー (root) 対応 | root 化端末で「ルート」ボタンから su 権限を要求。ON の間は一覧/閲覧/編集/作成/削除/リネーム/移動を `su` 経由で実行し、通常アプリでは開けない `/data/data`・`/system` 等のコードや設定を閲覧・編集できる (`RootShell`) |
| パスへ移動 | 任意の絶対パス (例: `/data/data/<pkg>`) を直接開く。よく使うルートのショートカット付き |
| 権限不足の自動案内 | 権限で開けないフォルダに入ろうとすると、root 化端末ではルートモード有効化を提案 (`rootHint`) |

root は明示的に ON にしたときだけ動作し、非 root 端末やボタン未使用時は一切 su を呼びません。su の許可は Magisk 等の管理アプリが判断します。

### v1.2.0 で追加した機能

| 機能 | 実装 |
|------|------|
| 統合展開 (スマート解凍) | 解凍時に「ここに展開して統合」を選ぶと、アーカイブ内の同名フォルダを端末の既存フォルダに合流させる (例: zip 内の `Download/` → 端末の `Download/` に統合、同名ファイルは上書き)。オフにすると従来どおり新規フォルダに展開。既定はオン |

### v1.1.0 で追加した機能

| 機能 | 実装 |
|------|------|
| 多言語のオンライン実行 | Piston API 経由で Python/Ruby/Go/C/C++/Java/Rust/PHP/Bash/Kotlin/Swift/C#/Lua/Perl/R… を実行。stdin・言語選択・exit code 表示。HTTP はネイティブ (`httpRequest`) 経由で CORS 回避。JS/TS はオフラインのローカル実行も可 |
| 解凍パスワード | 暗号化 zip(zip4j) / 7z(Commons Compress) / rar(junrar) をパスワードで展開。暗号化検知時は自動でパスワード入力を促す |
| 圧縮パスワード | zip を AES-256 で暗号化して作成 (zip4j) |
| 0バイトファイル削除 | 「整理」→ 再帰的に 0B ファイルを一括削除 |
| 検索 | ファイル名 (部分一致) + 任意でテキスト内容も再帰検索 |
| 並び替え | 名前 / サイズ / 更新日時 / 拡張子 × 昇順・降順 (設定は保存) |
| 隠しファイル表示切替 | ドットファイルの表示/非表示 |
| 詳細 / ハッシュ | サイズ・更新日時・権限に加え MD5 / SHA-256 を計算 |
| 移動 / コピー | 複数選択してフォルダを選び一括移動・コピー |
| 空フォルダ削除・重複検索 | 「整理」ツール。重複はサイズ+SHA-256 で判定し原本以外を削除可 |
| ブックマーク | よく使うフォルダをストレージ画面に登録 |

### rar / 7z について
- **7z**: 作成・展開ともに対応（Apache Commons Compress + XZ）。
- **rar**: 独自の圧縮アルゴリズムのためオープンな作成手段がなく、**展開のみ**対応（junrar）。
  圧縮形式で rar を選ぶと明示的に非対応と案内します。

## ビルド

Android SDK が入った環境で:

```bash
cd filemanager
./gradlew assembleRelease   # 署名済みリリース APK
./gradlew assembleDebug     # デバッグ APK
```

出力先:
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/apk/debug/app-debug.apk`

署名鍵は `release.keystore`（パスワード等はゲームアプリと共通の使い捨て鍵。
Play Store 公開時は各自の鍵に差し替えてください）。

### CI で APK を作る
SDK が無い環境向けに GitHub Actions のワークフロー
`.github/workflows/build-filemanager.yml` を用意しています。`filemanager/` を変更して
push するか、Actions から手動実行すると、debug/release の APK が成果物として
アップロードされます（Actions › 対象の run › Artifacts からダウンロード）。

## 権限
- `MANAGE_EXTERNAL_STORAGE`（Android 11+ の全ファイルアクセス。初回に許可を求めます）
- `REQUEST_INSTALL_PACKAGES`（APK のサイドロード）
- `INTERNET`（JS/TS トランスパイル・JSX プレビューのオンライン処理のみ）

## セキュリティに関する注意
APK 検査は「アプリ名 / パッケージ / 署名の有無 / 危険権限 / 簡易リスクスコア」を
提示する**目安**です。危険と表示されたアプリでも、最終的なインストール判断は
利用者に委ねられます（「それでもインストール」）。実際のインストール確認は
Android 標準のパッケージインストーラが行います。
