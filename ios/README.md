# Block Destroy — iOS 版 (WKWebView ラッパー)

Android 版と**同じゲーム本体** (`android/app/game-src/game.html`) を、iOS の
`WKWebView` で動かすラッパーアプリです。ゲームのロジック・セーブ（localStorage）・
実績・全モードは Android とまったく同じものが動きます。

```
ios/
├─ BlockDestroy/
│  ├─ project.yml                    ← XcodeGen プロジェクト定義
│  └─ BlockDestroy/
│     ├─ AppDelegate.swift
│     ├─ SceneDelegate.swift
│     ├─ GameViewController.swift    ← WKWebView + game.enc 復号 (中核)
│     ├─ AdConfig.swift              ← iOS 用 AdMob ID
│     ├─ Info.plist
│     ├─ Assets.xcassets/            ← アプリアイコン (1024px を要追加)
│     └─ game.enc                    ← 暗号化したゲーム本体
└─ tools/
   └─ encrypt-game.js               ← game.enc の再生成スクリプト
```

> ⚠️ **重要**: このプロジェクトは Linux 上で作成しており、**Xcode でのコンパイル確認は
> できていません**。Mac + Xcode で開いてビルドし、もしエラーが出たら教えてください
> （その場で直します）。ロジックはシンプルなので、通れば普通に動く想定です。

---

## 🛠 ビルド方法（要 Mac + Xcode）

iOS アプリのビルド・署名・実機/シミュレータ実行には **macOS と Xcode が必須**です。
（Windows/Linux だけではビルドできません。相方さんの Mac があればそこで。）

### 方法A: XcodeGen（推奨・確実）

```bash
brew install xcodegen          # 初回のみ
cd ios/BlockDestroy
xcodegen generate              # BlockDestroy.xcodeproj を生成
open BlockDestroy.xcodeproj    # Xcode で開く
```

Xcode で **Signing & Capabilities → Team** に自分の Apple ID / Team を選び、
実機かシミュレータを選んで ▶︎ Run。

### 方法B: 手動で Xcode プロジェクトを作る

1. Xcode →「App」→ Interface: **Storyboard 以外は既定**、Language: Swift で新規作成
   （Bundle ID: `site.ragdollp.blockdestory`）。
2. 自動生成された `ContentView`/`ViewController`/`Main.storyboard` を削除。
3. `BlockDestroy/BlockDestroy/` の **5つの .swift**・**Info.plist**・**game.enc**・
   **Assets.xcassets** をプロジェクトに **Add Files**（"Copy items if needed" ON、
   game.enc は **Target membership** にチェック＝バンドルに同梱されるよう）。
4. Target の Build Settings で Info.plist をこのファイルに差し替え、Run。

---

## 🔄 ゲームを更新したら（game.enc の作り直し）

`game.html` を更新したら、iOS 同梱の `game.enc` も作り直します（Android と同じ鍵）:

```bash
node ios/tools/encrypt-game.js
```

これで `ios/BlockDestroy/BlockDestroy/game.enc` が最新の game.html から再生成されます。
（暗号方式・パスフレーズは Android の `build.gradle` と完全一致させてあります。）

---

## ✅ 動くもの / ⏸ まだのもの（v1）

**動く:**
- ゲーム本体すべて（全モード・ミニゲーム・実績・図鑑）
- **セーブデータ**（localStorage、端末に永続化）
- 画面消灯防止、縦向き固定、デスクトップUI固定（Android と同挙動）
- オンライン時のランキング/コメント（Firebase。ネット接続時）

**まだ（Android のネイティブ機能は iOS では未実装。ゲーム側が自動でフォールバック）:**
- 📢 AdMob 広告（下記手順で後付け可能。**v1 は広告なし**）
- 🔔 ローカル通知（デイリー）／📸 スクショ・録画のネイティブ保存
- 💳 アプリ内課金（StoreKit 版が別途必要）

これらは `if (window.AndroidXxx)` の判定で**無ければ自動的にスキップ**されるので、
未実装でもクラッシュせずゲームは普通に遊べます。

---

## 📢 広告 (AdMob) を有効にするには

iOS 用の ID は設定済みです（**Android とは別 ID**）:

| 用途 | ID |
|---|---|
| アプリ ID | `ca-app-pub-8357981710510236~6093403070`（`Info.plist` の `GADApplicationIdentifier`）|
| ネイティブ広告ユニット | `ca-app-pub-8357981710510236/1056698817`（`AdConfig.swift`）|

有効化の手順（ざっくり）:
1. **Google Mobile Ads SDK** を追加（Swift Package Manager:
   `https://github.com/googleads/swift-package-manager-google-mobile-ads`）。
2. `Info.plist` の `SKAdNetworkItems` を **Google 公式の全リスト**に差し替え。
3. アプリ起動時に `MobileAds.shared.start()` を呼ぶ。
4. `AdConfig.nativeAdUnitID` でネイティブ広告をロードし、`GameViewController` の
   WebView 下などに表示（Android の `setupNativeAd` 相当を Swift で実装）。

> SDK 導入まではゲームは広告なしで完全動作します。急ぐものではないので、
> まず「ちゃんと動く iOS 版」を確認してから広告を足すのがおすすめです。

---

## 🎨 アプリアイコン

`Assets.xcassets/AppIcon.appiconset` は **1024×1024 の PNG が未設定**です
（今はアイコンなしでビルドは通ります）。提出前に:
`dist/playstore/icon-512.png` を 1024px に拡大した PNG を AppIcon にドロップしてください。

---

## ⚠️ 公開まわりの注意（大事)

- App Store への公開には **Apple Developer Program（年 $99・成人の本人確認/支払いが必要）** が要ります。
- **課金・広告収益・デベロッパー口座**は規約上ふつう**成人名義**が前提です。
  お金とアカウント周りは、**信頼できる大人（できれば家族）に必ず共有・相談**してから進めてください。
- 署名証明書・プロビジョニング・（もし作るなら）配布用の鍵類は**手元にも必ずコピー**を残すこと。

---

作った本人（Linux 環境）ではビルド確認ができていないので、Xcode で最初に通すときに
出たエラーはそのまま貼ってくれれば直します。まずは**シミュレータで起動 → タイトルが出て
遊べる**ところまで一緒に確認しましょう。
