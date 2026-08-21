# Block Destroy — Expo版 (スマホだけでIPAをビルドする用)

Android版と同じゲーム本体 (`android/app/game-src/game.html` のコピー、
`mobile/assets/game/game.html`) を `react-native-webview` で表示するだけの
薄いラッパーです。PC/Mac無しで、**EAS Build(Expoのクラウドビルドサービス)**
を使ってiOS用IPAを作ることを目的にしています。

## 何が必要か

- **Expoアカウント**(無料。https://expo.dev で作成)
- **Apple Developer Program 登録**(年$99。実機/配布用のIPA署名にはこれが必須。
  EAS BuildがMac無しで署名を代行してくれますが、Apple Developer登録自体は
  代行できません。無料のApple IDだけではストア/実機配布用IPAは作れません)
- ターミナルが使える環境1つ(スマホからでもOK):
  - **GitHub Codespaces**(推奨。スマホのブラウザだけで本格的なLinuxターミナルが開ける)
  - もしくは Termux(Android) / iSH(iPhone)

## 手順

### 1. Codespacesを開く(スマホのブラウザから)

このリポジトリのGitHubページ → `Code` → `Codespaces` タブ → `Create codespace`。
ブラウザ内にVS Code+ターミナルが開きます。

### 2. 依存関係をインストール

```bash
cd mobile
npm install
```

### 3. EAS CLIでログイン

```bash
npx eas-cli login
```
Expoアカウントのメール/パスワードを入力(初回はexpo.devでアカウント作成)。

### 4. プロジェクトをEASに登録(初回のみ)

```bash
npx eas-cli build:configure
```
既に `eas.json` は用意済みなので、聞かれたら iOS を選ぶだけでOK。

### 5. ビルド実行

実機に配布したい(TestFlight無しで直接インストール)場合:
```bash
npx eas-cli build --platform ios --profile preview
```

App Store / TestFlight提出用なら:
```bash
npx eas-cli build --platform ios --profile production
```

初回は対話式でApple IDのログインを求められます。証明書・プロビジョニング
プロファイルは **EASが自動生成**するので、Mac・Xcode・手元の証明書は一切不要です。
(`preview`プロファイルで実機に直接入れる場合は、その場で実機のUDID登録も
案内されます。案内されたURLをその実機のSafariで開くだけです。)

### 6. ビルド完了を待つ

クラウド上のMacで10〜20分程度でビルドされます。終わるとターミナルに
ダウンロードリンクが表示されます。同じリンクは https://expo.dev のダッシュボードからも確認可能。

- `preview`プロファイルの成果物 → `.ipa`ファイル(実機に直接インストール、
  要: 事前に登録したUDIDの実機であること)
- `production`プロファイルの成果物 → App Store Connectへ`eas submit -p ios`で
  そのまま提出可能

## game.html を更新したら

Android版のゲーム本体を更新した場合、こちらにもコピーし直してください:
```bash
cp ../android/app/game-src/game.html assets/game/game.html
```

## 現状の制限

- ネイティブ機能(AdMob広告・課金・プッシュ通知)は未実装です。ゲーム側が
  `if (window.AndroidXxx)` 判定で自動的にスキップするため、無くてもクラッシュせず
  普通に遊べます(Swift版iOSラッパーと同じ考え方)。
- アプリアイコンは `dist/playstore/icon-512.png` を流用しています(512×512)。
  App Store提出前には `assets/icon.png` を1024×1024・アルファチャンネル無しの
  PNGに差し替えてください。
- Bundle ID は Android/Swift版と同じ `site.ragdollp.blockdestory` に揃えてあります。
