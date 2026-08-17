# springcat mail

`springcat@ragdollp.site` 用の、Gmail風のWebメールアプリです。

- **フロントエンド**: バニラJS/HTML/CSS（ビルド不要、Gmail風の3ペインUI: サイドバー / メール一覧 / 詳細）
- **バックエンド**: Cloudflare Workers 1本
  - 受信メールは **Cloudflare Email Routing** がこの Worker に転送し、パースして **D1** に保存
  - 送信メールは **[Resend](https://resend.com)** のAPI経由で `springcat@ragdollp.site` から送信
  - ログインはパスワード1つ（本人専用アプリのため）＋ 署名付きセッションCookie

このリポジトリはコード一式です。実際に動かすには、あなたのCloudflare/Resendアカウントに対して以下のセットアップが必要です（私からはデプロイできないので、手順に沿って進めてください）。

## 0. 前提

- `ragdollp.site` のDNSがCloudflareで管理されていること
- Cloudflareアカウント、`wrangler` CLI（`npm install` すると devDependency として入ります）

```bash
cd mail-app
npm install
npx wrangler login
```

## 1. D1データベースを作成

```bash
npx wrangler d1 create springcat-mail-db
```

出力される `database_id` を `wrangler.toml` の `database_id = "REPLACE_WITH_YOUR_D1_DATABASE_ID"` に貼り付けてください。

スキーマを適用:

```bash
npm run db:init:remote
```

## 2. Resendで送信用ドメインを設定

1. https://resend.com でアカウント作成
2. Domains → `ragdollp.site`（もしくは `springcat.ragdollp.site` などのサブドメイン）を追加
3. 提示されるSPF/DKIMのDNSレコードをCloudflareのDNSに追加し、Verifiedになるまで待つ
4. API Keys → 新しいAPIキーを発行（Sending権限）

## 3. Secretsを設定

```bash
npx wrangler secret put APP_PASSWORD       # ログインパスワード（自分で決める）
npx wrangler secret put SESSION_SECRET     # ランダムな文字列（例: openssl rand -hex 32）
npx wrangler secret put RESEND_API_KEY     # 手順2で発行したAPIキー
```

## 4. デプロイ

```bash
npm run deploy
```

デプロイ後に表示されるURL（例: `springcat-mail.<your-subdomain>.workers.dev`）、または独自ドメインを割り当てたい場合はCloudflareダッシュボード → Workers & Pages → 対象Worker → Settings → Domains & Routes から `mail.ragdollp.site` 等を追加してください。

## 5. 受信メールをこのWorkerにルーティング

Cloudflareダッシュボード → 対象ドメイン（`ragdollp.site`） → **Email** → **Email Routing** を開き:

1. Email Routingが無効なら有効化（MXレコードが自動追加されます）
2. **Routing rules** → Create rule
   - Match: `springcat@ragdollp.site`
   - Action: **Send to a Worker** → `springcat-mail` を選択
3. 保存

これで `springcat@ragdollp.site` 宛のメールがこのWorkerの `email()` ハンドラに渡り、D1に保存されて受信トレイに表示されます。

## 6. ログイン

デプロイしたURLにアクセスし、手順3で設定した `APP_PASSWORD` でログインすれば、Gmailのように受信トレイ・スター・検索・返信（新規作成として送信）・ゴミ箱が使えます。

## ローカル開発

```bash
npm run dev
```

`wrangler dev` はローカルでD1のローカルレプリカを使います（`npm run db:init` でローカルDBにもスキーマを適用してください）。ローカルでは実際のメール受信は発生しません（Email Routingはデプロイ済みWorkerにのみ届きます）。送信はResendの本番APIを叩くので、テスト時はご注意ください。

## ディレクトリ構成

```
mail-app/
  wrangler.toml       Workerの設定（D1バインディング、静的アセット配信など）
  schema.sql           D1のテーブル定義
  src/
    index.ts            fetch/emailハンドラのエントリポイント
    auth.ts              パスワード認証・セッションCookie
    api.ts                REST API（一覧/詳細/スター/削除/送信）
    email.ts               受信メールのパース→D1保存
  public/
    index.html, style.css, app.js   Gmail風フロントエンド
```

## 制限事項・今後の拡張候補

- 添付ファイルは未対応（本文テキスト/HTMLのみ保存）
- スレッド表示は未実装（`thread_id` は保存しているので拡張可能）
- 複数ユーザー・複数メールアドレスには非対応（1アカウント専用）
