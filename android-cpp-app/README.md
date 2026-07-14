# Springcat Native (C++ on Android)

Android Studio の「**Native C++**」テンプレート相当の最小アプリです。
画面表示に出る文字列は **C++（`app/src/main/cpp/native-lib.cpp`）** が生成し、
Kotlin の `MainActivity` が **JNI** 経由でそれを呼び出しています。
ネイティブコードは **CMake** でビルドされ、`libspringcat.so` として APK に同梱されます。

## 構成

```
android-cpp-app/
├── settings.gradle.kts
├── build.gradle.kts            # プラグイン/バージョン定義
├── gradle.properties
└── app/
    ├── build.gradle.kts        # CMake を Gradle に接続 (externalNativeBuild)
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/
        │   ├── CMakeLists.txt   # C++ を libspringcat.so にビルド
        │   └── native-lib.cpp   # ← 実際の処理はここ (C++)
        ├── java/com/example/springcat/MainActivity.kt  # JNI で C++ を呼ぶ
        └── res/                 # レイアウト・文字列・テーマ
```

C++ 側を書き換えたいときは `native-lib.cpp` を編集してください。
処理の中心は C++ にあり、Kotlin は「起動して結果を表示する」だけの薄い層です。

## Android Studio でのビルド手順（APK を作って端末に入れる）

1. Android Studio で **File > Open** から `android-cpp-app` フォルダを開く
2. 初回は必要なコンポーネントを促されるのでインストール:
   - **SDK**（compileSdk 34）
   - **NDK** と **CMake**（`SDK Manager > SDK Tools` から）
3. Gradle Sync が終わるのを待つ
4. USB デバッグを有効にした Android 端末を接続（または AVD エミュレータを起動）
5. ツールバーの ▶ **Run 'app'** で、ビルド → 端末へインストール → 起動

画面に `Hello from C++ 🐱 (native-lib.cpp)` と表示されれば、C++ コードが
端末上で動いています。

### 配布用 APK を書き出す場合

- **Build > Build Bundle(s) / APK(s) > Build APK(s)**
- 生成物: `app/build/outputs/apk/debug/app-debug.apk`
- この APK を端末に転送してインストールすれば「ダウンロードして使う」形になります
  （提供元不明アプリのインストール許可が必要）

## 補足

- このリポジトリの環境には Android SDK/NDK が無いため、雛形の作成のみ行っています。
  実際のビルド（APK 生成）は Android Studio 側で行ってください。
- 対応 ABI は `arm64-v8a` / `armeabi-v7a` / `x86_64`（実機の多くとエミュレータをカバー）。
