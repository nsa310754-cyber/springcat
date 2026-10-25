// Cloudflare Pages Function: サイト編集 API（/api/site）
//   POST {action:"list"}                → { pages:[...] }          要パスワード
//   POST {action:"load", path}          → { html }                要パスワード
//   POST {action:"save", path, html}    → { ok }                  要パスワード
//   POST {action:"delete", path}        → { ok }                  要パスワード
// 認証: ヘッダー X-Site-Pass。照合は sha256("springcat-site:" + パスワード) === GATE。
//   （パスワード本体は /domainedit の AES-256 ペイロード内にあり、平文ではどこにも無い）
// 保存先は NEWS_KV（お知らせと共通）。ページは "site:" + path、一覧は "site:__index__"（JSON配列）。
// 公開は functions/[[path]].js が ragdollp.site/<path> で配信する。
const GATE = "70213c5c3ea3c2fc632490fec5b8f0cb7764f3c2cff9c34de0e5f8aa8b4f0667";
const MAX = 2 * 1024 * 1024; // 1ページ最大 2MB
const IDX = "site:__index__";
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "POST, OPTIONS", "Access-Control-Allow-Headers": "Content-Type, X-Site-Pass" };
const json = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...CORS } });

// 上書き禁止（既存の静的ページ・機能を守る）
const RESERVED = new Set(["", "index.html", "index", "api", "functions", "dist", "domainedit", "newsedit", "gameedit",
  "news", "randommode", "game", "oshilog", "buy", "pricing", "privacy", "refunds", "terms", "download",
  "apk", "android", "ios", "desktop", "desktop-lite", "docs", "eigyo", "paddle", "delete-account",
  "delivery-sales-tool", "springcat1security", "springcat-app.html", ".well-known", "robots.txt", "sitemap.xml"]);

export async function onRequestOptions() { return new Response(null, { status: 204, headers: CORS }); }

async function sha256hex(s) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
function norm(p) { return String(p || "").trim().replace(/^\/+/, "").replace(/\s+/g, ""); }
function reserved(p) {
  const n = norm(p).toLowerCase();
  if (!n || RESERVED.has(n)) return true;
  if (RESERVED.has(n.split("/")[0])) return true;
  if (n.includes("..")) return true;
  return false;
}
async function readIndex(kv) { try { return JSON.parse((await kv.get(IDX)) || "[]"); } catch { return []; } }

export async function onRequestPost({ request, env }) {
  try {
    const kv = env.NEWS_KV;
    if (!kv) return json({ error: "サーバー設定(NEWS_KV)がありません" }, 500);

    // 総当たり対策: 同一IPで10分に8回失敗でロック
    const ip = request.headers.get("CF-Connecting-IP") || "?";
    const failKey = "sitefail:" + ip;
    const fails = Number(await kv.get(failKey)) || 0;
    if (fails >= 8) return json({ error: "失敗が続いたため一時的にロック中です。10分ほど待ってください。" }, 429);

    const pass = request.headers.get("X-Site-Pass") || "";
    if ((await sha256hex("springcat-site:" + pass)) !== GATE) {
      await kv.put(failKey, String(fails + 1), { expirationTtl: 600 });
      return json({ error: "パスワードが違います" }, 401);
    }

    const body = await request.json().catch(() => ({}));
    const action = body.action;

    if (action === "list") {
      return json({ pages: await readIndex(kv) });
    }

    if (action === "load") {
      const p = norm(body.path);
      const html = await kv.get("site:" + p);
      if (html === null) return json({ error: "ページが見つかりません" }, 404);
      return json({ html });
    }

    if (action === "save") {
      const p = norm(body.path);
      if (reserved(p)) return json({ error: "そのパスは予約済みで保存できません" }, 400);
      const html = String(body.html || "");
      if (html.length > MAX) return json({ error: "ページが大きすぎます（2MBまで）" }, 413);
      await kv.put("site:" + p, html);
      const idx = await readIndex(kv);
      if (!idx.includes(p)) { idx.push(p); await kv.put(IDX, JSON.stringify(idx)); }
      return json({ ok: true });
    }

    if (action === "delete") {
      const p = norm(body.path);
      await kv.delete("site:" + p);
      const idx = (await readIndex(kv)).filter((x) => x !== p);
      await kv.put(IDX, JSON.stringify(idx));
      return json({ ok: true });
    }

    return json({ error: "不明な操作です" }, 400);
  } catch (e) {
    return json({ error: "エラー: " + (e && e.message || e) }, 500);
  }
}
