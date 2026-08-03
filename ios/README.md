# Block Destroy — iOS アプリ

`android/` にある Android 版 (WKWebView ならぬ WebView ラッパー) と同じ設計思想で、
ゲーム本体 (`Resources/game.html`) を **WKWebView** に読み込んで動かす iOS アプリです。
完全オフラインでプレイできます。

## なぜ WKWebView 方式か

Android 版と同じ理由です。46,000 行超・2.4MB の単一 HTML として実装されたゲームを
Swift へ 1:1 移植するのは現実的ではないため、ゲームの全機能をそのまま保てる
WKWebView ラッパー方式を採用しています。

## 構成

```
ios/
└── BlockDestroy.xcodeproj/     # Xcode プロジェクト (Xcode 15 / iOS 15+)
    └── project.pbxproj
└── BlockDestroy/
    ├── AppDelegate.swift        # UIApplicationDelegate + 通知デリゲート登録
    ├── SceneDelegate.swift      # ルート ViewController のセットアップ
    ├── ViewController.swift     # WKWebView 本体 + JS↔Native ブリッジ
    ├── DailyNotify.swift        # デイリーボーナス通知 (UNUserNotificationCenter)
    ├── MediaSaver.swift         # スクショ/録画の保存 (写真アプリ / Files)
    ├── ScreenRecorder.swift     # 画面録画 (ReplayKit)
    ├── Info.plist
    ├── Assets.xcassets/         # アプリアイコン (Android 版アイコンを流用)
    └── Resources/
        ├── game.html            # ゲーム本体 (android/app/.../assets/game.html と同一)
        └── html2canvas.min.js
```

## ビルド方法

macOS + Xcode 15 以降が必要です (このリポジトリの開発コンテナには Xcode がないため、
実機/シミュレータでのビルド確認は各自の Mac 上で行ってください)。

```bash
open ios/BlockDestroy.xcodeproj
```

Xcode 上で `Signing & Capabilities` からご自身の Apple Developer Team を選択して
実行してください (`PRODUCT_BUNDLE_IDENTIFIER` は Android 版と同じ `site.ragdollp.blockdestory`
にしていますが、実機配布する場合は各自の Bundle ID に変更してください)。

- Bundle ID: `site.ragdollp.blockdestory`
- Deployment Target: iOS 15.0
- Version 1.0 (Build 1)

## Android 版との対応関係

| 機能 | Android | iOS |
|---|---|---|
| UserAgent マーカー (簡易ブラウザ判定回避) | UserAgent 末尾に `BlockdestoryApp/1` を手動付与 | `WKWebViewConfiguration.applicationNameForUserAgent` で同じ文字列を自動付与 |
| デスクトップ表示固定 | `onPageStarted/onPageFinished` で viewport 強制 | `WKUserScript` (atDocumentStart) + `DOMContentLoaded` で viewport 強制 |
| スクショ/エクスポート保存 | `AndroidSaver` → `MediaStore` | `native` メッセージハンドラ → `PHPhotoLibrary` (画像/動画) / Documents (その他) |
| 画面録画 | `WebViewRecorder` (WebView 直接キャプチャ) | `ReplayKit` (`RPScreenRecorder`, アプリ内録画) |
| 端末固有ID | `Settings.Secure.ANDROID_ID` | `UIDevice.identifierForVendor` |
| デイリーボーナス通知 | `AlarmManager` + `NotificationManager` | `UNCalendarNotificationTrigger` (repeats: true) |
| イベント告知 (FCM プッシュ) | Firebase Cloud Messaging | 未実装 (Firebase iOS SDK 導入が必要。追加する場合は SwiftPM で `firebase-ios-sdk` を追加し `EventMessagingService.java` 相当の `UNUserNotificationCenterDelegate`/APNs 連携を実装してください) |

## オフライン動作について

- ゲーム HTML はアプリバンドルに同梱し、`Bundle.main` から `loadFileURL` で読み込むためネットワーク不要でプレイできます。
- セーブデータは WKWebView の `localStorage` に保存されます。
- Firebase の世界ランキング・広告・reCAPTCHA などのオンライン機能は、通信できない場合は
  静かに失敗するだけで、コアのゲームプレイには影響しません(元コードが try/catch で握りつぶす設計)。
- `html2canvas.min.js` はオフライン用に同梱していますが、Android 版のようにゲームが読み込む
  CDN リクエストを横取りする API が WKWebView には無いため、この同梱ファイル自体は現状未使用です
  (該当のエクスポート機能はオンライン時のみ CDN から読み込まれます)。

## 権限

- `NSPhotoLibraryAddUsageDescription`: スクリーンショット/録画を写真アプリに保存するため
- 通知権限はゲーム内の「デイリー通知」設定 ON 操作をトリガーに、その場でリクエストします (起動時には要求しません)
- 画面録画は初回のみ ReplayKit の標準確認ダイアログが表示されます

## ゲーム HTML を更新したら

`BlockDestroy/Resources/game.html` を新しい HTML で置き換えて再ビルドするだけです
(アプリ側のコード変更は不要)。ただし新しい HTML でも `BlockdestoryApp` UserAgent 判定フックが
維持されていることを確認してください。
