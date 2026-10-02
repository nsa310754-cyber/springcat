# APKドクター (APK Doctor)

インストールできないAPKをアップロードすると、原因を診断して自動修復し、そのままインストールできるAndroidアプリです。

APK / XAPK / APKS / APKM、および **base.apk + 複数の分割APK**（個別にはインストールできないもの）を受け取り、`AndroidManifest.xml` を直接書き換えて再パッケージ・再署名し、`PackageInstaller` の1セッションでまとめてインストールします。root 端末では `su` 経由の `pm install` で、署名を保持したまま、署名衝突・ダウングレード・testOnly も越えてインストールできます。

---

## 自動で直せること

| 症状 | 原因 | 修復内容 |
|---|---|---|
| 「アプリがインストールされていません」 | 署名がない／壊れている | v1+v2+v3 で再署名 |
| Android 11+ でインストール失敗 | 署名がv1のみ（targetSdk 30+） | v2/v3署名を追加 |
| Android 14+ でインストール拒否 | `targetSdkVersion` が古すぎる | 端末が要求する最低値まで引き上げ |
| 「お使いの端末に対応していません」 | `minSdkVersion` が端末より高い | 端末のAPIレベルまで引き下げ |
| `INSTALL_FAILED_TEST_ONLY` | `android:testOnly="true"` | フラグを削除 |
| Android 11+ で `INSTALL_PARSE_FAILED` | `resources.arsc` が圧縮されている | 無圧縮（STORED）で格納し直す |
| タップしてもインストールできない | 分割APKバンドル | 端末に合う分割だけ選んで一括インストール |
| **base.apk + 分割APKがバラバラ** | 個別には署名・分割の都合でインストール不可 | まとめて選択 → 端末に合う分割を選び、必要なら全パートを同一鍵で再署名して一括インストール |
| `maxSdkVersion` による対象外 | `maxSdkVersion` が低い | 属性を削除 |
| ベースAPK単体で失敗 | `isSplitRequired` が有効 | フラグを解除 |
| ZIPが壊れている（ダウンロード破損） | 中央ディレクトリの破損・切り詰め | ローカルヘッダから読める範囲を再構築して再署名 |
| 分割APKの署名不一致 | 各分割の署名者が違う | 全パートを同じ鍵で再署名 |

## 診断のみ（端末側の対応が必要）

- **ABI不一致** — APKに端末のCPUアーキテクチャ向けネイティブライブラリが入っていない
- **署名の異なる同名アプリがインストール済み** — 先にアンインストール（root ならそのまま置き換え可能）
- **ダウングレード** — インストール済みの方が新しい（root ならインストール可能）
- **インストール許可未設定** — 「不明なアプリのインストール」の許可（root 時は不要）
- **`.aab`（Android App Bundle）** — Google Play 用の配布形式。パッケージ名・バージョンは表示するが、そのままインストールはできない（下記参照）

---

## base.apk + 分割APK / AAB の扱い

**「aab をインストールしたい」= 実際には base.apk + 複数の分割APK**、という前提での実装です。

- **バラバラの分割APKをまとめて選択** — ファイル選択で `base.apk` と `split_config.*.apk` を複数選ぶ（または複数共有する）と、1つのアプリとして束ね、端末に合う分割だけを選択します。別パッケージが混ざっていれば警告してベースと同じアプリのものだけを対象にします。各分割が未署名／署名バラバラでも、全パートを同一の鍵で再署名してから `PackageInstaller` の1セッションでまとめて入れます。
- **真の `.aab` ファイル** — `.aab` は protobuf 形式（`resources.pb`・protobuf マニフェスト）で、端末上ではそのままインストールできません。本アプリは `.aab` を検出し、**protobuf マニフェストを読んでパッケージ名・バージョン・SDK を表示**したうえで、「bundletool 等で分割APKに変換し、それらをここでまとめて選択してください」と案内します（端末内での `.aab`→APK 変換は aapt2 相当が必要なため未実装）。bundletool が出力する `.apks`（中に分割APKが入っている）は従来どおり直接インストールできます。

## root インストール（任意）

root を検出すると「root でインストール（署名そのまま）」ボタンが出ます。`su -c pm install-create / install-write / install-commit` を実行し、**元の署名を保持**（再署名なし）したまま、`-r -d -t -g` 付きでインストールします。これにより次のケースを越えられます。

- 署名の異なる同名アプリがインストール済み（`-d` で置き換え）
- ダウングレード
- `testOnly` APK（`-t`）
- 「不明なアプリのインストール」未許可（root では不要）

root は Android のマニフェスト権限ではないため、**追加のパーミッション宣言はありません**。`su` バイナリが存在する端末でのみボタンが表示されます。

---

## 使い方

1. アプリを開いて「APKファイルを選ぶ」、またはファイルマネージャからAPKを共有／タップして開く
2. 診断結果を確認する（自動修復できる項目にはバッジが付きます）
3. 「修復する」→ 修復後に自動で再チェックが走る
4. 「修復版をインストール」、または「修復版を保存」でファイルとして書き出す

