// Cloudflare Pages Function: サイト編集で作ったページ/ファイルの配信（catch-all）
//   /domainedit で保存・アップロードしたものを ragdollp.site/<path> で公開する。
//   KV(NEWS_KV) の "site:"+path があればメタ("sitemeta:"+path)に従って配信、
//   無ければ next() で通常の静的ファイル・他 Function にそのまま委ねる（既存サイトは不変）。
function norm(p) { return String(p || "").replace(/^\/+/, "").replace(/\/+$/, ""); }
function b64ToBytes(b64) {
  const bin = atob(b64); const a = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
  return a;
}

export async function onRequest(context) {
  const { request, env, next } = context;
  if ((request.method !== "GET" && request.method !== "HEAD") || !env.NEWS_KV) return next();

  let path = "";
  try { path = norm(decodeURIComponent(new URL(request.url).pathname)); } catch { path = norm(new URL(request.url).pathname); }
  if (!path) return next(); // ルートは既存 index.html を使う

  const candidates = [path, path + "/index.html", path + "/index"];
  for (const p of candidates) {
    const raw = await env.NEWS_KV.get("site:" + p);
    if (raw === null) continue;
    let meta = {}; try { meta = JSON.parse((await env.NEWS_KV.get("sitemeta:" + p)) || "{}"); } catch {}
    const ct = meta.ct || "text/html";
    const headers = { "Content-Type": (/^text\/|json|xml|javascript|svg/.test(ct) ? ct + "; charset=utf-8" : ct), "Cache-Control": "no-cache" };
    if (request.method === "HEAD") return new Response(null, { headers });
    const body = meta.enc === "b64" ? b64ToBytes(raw) : raw;
    return new Response(body, { headers });
  }
  return next();
}
