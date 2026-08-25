# 公開手順（Cloudflare バックエンド方式）

キーを **Cloudflare の環境変数（Secret）** に保存し、公開ページ・GitHub・アプリのどこにもキーを含めない安全な構成です。

## 仕組み

```
ブラウザ / アプリ
   └─ https://springcat.ragdollp.site/eigyo/   ← 公開ページ（キーを持たない）
         └─ 同一ドメインの /api/gemini・/api/x-search を呼ぶ
               └─ Cloudflare Pages Functions（functions/api/*.js）
                     └─ 環境変数のキーで Gemini / X API を呼ぶ ← キーはここだけ
```

- 公開ページ: `eigyo/index.html` → `https://springcat.ragdollp.site/eigyo/`
- バックエンド: `functions/api/gemini.js`, `functions/api/x-search.js`
- キーは **Cloudflare のダッシュボードにのみ**保存（リポジトリにもページにも入らない）

## 公開までの手順（3つ）

### 1. main ブランチへ反映（デプロイ）
このブランチ（`claude/delivery-sales-target-tool-d6hdqw`）を **main にマージ**してください。
Cloudflare Pages が push を検知して自動デプロイします。
> 本番反映は main へのマージが必要です（プレビューは各ブランチの `*.pages.dev` URL でも確認できます）。

### 2. Cloudflare に環境変数（Secret）を設定
Cloudflare ダッシュボード → 対象の **Pages プロジェクト** → **Settings → Environment variables**
に以下を **Production（と必要なら Preview）** へ追加し、**Encrypt（Secret化）** して保存:

| 変数名 | 値 |
|---|---|
| `GEMINI_KEY` | あなたの Gemini API キー |
| `X_API_KEY` | X の API Key（consumer key） |
| `X_API_SECRET` | X の API Secret（consumer secret） |
| `GEMINI_MODEL` | 任意。既定 `gemini-3.6-flash` |

> 変更後は再デプロイ（またはリトライ）で反映されます。

### 3. 動作確認
`https://springcat.ragdollp.site/eigyo/` を開き、上部が「バックエンド: 接続」になっていれば成功。
「検索・抽出を実行」で候補抽出 → 「DM生成」まで動きます。

## セキュリティ上の注意
- キーは Cloudflare の Secret に置くため、ページ・GitHub・APK には**含まれません**。
- Cloudflare の環境変数は暗号化保存され、クライアントには渡りません。
- 万一に備え、Gemini / X ともに **利用上限（spending limit）** を設定してください。
- これまでチャットで共有したキーは、公開前に **再発行（Regenerate）** を推奨します。

## ローカルで試す（任意）
Cloudflare の `wrangler` があれば、キーを与えてローカル起動できます:

```bash
npm i -g wrangler
cd <repo-root>
GEMINI_KEY=... X_API_KEY=... X_API_SECRET=... \
  wrangler pages dev . --compatibility-date=2024-01-01
# → http://localhost:8788/eigyo/
```
