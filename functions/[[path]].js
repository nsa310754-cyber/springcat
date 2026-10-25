// Cloudflare Pages Function: サイト編集で作ったページ/ファイルの配信（catch-all）
//   静的ページ/ファイル: KV "site:"+path をメタ("sitemeta:"+path)に従って配信。
//   動的ページ(meta.dyn): GET はサーバー側テンプレート展開、POST はフォーム送信を
//     KVコレクション("sitecoll:"+coll)に蓄積して 303 リダイレクト。
//   どれも無ければ next() で通常の静的ファイル・他 Function に委ねる（既存サイトは不変）。
function norm(p) { return String(p || "").replace(/^\/+/, "").replace(/\/+$/, ""); }
function b64ToBytes(b64) { const bin = atob(b64); const a = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i); return a; }
function esc(s) { return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c])); }
async function replaceAsync(str, re, fn) {
  const parts = []; let last = 0, m;
  re.lastIndex = 0;
  while ((m = re.exec(str)) !== null) { parts.push(str.slice(last, m.index)); parts.push(await fn(...m)); last = m.index + m[0].length; if (m.index === re.lastIndex) re.lastIndex++; }
  parts.push(str.slice(last)); return parts.join("");
}

async function renderTemplate(tpl, ctx) {
  const { url, request, env, path } = ctx;
  let out = tpl;
  // {{rows:coll}} ... {{row.field}} ... {{/rows}}
  out = await replaceAsync(out, /\{\{rows:([^}]+)\}\}([\s\S]*?)\{\{\/rows\}\}/g, async (_m, coll, inner) => {
    let rows = []; try { rows = JSON.parse((await env.NEWS_KV.get("sitecoll:" + coll.trim())) || "[]"); } catch {}
    return rows.map((r) => inner.replace(/\{\{row\.([a-zA-Z0-9_]+)\}\}/g, (_mm, f) => esc(r[f] == null ? "" : r[f]))).join("");
  });
  // {{data:key}}
  out = await replaceAsync(out, /\{\{data:([^}]+)\}\}/g, async (_m, key) => esc((await env.NEWS_KV.get("sitedata:" + key.trim())) || ""));
  // {{count}} : ページ閲覧カウンタ
  if (out.indexOf("{{count}}") >= 0) {
    const k = "sitecount:" + path; const n = (Number(await env.NEWS_KV.get(k)) || 0) + 1;
    await env.NEWS_KV.put(k, String(n)); out = out.split("{{count}}").join(String(n));
  }
  const q = url.searchParams; const now = new Date();
  out = out.replace(/\{\{query\.([a-zA-Z0-9_]+)\}\}/g, (_m, n) => esc(q.get(n) || ""));
  out = out.replace(/\{\{now\}\}/g, now.toISOString())
           .replace(/\{\{date\}\}/g, now.toISOString().slice(0, 10))
           .replace(/\{\{time\}\}/g, now.toISOString().slice(11, 19))
           .replace(/\{\{random\}\}/g, () => Math.random().toString(36).slice(2, 10))
           .replace(/\{\{path\}\}/g, esc(path))
           .replace(/\{\{ip\}\}/g, esc(request.headers.get("CF-Connecting-IP") || ""))
           .replace(/\{\{ua\}\}/g, esc(request.headers.get("User-Agent") || ""));
  return out;
}

async function findExact(kv, key) {
  const raw = await kv.get("site:" + key);
  if (raw === null) return null;
  let meta = {}; try { meta = JSON.parse((await kv.get("sitemeta:" + key)) || "{}"); } catch {}
  return { p: key, raw, meta };
}
async function findPage(kv, path) {
  for (const p of [path, path + ".html", path + "/index.html", path + "/index"]) {
    const hit = await findExact(kv, p);
    if (hit) return hit;
  }
  return null;
}

export async function onRequest(context) {
  const { request, env, next } = context;
  if (!env.NEWS_KV) return next();
  const url = new URL(request.url);
  let path = ""; try { path = norm(decodeURIComponent(url.pathname)); } catch { path = norm(url.pathname); }

  let hit;
  if (!path) {
    // ルート /: KV の index.html / index をホームページとして配信（/ と /index.html を統一）
    hit = (await findExact(env.NEWS_KV, "index.html")) || (await findExact(env.NEWS_KV, "index"));
    if (!hit) return next();            // 無ければ既存の静的 index.html
    path = hit.p;
  } else {
    hit = await findPage(env.NEWS_KV, path);
  }

  // フォーム送信 (動的ページのみ)
  if (request.method === "POST") {
    if (!hit || !hit.meta.dyn) return next();
    let form; try { form = await request.formData(); } catch { return new Response("bad form", { status: 400 }); }
    const row = { id: (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(36).slice(2)), ts: Date.now() };
    let coll = path, redirect = "/" + path;
    for (const [k, v] of form.entries()) {
      if (k === "_coll") { coll = norm(String(v)) || path; continue; }
      if (k === "_redirect") { redirect = String(v); continue; }
      if (k.startsWith("_")) continue;
      row[k] = String(v).slice(0, 5000);
    }
    let rows = []; try { rows = JSON.parse((await env.NEWS_KV.get("sitecoll:" + coll)) || "[]"); } catch {}
    rows.push(row); if (rows.length > 5000) rows = rows.slice(-5000);
    await env.NEWS_KV.put("sitecoll:" + coll, JSON.stringify(rows));
    let idx = []; try { idx = JSON.parse((await env.NEWS_KV.get("sitecolls:__index__")) || "[]"); } catch {}
    if (!idx.includes(coll)) { idx.push(coll); await env.NEWS_KV.put("sitecolls:__index__", JSON.stringify(idx)); }
    return new Response(null, { status: 303, headers: { Location: new URL(redirect, url).toString() } });
  }

  if (request.method !== "GET" && request.method !== "HEAD") return next();
  if (!hit) return next();

  const ct = hit.meta.ct || "text/html";
  const headers = { "Content-Type": (/^text\/|json|xml|javascript|svg/.test(ct) ? ct + "; charset=utf-8" : ct), "Cache-Control": "no-cache" };
  if (request.method === "HEAD") return new Response(null, { headers });

  // 動的ページ: テンプレート展開（HTMLテキストのみ）
  if (hit.meta.dyn && hit.meta.enc !== "b64") {
    const html = await renderTemplate(hit.raw, { url, request, env, path });
    return new Response(html, { headers: { ...headers, "Content-Type": "text/html; charset=utf-8" } });
  }
  const body = hit.meta.enc === "b64" ? b64ToBytes(hit.raw) : hit.raw;
  return new Response(body, { headers });
}
