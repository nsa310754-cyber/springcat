# 課金バックエンド (Firebase Functions + Stripe)

`sk_live` はどのファイルにも書きません。すべて Firebase の Secret 機能に保存し、
関数からは `defineSecret` 経由でのみ参照します。以下のコマンドは **あなた自身の
PCで、あなたのFirebase/Stripeアカウントにログインした状態**で実行してください
（この開発コンテナはあなたのアカウントにログインしていないため代行できません）。

## 前提条件

- Firebaseプロジェクト `blockdestory-499622` が **Blazeプラン(従量課金)** になっていること
  （2nd gen Cloud Functions と Stripeへの外部HTTP通信に必須）
- Firebase Authentication で **匿名ログイン(Anonymous)** を有効化しておくこと
  （Console → Authentication → Sign-in method → 匿名）
- Firestore (Native mode) が有効になっていること

## 販売商品 (`functions/index.js` の `PRODUCTS` で定義・変更可)

| sku | 内容 | 価格 |
|---|---|---|
| `gems_100` | ジェム 100 | ¥150 |
| `gems_525` | ジェム 500+25 | ¥700 |
| `gems_1300` | ジェム 1000+300 | ¥1,500 |
| `gems_14000` | ジェム 10000+4000 | ¥16,000 |
| `vs_pass_week` | VSオンライン5回パック(1週間) | ¥1,500 |
| `premium_monthly` | プレミアム会員(月額・自動更新) | ¥2,500/月 |

金額・付与量はクライアントから送らせず、この表のみを正としています。
Stripeダッシュボード側の商品登録は不要です（Checkout Session作成時に動的に生成しています）。

## デプロイ手順

```bash
cd functions
npm install

firebase login          # 未ログインなら
firebase use blockdestory-499622

# 1. まず STRIPE_SECRET_KEY だけ設定してデプロイ (関数のURLを得るため)
firebase functions:secrets:set STRIPE_SECRET_KEY
# → プロンプトで sk_live_... を貼り付け (シェル履歴には残りません)

firebase deploy --only functions,firestore:rules
```

デプロイ後に表示される `stripeWebhook` のURL（例:
`https://asia-northeast1-blockdestory-499622.cloudfunctions.net/stripeWebhook`）
を確認してください。

```bash
# 2. Stripeダッシュボード → 開発者 → Webhook → エンドポイントを追加
#    URL: 上記の stripeWebhook のURL
#    イベント: checkout.session.completed, invoice.paid,
#              invoice.payment_failed, customer.subscription.deleted
#    (premium_monthly を追加した場合は後の3つも忘れず選択すること)
#    作成後に表示される「署名シークレット」(whsec_...) をコピー

firebase functions:secrets:set STRIPE_WEBHOOK_SECRET
# → whsec_... を貼り付け

# 3. Webhookシークレットを反映するため再デプロイ
firebase deploy --only functions
```

## フロントエンド(WebView/game.html側)との連携仕様

別チャットで実装中のフロント側は、以下のFirebase Client SDK呼び出しだけで
連携できます（Firestoreへの直接書き込みは禁止しているので、残高付与は必ず
Webhook経由になります）。

1. Firebase Auth で匿名サインイン（`signInAnonymously`）してUIDを確保
2. `httpsCallable(functions, 'createCheckoutSession')({ sku, successUrl, cancelUrl })`
   を呼ぶ → `{ url }` が返るので、その `url` にWebViewを遷移させる
   - `successUrl` / `cancelUrl` は `https://springcat.ragdollp.site` または
     `https://appassets.androidplatform.net` 配下のみ許可（それ以外は無視して
     デフォルトURLにフォールバック）
   - `sku` は上記商品表のいずれか
3. 決済完了後、Firestoreの `users/{uid}` ドキュメントを購読(`onSnapshot`)すると
   `gems`（累計ジェム数）、`vsPass: { matchesRemaining, expiresAt }`、
   `premium: { active, periodEnd }`（`premium_monthly`加入者。解約/支払い
   失敗で自動的に`active: false`になる）がWebhook側で自動更新される

## 動作確認

Stripe CLIでローカルからwebhookをテストできます:

```bash
stripe listen --forward-to https://<region>-blockdestory-499622.cloudfunctions.net/stripeWebhook
stripe trigger checkout.session.completed
```

`sk_live` はテストに使わず、動作確認は `sk_test_...` のテストキーで行うことを
強く推奨します（Secretを一時的にテストキーに差し替え → 確認後に本番キーへ戻す）。