---

## 仕組み

### バイナリXML（AXML）の読み書き

`AndroidManifest.xml` はAPK内でバイナリ形式（AXML）で格納されているため、`app/src/main/java/com/springcat/apkdoctor/apk/Axml.kt` に専用のパーサ／シリアライザを実装しています。文字列プールとリソースマップを解析してDOMに展開し、編集後に再構築します。

書き出しで特に重要な点が2つあります。

- **属性はリソースIDの昇順でなければならない。** フレームワークの `ResTable::retrieveAttributes` は属性列を一度だけ前方に走査するため、順序が崩れた属性は実行時に無言で無視されます。
- **リソースIDを持つ属性名は文字列プールの先頭に置く必要がある。** リソースマップはインデックス位置で引かれるためです。

`<uses-sdk>` が存在しない古いAPKでも、要素ごと新規挿入して `targetSdkVersion` を付与できます。

### 再パッケージ

エントリごとの圧縮方式を保持したまま再構築します。`resources.arsc` や無圧縮の `.so` を勝手に圧縮すると、Android 11+ でインストールできなくなるためです。アライメントは apksig の出力側（`setAlignmentPreserved(false)`、`.so` は16KBページ境界）に任せています。

### 署名

Google の [apksig](https://android.googlesource.com/platform/tools/apksig/)（Android Gradle Plugin と同じ実装）で v1+v2+v3 署名を行います。署名鍵は初回起動時に BouncyCastle で自己署名証明書を生成し、アプリのプライベート領域に PKCS#12 として保存します。同じ鍵を使い続けるため、同じアプリを2回修復した場合は更新としてインストールできます。

> **注意:** 修復版は元の署名とは異なる鍵で署名されます。既に同じアプリがインストールされている場合、インストール前にアンインストールが必要です（アプリ内で警告します）。

### インストール

通常は `PackageInstaller` のセッションを使います。分割APKをインストールする唯一の標準手段であり、ベースと分割を1つのセッションにまとめてコミットします。root 時は `RootInstaller` が `su -c pm install-create/-write/-commit` を実行し、元の署名のままインストールします（コマンド列は注入可能な `CommandRunner` 越しに組み立てるため、端末なしでも単体テストできます）。

### `.aab` の読み取り

`.aab` の `AndroidManifest.xml` は protobuf（aapt2 の `XmlNode`）で格納されています。`ProtoManifest.kt` に最小限の protobuf デコーダを実装し、パッケージ名・versionCode・versionName・min/target SDK を取り出して診断に表示します。

---

## ビルド

```bash
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew test                # ユニットテスト
```

Android SDK 36 / JDK 17+ が必要です。ビルド済みAPKは [`dist/`](dist/) にあります（デバッグ鍵で署名済み、そのままサイドロード可能）。

## テスト

`./gradlew test` は、ビルドしたAPK自身を材料に実際の処理経路を通します。

- **`AxmlRoundTripTest`** — 実APKのマニフェストを再シリアライズして全属性の一致、バイト安定性、リソースID昇順を検証。`uses-sdk` の新規挿入も確認します。
- **`RepairPipelineTest`** — `targetSdkVersion=15` / `testOnly=true` / 署名なしの壊れたAPKを作り、診断→修復→apksigによる署名検証→再診断まで通します。切り詰めたAPKのサルベージ経路も含みます。
- **`BundleRepairTest`** — ベース＋ABI/解像度別の分割を含む `.xapk` を組み立て、端末に合う分割だけが選ばれること、全パートが同一鍵で署名されることを検証します。
- **`MultiSplitInstallTest`** — `base.apk` と分割APKを**バラバラのファイル**として組み立て、まとめて選択→端末に合う分割の選択→全パートの同一鍵署名→検証まで通します。別パッケージ混在が警告され除外されることも確認します。
- **`ProtoManifestTest`** — `aapt2 convert --output-format proto` で実際の protobuf マニフェストを生成し、読み取り結果が元の値と一致することを検証。`.aab` を組み立てて検出・診断されることも確認します（aapt2 がある環境でのみ実行）。
- **`RootInstallerTest`** — 偽の `CommandRunner` で `install-create → install-write（ファイルごと）→ install-commit` の順序・フラグ・サイズ・stdin を検証し、失敗時のセッション破棄も確認します。

書き出したマニフェストは Android SDK の `aapt2 dump badging` / `aapt2 dump xmltree` でも読めることを確認済みです。

### 未検証の範囲

ユニットテストはJVM上で動作し、生成物は `aapt2` と `apksigner` で検証していますが、**実機／エミュレータ上での動作確認は行っていません**（ビルド環境にKVMがないため）。実際のインストール動作は実機で確認してください。

## 権限

- `REQUEST_INSTALL_PACKAGES` — 修復したAPKのインストール
- `REQUEST_DELETE_PACKAGES` — 署名衝突時のアンインストール誘導

root インストールは `su` バイナリ経由で動くため、追加のマニフェスト権限は宣言しません。ネットワーク権限もありません。すべての処理は端末内で完結します。
