// Cloudflare Pages Function: サイト編集で作ったページの配信（catch-all）
//   /domainedit で保存したページを ragdollp.site/<path> で公開する。
//   KV(NEWS_KV) に "site:" + path があればそれを返し、無ければ next() で
//   通常の静的ファイル・他の Function にそのまま委ねる（既存サイトは一切変えない）。
// 注意: /api/* や /news などの個別 Function は本ファイルより優先されるため影響しない。
function norm(p) { return String(p || "").replace(/^\/+/, "").replace(/\/+$/, ""); }

export async function onRequest(context) {
  const { request, env, next } = context;

  // GET/HEAD のみ対象。それ以外や KV 未設定は素通し。
  if ((request.method !== "GET" && request.method !== "HEAD") || !env.NEWS_KV) return next();

  let path = "";
  try { path = norm(decodeURIComponent(new URL(request.url).pathname)); } catch { path = norm(new URL(request.url).pathname); }
  if (!path) return next(); // ルートは既存 index.html を使う

  // 候補: そのまま / 末尾 index（ディレクトリ風アクセス用）
  const keys = ["site:" + path, "site:" + path + "/index.html", "site:" + path + "/index"];
  for (const k of keys) {
    const html = await env.NEWS_KV.get(k);
    if (html !== null) {
      return new Response(request.method === "HEAD" ? null : html, {
        headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-cache" },
      });
    }
  }
  return next(); // 無ければ通常配信へ
}
