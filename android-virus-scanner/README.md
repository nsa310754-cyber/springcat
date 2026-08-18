# アプリウイルススキャン — Android アプリ (APK)

端末にインストールされたアプリを1つずつ検査し、マルウェアが悪用しやすい「危険な権限」を
複数組み合わせて要求しているアプリを検出する、簡易的なウイルス/不審アプリスキャナーです。

パッケージ名: `site.ragdollp.virusscanner`

## できること

1. **スキャン開始** — システムアプリを除く、端末にインストール済みの全アプリを1つずつ
   走査します。各アプリのAPKファイルを実際に解凍(zip展開)し、`AndroidManifest.xml`
   (要求権限)、`classes.dex`、`assets/` 配下などの中身を確認します。
2. **判定** — [`PermissionRules`](app/src/main/java/site/ragdollp/virusscanner/scan/PermissionRules.kt)
   に定義した「危険な権限」リストと照合し、**2つ以上**の危険な権限を同時に要求しているアプリだけを
   検出結果に含めます(1つだけなら正常なアプリでもよくあるケースのため対象外)。
3. **検出結果画面** — スキャン終了後、検出されたアプリの一覧(アイコン・アプリ名・パッケージ名・
   該当した権限/所見)を確認できます。
4. **アンインストール** — 検出結果の各アプリを個別にアンインストール、または
   「すべてアンインストール」で一括アンインストールできます(OS標準の確認ダイアログを
   1件ずつ経由するため、実際の削除には毎回ユーザーの確認操作が必要です)。

## 検査の仕組み

- `PackageManager#getInstalledApplications` で `ApplicationInfo.FLAG_SYSTEM` を持つ
  プリインストールアプリを除外し、走査対象を絞り込みます
  ([`AppScanner.listScanTargets`](app/src/main/java/site/ragdollp/virusscanner/scan/AppScanner.kt))。
- 各アプリのAPK(`ApplicationInfo.sourceDir`)について:
  - `PackageManager#getPackageInfo(..., GET_PERMISSIONS)` でAndroidManifestに書かれた
    要求権限一覧を取得します(バイナリXMLの正式なパーサーはOSに任せるのが安全・確実なため)。
  - [`ApkInspector`](app/src/main/java/site/ragdollp/virusscanner/scan/ApkInspector.kt) が
    APKを`ZipFile`で実際に解凍し、`classes.dex`の数・`assets/`配下に隠されたdexファイルの有無
    (動的コード読み込みによる検知回避の典型的な手口)・既知の不審な文字列
    (`DexClassLoader`、`su`実行など)を確認します。
- 危険な権限が2つ以上ヒットしたアプリのみ、これらの所見とともに検出結果に追加されます。

## 設計上の注意・限界

- これは**シグネチャベースの本格的なウイルス対策エンジンではなく**、権限とAPK構造を見る
  ヒューリスティックな簡易検査です。検出 = 悪意があると断定するものではなく、
  誤検出(正当な多機能アプリが複数の権限を必要とするケース)もあり得ます。
  逆に、危険な権限を使わない巧妙なマルウェアを見逃す可能性もあります。
- 通信を一切行わない完全オフライン動作です(`INTERNET`権限なし)。取得した情報が
  端末外に送信されることはありません。
- Android 11 (API 30) 以降は他アプリの一覧取得に`QUERY_ALL_PACKAGES`権限が必要なため
  マニフェストで宣言しています。本アプリの主機能(全アプリのスキャン)に必須の権限です。
- 通常のアプリ(端末管理者/デバイスオーナーではない)は他アプリをサイレントに削除できないため、
  アンインストールはOS標準の確認ダイアログ(`Intent.ACTION_DELETE`)を都度呼び出します。
  「すべてアンインストール」はこのダイアログを検出件数分キューで順番に表示する実装です。

## ビルド方法

前提: JDK 17+、Android SDK(compileSdk 34 / build-tools 相当)。

```bash
cd android-virus-scanner
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または環境変数 ANDROID_HOME を設定

# デバッグ APK
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk

# 署名済みリリース APK
./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release.apk
```

### 署名鍵について

`virusscanner-release.keystore` は個人配布・提出用の**使い捨て自己署名鍵**です。

| 項目 | 値 |
|---|---|
| keystore | `virusscanner-release.keystore` |
| storePassword | `virusscanner` |
| keyAlias | `virusscanner` |
| keyPassword | `virusscanner` |

> Google Play へ公開する場合は、各自で新しいアップロード鍵を作成し `app/build.gradle` の
> `signingConfigs.release` を差し替えてください。この鍵を本番の秘密鍵として使わないこと。

## プロジェクト構成

```
android-virus-scanner/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/            # Gradle 8.9 wrapper
├── virusscanner-release.keystore        # リリース署名鍵(上記)
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/site/ragdollp/virusscanner/
        │   ├── MainActivity.kt          # スキャン開始画面
        │   ├── ScanActivity.kt          # スキャン進捗画面
        │   ├── DetectionActivity.kt     # 検出結果・アンインストール画面
        │   ├── model/AppScanResult.kt
        │   ├── scan/PermissionRules.kt  # 危険な権限カタログ・しきい値
        │   ├── scan/ApkInspector.kt     # APK(zip)の解凍・中身の走査
        │   ├── scan/AppScanner.kt       # スキャン処理の中核
        │   ├── state/ScanResultsHolder.kt
        │   └── ui/DetectionAdapter.kt
        └── res/                         # レイアウト・アイコン・文言
```

## 動作確認について

この開発環境にはAndroid SDKが用意されておらず、実機/エミュレータでの動作確認や
`./gradlew build` によるコンパイル確認はできていません。Android Studio または
Android SDKをセットアップした環境で `:app:assembleDebug` を実行し、実機での動作確認を
行ってください。
