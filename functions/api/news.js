// Cloudflare Pages Function: お知らせ API（/api/news）
//   GET  /api/news            → { updatedAt }            誰でも可（アプリの NEW 表示用）
//   POST /api/news {action:"load"}              → { html, updatedAt }   要パスワード
//   POST /api/news {action:"save", html}        → { ok, updatedAt }     要パスワード
// パスワードはヘッダー X-News-Pass。照合は Secret NEWS_PASS_HASH = sha256("springcat-news:" + パスワード)。
// 総当たり対策: 同じ IP で 10 分間に 8 回失敗したらしばらく拒否。
const MAX = 1000000;
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST, OPTIONS", "Access-Control-Allow-Headers": "Content-Type, X-News-Pass" };
const json = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...CORS } });

export async function onRequestOptions() { return new Response(null, { status: 204, headers: CORS }); }

export async function onRequestGet({ env }) {
  const updatedAt = Number(await env.NEWS_KV.get("updatedAt")) || 0;
  return json({ updatedAt });
}

async function sha256hex(s) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export async function onRequestPost({ request, env }) {
  try {
    if (!env.NEWS_KV || !env.NEWS_PASS_HASH) return json({ error: "サーバー設定(NEWS_KV / NEWS_PASS_HASH)がありません" }, 500);
    const ip = request.headers.get("CF-Connecting-IP") || "?";
    const failKey = "fail:" + ip;
    const fails = Number(await env.NEWS_KV.get(failKey)) || 0;
    if (fails >= 8) return json({ error: "失敗が続いたため一時的にロックしています。10分ほど待ってください。" }, 429);
    const h = await sha256hex("springcat-news:" + (request.headers.get("X-News-Pass") || ""));
    if (h !== env.NEWS_PASS_HASH) {
      await env.NEWS_KV.put(failKey, String(fails + 1), { expirationTtl: 600 });
      return json({ error: "パスワードが違います" }, 401);
    }
    const body = await request.json().catch(() => ({}));
    if (body.action === "load") {
      const [html, updatedAt] = await Promise.all([env.NEWS_KV.get("html"), env.NEWS_KV.get("updatedAt")]);
      return json({ html: html || null, updatedAt: Number(updatedAt) || 0 });
    }
    if (body.action === "save") {
      if (typeof body.html !== "string" || !body.html.trim()) return json({ error: "HTML が空です" }, 400);
      if (body.html.length > MAX) return json({ error: "HTML が大きすぎます (" + MAX + " 文字まで)" }, 413);
      const now = Date.now();
      await env.NEWS_KV.put("html", body.html);
      await env.NEWS_KV.put("updatedAt", String(now));
      return json({ ok: true, updatedAt: now });
    }
    return json({ error: "不明な操作です" }, 400);
  } catch (e) {
    return json({ error: String((e && e.message) || e) }, 500);
  }
}
