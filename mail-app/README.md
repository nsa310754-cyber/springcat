# springcat mail

`springcat@ragdollp.site` 用の、Gmail風のWebメールアプリです。

- **フロントエンド**: バニラJS/HTML/CSS（ビルド不要、Gmail風の3ペインUI: サイドバー / メール一覧 / 詳細）
- **バックエンド**: Cloudflare Workers 1本
  - 受信メールは **Cloudflare Email Routing** がこの Worker に転送し、パースして **D1** に保存
  - 送信メールは **[Resend](https://resend.com)** のAPI経由で `springcat@ragdollp.site` から送信

## ⚠️ 認証なし（重要）

このアプリには**ログイン機能がありません**。デプロイ先のURLを知っていれば誰でも受信トレイの中身（個人的な内容を含むメール本文）を読めます。意図的にこの構成にしていますが、URLを他人に共有しないよう注意してください。

## 現在のデプロイ状況

- URL: `https://springcat-mail.<account>.workers.dev`
- D1データベース: 作成・スキーマ適用済み
- Email Routing: `springcat@ragdollp.site` → このWorker宛に設定済み（従来のGmail自動転送は停止しています）
- 送信(Resend): **ドメイン未認証のため送信は失敗します**。下記「送信を有効にする」を参照

## 送信を有効にする（Resendドメイン認証）

1. https://resend.com/domains で `ragdollp.site`（もしくは `springcat.ragdollp.site`）を追加
2. 表示されるSPF/DKIMのDNSレコードをCloudflareのDNSに追加
3. Verified になれば `/api/send` からの送信が成功するようになります

## ゼロから構築し直す場合

```bash
cd mail-app
npm install
npx wrangler login
```

1. D1データベース作成: `npx wrangler d1 create springcat-mail-db` → 出力された `database_id` を `wrangler.toml` に反映
2. スキーマ適用: `npm run db:init:remote`
3. Secrets設定: `npx wrangler secret put RESEND_API_KEY`
4. デプロイ: `npm run deploy`
5. Cloudflareダッシュボード → 対象ドメイン → **Email** → **Email Routing** → Routing rules で
   `springcat@ragdollp.site` → **Send to a Worker** → `springcat-mail` を設定

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
    env.ts                Env型定義
    api.ts                 REST API（一覧/詳細/スター/削除/送信）
    email.ts                受信メールのパース→D1保存
  public/
    index.html, style.css, app.js   Gmail風フロントエンド
```

## 制限事項・今後の拡張候補

- 認証なし（上記参照）
- 添付ファイルは未対応(本文テキスト/HTMLのみ保存)
- スレッド表示は未実装(`thread_id` は保存しているので拡張可能)
- 複数ユーザー・複数メールアドレスには非対応(1アカウント専用)
