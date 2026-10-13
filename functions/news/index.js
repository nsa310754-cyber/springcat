// Cloudflare Pages Function: お知らせページ（/news）
// /newsedit で編集・保存した HTML（KV: NEWS_KV の "html"）をそのまま返す。
// まだ保存されていなければ静的な news/index.html（初期テンプレート）を返す。
export async function onRequestGet({ request, env }) {
  try {
    const html = env.NEWS_KV ? await env.NEWS_KV.get("html") : null;
    if (html) {
      return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
    }
  } catch (e) {}
  return env.ASSETS.fetch(request);
}
