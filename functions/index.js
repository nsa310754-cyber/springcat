const { onCall, onRequest, HttpsError } = require('firebase-functions/v2/https');
const { defineSecret } = require('firebase-functions/params');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const Stripe = require('stripe');

initializeApp();
const db = getFirestore();

const stripeSecretKey = defineSecret('STRIPE_SECRET_KEY');
const stripeWebhookSecret = defineSecret('STRIPE_WEBHOOK_SECRET');

// 金額・付与量はここがサーバー側の唯一の正とする。クライアントからの申告は信用しない。
const PRODUCTS = {
  gems_100:        { type: 'gems',         amount: 100,   jpy: 150,   label: 'ジェム 100' },
  gems_525:        { type: 'gems',         amount: 525,   jpy: 700,   label: 'ジェム 500+25' },
  gems_1300:       { type: 'gems',         amount: 1300,  jpy: 1500,  label: 'ジェム 1000+300' },
  gems_14000:      { type: 'gems',         amount: 14000, jpy: 16000, label: 'ジェム 10000+4000' },
  vs_pass_week:    { type: 'vs_pass',      matches: 5,    days: 7,    jpy: 1500, label: 'VSオンライン5回パック(1週間)' },
  premium_monthly: { type: 'subscription', priceId: 'price_1U0ycY16A9MqGL90z7nnqcwD', label: 'プレミアム会員(月額)' },
};

// Checkout の戻り先として許可するオリジン (オープンリダイレクト防止)
const ALLOWED_RETURN_ORIGINS = [
  'https://springcat.ragdollp.site',
  'https://appassets.androidplatform.net',
];

function safeReturnUrl(url, suffix) {
  try {
    const u = new URL(url);
    if (!ALLOWED_RETURN_ORIGINS.includes(u.origin)) throw new Error('origin not allowed');
    u.search += (u.search ? '&' : '?') + suffix;
    return u.toString();
  } catch {
    return `${ALLOWED_RETURN_ORIGINS[0]}/?${suffix}`;
  }
}

/**
 * クライアント (Firebase Auth でサインイン済み) が呼び出し、
 * 指定した sku の Stripe Checkout Session URL を受け取る。
 * data: { sku: string, successUrl?: string, cancelUrl?: string }
 * returns: { url: string }
 */
exports.createCheckoutSession = onCall({ secrets: [stripeSecretKey] }, async (request) => {
  if (!request.auth) {
    throw new HttpsError('unauthenticated', 'ログインが必要です');
  }
  const uid = request.auth.uid;
  const sku = request.data && request.data.sku;
  const product = PRODUCTS[sku];
  if (!product) {
    throw new HttpsError('invalid-argument', '不明な商品です');
  }

  const stripe = new Stripe(stripeSecretKey.value());
  const isSubscription = product.type === 'subscription';

  const session = await stripe.checkout.sessions.create({
    mode: isSubscription ? 'subscription' : 'payment',
    payment_method_types: ['card'],
    line_items: [
      product.priceId
        ? { price: product.priceId, quantity: 1 }
        : {
            price_data: {
              currency: 'jpy',
              product_data: { name: product.label },
              unit_amount: product.jpy,
              ...(isSubscription ? { recurring: { interval: 'month' } } : {}),
            },
            quantity: 1,
          },
    ],
    metadata: { uid, sku },
    // サブスクは webhook (invoice.paid 等) で uid を拾えるよう subscription 側にも metadata を付与
    ...(isSubscription ? { subscription_data: { metadata: { uid, sku } } } : {}),
    success_url: safeReturnUrl(request.data.successUrl, 'checkout=success'),
    cancel_url: safeReturnUrl(request.data.cancelUrl, 'checkout=cancel'),
  });

  return { url: session.url };
});

/**
 * Stripe からの Webhook。署名を検証した上で users/{uid} に
 * ジェム・VSパスの付与、またはプレミアム会員の有効/無効を反映する。
 * StripeダッシュボードでこのURLをエンドポイントとして登録し、
 * 以下のイベントをリッスンすること:
 *   checkout.session.completed, invoice.paid,
 *   invoice.payment_failed, customer.subscription.deleted
 */
