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
  gems_100:     { type: 'gems',    amount: 100,   jpy: 150,   label: 'ジェム 100' },
  gems_525:     { type: 'gems',    amount: 525,   jpy: 700,   label: 'ジェム 500+25' },
  gems_1300:    { type: 'gems',    amount: 1300,  jpy: 1500,  label: 'ジェム 1000+300' },
  gems_14000:   { type: 'gems',    amount: 14000, jpy: 16000, label: 'ジェム 10000+4000' },
  vs_pass_week: { type: 'vs_pass', matches: 5,    days: 7,    jpy: 1500,  label: 'VSオンライン5回パック(1週間)' },
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

  const session = await stripe.checkout.sessions.create({
    mode: 'payment',
    payment_method_types: ['card'],
    line_items: [{
      price_data: {
        currency: 'jpy',
        product_data: { name: product.label },
        unit_amount: product.jpy,
      },
      quantity: 1,
    }],
    metadata: { uid, sku },
    success_url: safeReturnUrl(request.data.successUrl, 'checkout=success'),
    cancel_url: safeReturnUrl(request.data.cancelUrl, 'checkout=cancel'),
  });

  return { url: session.url };
});

/**
 * Stripe からの Webhook。checkout.session.completed を検証した上で
 * users/{uid} にジェム or VSパスを付与する。Stripeダッシュボードで
 * このURLをエンドポイントとして登録すること。
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

  if (event.type === 'checkout.session.completed') {
    const session = event.data.object;
    const uid = session.metadata && session.metadata.uid;
    const sku = session.metadata && session.metadata.sku;
    const product = PRODUCTS[sku];

    if (uid && product) {
      const eventRef = db.collection('processedStripeEvents').doc(session.id);
      const userRef = db.collection('users').doc(uid);

      await db.runTransaction(async (tx) => {
        const eventSnap = await tx.get(eventRef);
        if (eventSnap.exists) return; // 冪等性: webhookの再送で二重付与しない

        const userSnap = await tx.get(userRef);
        const userData = userSnap.exists ? userSnap.data() : {};

        if (product.type === 'gems') {
          const current = userData.gems || 0;
          tx.set(userRef, { gems: current + product.amount }, { merge: true });
        } else if (product.type === 'vs_pass') {
          const now = Date.now();
          const existing = userData.vsPass;
          const stillActive = !!(existing && existing.expiresAt && existing.expiresAt.toMillis() > now);
          const baseTime = stillActive ? existing.expiresAt.toMillis() : now;
          const expiresAt = new Date(baseTime + product.days * 24 * 60 * 60 * 1000);
          const matchesRemaining = (stillActive ? (existing.matchesRemaining || 0) : 0) + product.matches;
          tx.set(userRef, { vsPass: { matchesRemaining, expiresAt } }, { merge: true });
        }

        tx.set(eventRef, { uid, sku, processedAt: FieldValue.serverTimestamp() });
      });
    }
  }

  res.json({ received: true });
});
