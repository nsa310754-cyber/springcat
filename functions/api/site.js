// Cloudflare Pages Function: サイト編集 API（/api/site）
//   認証はどちらか:
//     - ヘッダー X-Site-Pass : 編集ツール用。sha256("springcat-site:"+パス) === GATE
//     - ヘッダー Authorization: Bearer <APIキー>  または  X-Site-Key: <APIキー>
//         発行済みキー（sha256 を KV "sitekey:"+hash に保存）なら許可。Neocities と同じ方式。
//   POST {action:"list"}                         → { pages:[{p,ct}] }
//   POST {action:"load",  path}                  → { html, ct, enc, bin }
//   POST {action:"save",  path, html, ct?}       → { ok }            (テキストページ)
//   POST {action:"upload",path, b64, ct?}        → { ok }            (任意ファイル/バイナリ)
//   POST {action:"delete",path}                  → { ok }
//   POST {action:"issuekey", label?}             → { key, id }       (発行は一度だけ表示)
//   POST {action:"listkeys"}                     → { keys:[{id,created,label}] }
//   POST {action:"revokekey", id}                → { ok }
// 保存先は NEWS_KV。ページ本体= "site:"+path、メタ= "sitemeta:"+path、
//   一覧= "site:__index__"、キー= "sitekey:"+hash、キー一覧= "sitekeys:__index__"。
// 公開は functions/[[path]].js が ragdollp.site/<path> で配信する。
const GATE = "70213c5c3ea3c2fc632490fec5b8f0cb7764f3c2cff9c34de0e5f8aa8b4f0667";
const MAX_TEXT = 2 * 1024 * 1024;     // テキストページ 2MB
const MAX_B64 = 24 * 1024 * 1024;     // アップロード base64 24MB（元ファイル ~18MB まで）
const IDX = "site:__index__";
const KIDX = "sitekeys:__index__";
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "POST, OPTIONS", "Access-Control-Allow-Headers": "Content-Type, X-Site-Pass, X-Site-Key, Authorization" };
const json = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...CORS } });

// 機能ルートと、Neocities側に無い既存repo静的ディレクトリのみ保護（コンテンツページは移行可）
const RESERVED = new Set(["", "api", "functions", "dist", "domainedit", "newsedit", "gameedit",
  "news", "randommode", "game", "oshilog", ".well-known", "robots.txt", "sitemap.xml", "springcat-app.html",
  "download", "apk", "android", "ios", "desktop", "desktop-lite", "docs", "eigyo", "paddle",
  "delete-account", "delivery-sales-tool"]);

const EXT_CT = {
  html: "text/html", htm: "text/html", css: "text/css", js: "text/javascript", mjs: "text/javascript",
  json: "application/json", xml: "application/xml", txt: "text/plain", md: "text/markdown", csv: "text/csv",
  svg: "image/svg+xml", png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif",
  webp: "image/webp", avif: "image/avif", ico: "image/x-icon", bmp: "image/bmp",
  mp4: "video/mp4", webm: "video/webm", mov: "video/quicktime", mp3: "audio/mpeg", wav: "audio/wav",
  ogg: "audio/ogg", m4a: "audio/mp4", flac: "audio/flac", woff: "font/woff", woff2: "font/woff2",
  ttf: "font/ttf", otf: "font/otf", pdf: "application/pdf", zip: "application/zip", wasm: "application/wasm",
  webmanifest: "application/manifest+json", apk: "application/vnd.android.package-archive",
};
function ctOf(path, given) {
  if (given) return given;
  const ext = String(path).split(".").pop().toLowerCase();
  return EXT_CT[ext] || "application/octet-stream";
}
function isText(ct) {
  return /^text\//.test(ct) || ["application/json", "application/xml", "application/javascript", "text/javascript", "image/svg+xml", "application/manifest+json"].includes(ct);
}

export async function onRequestOptions() { return new Response(null, { status: 204, headers: CORS }); }

async function sha256hex(s) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}
function norm(p) { return String(p || "").trim().replace(/^\/+/, "").replace(/\\/g, "/").replace(/\/+/g, "/").replace(/\/+$/, "").split("/").map((s) => s.trim()).join("/"); }
function reserved(p) {
  const n = norm(p).toLowerCase();
  if (!n || RESERVED.has(n) || RESERVED.has(n.split("/")[0])) return true;
  if (n.includes("..")) return true;
  return false;
}
async function readIndex(kv) {
  try { const a = JSON.parse((await kv.get(IDX)) || "[]"); return a.map((x) => typeof x === "string" ? { p: x, ct: "text/html" } : x); }
  catch { return []; }
}
async function readKeyIndex(kv) { try { return JSON.parse((await kv.get(KIDX)) || "[]"); } catch { return []; } }

async function authKind(request, kv) {
  const pass = request.headers.get("X-Site-Pass");
  if (pass && (await sha256hex("springcat-site:" + pass)) === GATE) return "pass";
  let key = request.headers.get("X-Site-Key") || "";
  const auth = request.headers.get("Authorization") || "";
  if (!key && /^Bearer\s+/i.test(auth)) key = auth.replace(/^Bearer\s+/i, "").trim();
  if (key) { const h = await sha256hex(key); if ((await kv.get("sitekey:" + h)) !== null) return "key"; }
  return null;
}