exports.stripeWebhook = onRequest({ secrets: [stripeSecretKey, stripeWebhookSecret] }, async (req, res) => {
  const stripe = new Stripe(stripeSecretKey.value());

  let event;
  try {
    event = stripe.webhooks.constructEvent(
      req.rawBody,
      req.headers['stripe-signature'],
      stripeWebhookSecret.value()
    );
  } catch (err) {
    console.error('signature verification failed', err.message);
    res.status(400).send(`Webhook Error: ${err.message}`);
    return;
  }

  const userRef = (uid) => db.collection('users').doc(uid);

  // event.id をキーに冪等性を保証しつつ user ドキュメントを更新する。
  // Stripeの再送(同一event.id)で二重付与・二重反映が起きないようにする。
  async function applyOnce(uid, mutate) {
    if (!uid) return;
    const eventRef = db.collection('processedStripeEvents').doc(event.id);
    await db.runTransaction(async (tx) => {
      const eventSnap = await tx.get(eventRef);
      if (eventSnap.exists) return;

      const uRef = userRef(uid);
      const userSnap = await tx.get(uRef);
      const userData = userSnap.exists ? userSnap.data() : {};

      mutate(tx, uRef, userData);

      tx.set(eventRef, { uid, type: event.type, processedAt: FieldValue.serverTimestamp() });
    });
  }

  try {
    switch (event.type) {
      // 決済完了 (単発購入 or サブスク初回)
      case 'checkout.session.completed': {
        const session = event.data.object;
        const uid = (session.metadata && session.metadata.uid) || session.client_reference_id;
        const sku = session.metadata && session.metadata.sku;
        const product = PRODUCTS[sku];
        if (!uid || !product) break;

        if (product.type === 'subscription') {
          const sub = await stripe.subscriptions.retrieve(session.subscription);
          await applyOnce(uid, (tx, uRef) => {
            tx.set(uRef, { premium: { active: true, periodEnd: sub.current_period_end * 1000 } }, { merge: true });
          });
        } else if (product.type === 'gems') {
          await applyOnce(uid, (tx, uRef, userData) => {
            const current = userData.gems || 0;
            tx.set(uRef, { gems: current + product.amount }, { merge: true });
          });
        } else if (product.type === 'vs_pass') {
          await applyOnce(uid, (tx, uRef, userData) => {
            const now = Date.now();
            const existing = userData.vsPass;
            const stillActive = !!(existing && existing.expiresAt && existing.expiresAt.toMillis() > now);
            const baseTime = stillActive ? existing.expiresAt.toMillis() : now;
            const expiresAt = new Date(baseTime + product.days * 24 * 60 * 60 * 1000);
            const matchesRemaining = (stillActive ? (existing.matchesRemaining || 0) : 0) + product.matches;
            tx.set(uRef, { vsPass: { matchesRemaining, expiresAt } }, { merge: true });
          });
        }
        break;
      }

      // 毎月の自動課金成功 → プレミアム期限を延長
      case 'invoice.paid': {
        const invoice = event.data.object;
        if (!invoice.subscription) break;
        const sub = await stripe.subscriptions.retrieve(invoice.subscription);
        const uid = sub.metadata && sub.metadata.uid;
        if (!uid) break;
        await applyOnce(uid, (tx, uRef) => {
          tx.set(uRef, { premium: { active: true, periodEnd: sub.current_period_end * 1000 } }, { merge: true });
        });
        break;
      }

      // 支払い失敗 / 解約 → プレミアムを無効化
      case 'invoice.payment_failed':
      case 'customer.subscription.deleted': {
        const obj = event.data.object;
        const subId = obj.object === 'subscription' ? obj.id : obj.subscription;
        if (!subId) break;
        const sub = await stripe.subscriptions.retrieve(subId);
        const uid = sub.metadata && sub.metadata.uid;
        if (!uid) break;
        await applyOnce(uid, (tx, uRef) => {
          tx.set(uRef, { premium: { active: false } }, { merge: true });
        });
        break;
      }
    }
    res.json({ received: true });
  } catch (err) {
    console.error('webhook handler error:', event.type, err);
    res.status(500).send('handler error');
  }
});
