# APKドクター (APK Doctor)

インストールできないAPKをアップロードすると、原因を診断して自動修復し、そのままインストールできるAndroidアプリです。

APK / XAPK / APKS / APKM を受け取り、`AndroidManifest.xml` を直接書き換えて再パッケージ・再署名し、`PackageInstaller` でインストールします。

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
| `maxSdkVersion` による対象外 | `maxSdkVersion` が低い | 属性を削除 |
| ベースAPK単体で失敗 | `isSplitRequired` が有効 | フラグを解除 |
| ZIPが壊れている（ダウンロード破損） | 中央ディレクトリの破損・切り詰め | ローカルヘッダから読める範囲を再構築して再署名 |
| 分割APKの署名不一致 | 各分割の署名者が違う | 全パートを同じ鍵で再署名 |

## 診断のみ（端末側の対応が必要）

- **ABI不一致** — APKに端末のCPUアーキテクチャ向けネイティブライブラリが入っていない
- **署名の異なる同名アプリがインストール済み** — 先にアンインストールが必要（アプリから誘導）
- **ダウングレード** — インストール済みの方が新しい
- **インストール許可未設定** — 「不明なアプリのインストール」の許可（設定画面へ誘導）

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

`PackageInstaller` のセッションを使います。分割APKをインストールする唯一の方法であり、ベースと分割を1つのセッションにまとめてコミットします。

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

書き出したマニフェストは Android SDK の `aapt2 dump badging` / `aapt2 dump xmltree` でも読めることを確認済みです。

### 未検証の範囲

ユニットテストはJVM上で動作し、生成物は `aapt2` と `apksigner` で検証していますが、**実機／エミュレータ上での動作確認は行っていません**（ビルド環境にKVMがないため）。実際のインストール動作は実機で確認してください。

## 権限

- `REQUEST_INSTALL_PACKAGES` — 修復したAPKのインストール
- `REQUEST_DELETE_PACKAGES` — 署名衝突時のアンインストール誘導

ネットワーク権限はありません。すべての処理は端末内で完結します。
