// Cloudflare Pages Function: アーカイブのゲーム本体（/game/Blockdestoryarchive/<id>）— パスワードなしでそのまま遊べる
export async function onRequestGet({ params, env }) {
  const id = String(params.id || "");
  if (!/^\d{10,16}$/.test(id) || !env.NEWS_KV) return new Response("Not found", { status: 404 });
  const html = await env.NEWS_KV.get("arc:" + id);
  if (!html) return new Response("Not found", { status: 404 });
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}