export async function onRequestPost({ request, env }) {
  try {
    const kv = env.NEWS_KV;
    if (!kv) return json({ error: "サーバー設定(NEWS_KV)がありません" }, 500);

    // 総当たり対策（パスワード失敗のみ数える）
    const ip = request.headers.get("CF-Connecting-IP") || "?";
    const failKey = "sitefail:" + ip;
    const fails = Number(await kv.get(failKey)) || 0;
    if (fails >= 8) return json({ error: "失敗が続いたため一時的にロック中です。10分ほど待ってください。" }, 429);

    const kind = await authKind(request, kv);
    if (!kind) {
      if (request.headers.get("X-Site-Pass")) await kv.put(failKey, String(fails + 1), { expirationTtl: 600 });
      return json({ error: "認証できません（パスワードまたはAPIキーが違います）" }, 401);
    }

    const body = await request.json().catch(() => ({}));
    const action = body.action;

    if (action === "list") return json({ pages: await readIndex(kv) });

    if (action === "load") {
      const p = norm(body.path);
      const raw = await kv.get("site:" + p);
      if (raw === null) return json({ error: "ページが見つかりません" }, 404);
      let meta = {}; try { meta = JSON.parse((await kv.get("sitemeta:" + p)) || "{}"); } catch {}
      const ct = meta.ct || "text/html", enc = meta.enc || "utf8";
      return json({ html: enc === "utf8" ? raw : "", ct, enc, bin: enc === "b64" });
    }

    if (action === "save") {
      const p = norm(body.path);
      if (reserved(p)) return json({ error: "そのパスは予約済みで保存できません" }, 400);
      const html = String(body.html || "");
      if (html.length > MAX_TEXT) return json({ error: "ページが大きすぎます（2MBまで）" }, 413);
      const ct = ctOf(p, body.ct && isText(body.ct) ? body.ct : (p.includes(".") ? null : "text/html")) || "text/html";
      await kv.put("site:" + p, html);
      await kv.put("sitemeta:" + p, JSON.stringify({ ct: isText(ct) ? ct : "text/html", enc: "utf8" }));
      await addIndex(kv, p, isText(ct) ? ct : "text/html");
      return json({ ok: true });
    }

    if (action === "upload") {
      const p = norm(body.path);
      if (reserved(p)) return json({ error: "そのパスは予約済みで保存できません: " + p }, 400);
      const b64 = String(body.b64 || "");
      if (!b64) return json({ error: "ファイルが空です: " + p }, 400);
      if (b64.length > MAX_B64) return json({ error: "ファイルが大きすぎます（約18MBまで）: " + p }, 413);
      const ct = ctOf(p, body.ct);
      await kv.put("site:" + p, b64);
      await kv.put("sitemeta:" + p, JSON.stringify({ ct, enc: "b64" }));
      await addIndex(kv, p, ct);
      return json({ ok: true });
    }

    if (action === "delete") {
      const p = norm(body.path);
      await kv.delete("site:" + p);
      await kv.delete("sitemeta:" + p);
      const idx = (await readIndex(kv)).filter((x) => x.p !== p);
      await kv.put(IDX, JSON.stringify(idx));
      return json({ ok: true });
    }

    if (action === "issuekey") {
      const bytes = crypto.getRandomValues(new Uint8Array(24));
      const key = [...bytes].map((x) => x.toString(16).padStart(2, "0")).join("");
      const h = await sha256hex(key);
      const id = h.slice(0, 8);
      const rec = { id, created: Date.now(), label: String(body.label || "").slice(0, 40) };
      await kv.put("sitekey:" + h, JSON.stringify(rec));
      const list = await readKeyIndex(kv); list.push({ ...rec, hash: h });
      await kv.put(KIDX, JSON.stringify(list));
      return json({ key, id });
    }

    if (action === "listkeys") {
      const list = await readKeyIndex(kv);
      return json({ keys: list.map((k) => ({ id: k.id, created: k.created, label: k.label })) });
    }

    if (action === "revokekey") {
      const id = String(body.id || "");
      const list = await readKeyIndex(kv);
      const hit = list.find((k) => k.id === id);
      if (hit) await kv.delete("sitekey:" + hit.hash);
      await kv.put(KIDX, JSON.stringify(list.filter((k) => k.id !== id)));
      return json({ ok: true });
    }

    return json({ error: "不明な操作です" }, 400);
  } catch (e) {
    return json({ error: "エラー: " + (e && e.message || e) }, 500);
  }
}

async function addIndex(kv, p, ct) {
  const idx = await readIndex(kv);
  const i = idx.findIndex((x) => x.p === p);
  if (i >= 0) idx[i] = { p, ct }; else idx.push({ p, ct });
  await kv.put(IDX, JSON.stringify(idx));
}
