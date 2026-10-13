// Cloudflare Pages Function: お知らせの更新日時（/news/meta）— アプリの NEW 表示用（誰でも可）
export async function onRequestGet({ env }) {
  const updatedAt = env.NEWS_KV ? Number(await env.NEWS_KV.get("updatedAt")) || 0 : 0;
  return new Response(JSON.stringify({ updatedAt }), {
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", "Access-Control-Allow-Origin": "*" },
  });
}
