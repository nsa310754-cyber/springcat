// Cloudflare Pages Function: 期間限定モードのアーカイブ一覧（/game/Blockdestoryarchive）
//   /randommode が次のゲームに切り替わるたびに、前のゲームがここに追加される。パスワードなしで遊べる。
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const day = (t) => new Date(t).toLocaleString("ja-JP", { timeZone: "Asia/Tokyo", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
export async function onRequestGet({ env }) {
  let index = [];
  try { index = JSON.parse((env.NEWS_KV && (await env.NEWS_KV.get("arc:index"))) || "[]"); } catch (e) { index = []; }
  if (!Array.isArray(index)) index = [];
  const items = index.map((x, i) => `<a class="it" href="/game/Blockdestoryarchive/${esc(x.id)}">
    <span class="no">#${index.length - i}</span><span class="tt">${esc(x.title || "期間限定ゲーム")}</span>
    <span class="dt">${day(x.from)} 〜 ${day(x.to)}</span><span class="go">▶ 遊ぶ</span></a>`).join("\n");
  const html = `<!DOCTYPE html><html lang="ja"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>🗄️ Block Destroy 期間限定アーカイブ</title>
<style>
  :root{ color-scheme:dark; } body{ margin:0; background:#0b1120; color:#e2e8f0; font-family:system-ui,-apple-system,"Noto Sans JP",sans-serif; }
  .wrap{ max-width:44rem; margin:0 auto; padding:1.2rem; } h1{ font-size:1.3rem; color:#fde68a; margin:.4rem 0 .3rem; } p{ color:#94a3b8; font-size:.85rem; line-height:1.7; }
  .it{ display:grid; grid-template-columns:auto 1fr auto; gap:.2rem .7rem; align-items:center; text-decoration:none; color:inherit; background:#111827; border:1px solid #334155; border-radius:12px; padding:.8rem 1rem; margin:.6rem 0; }
  .it:hover{ border-color:#f59e0b; } .no{ color:#f59e0b; font-weight:bold; } .tt{ font-weight:bold; } .go{ grid-row:span 2; color:#fde68a; font-weight:bold; }
  .dt{ grid-column:2; font-size:.75rem; color:#94a3b8; } .empty{ border:1px dashed #334155; border-radius:12px; padding:1.2rem; text-align:center; color:#94a3b8; }
</style></head><body><div class="wrap">
<h1>🗄️ 期間限定アーカイブ</h1>
<p>Block Destroy の期間限定モードで公開されていたゲームです。期間が終わったゲームがここに追加されていきます。<b>パスワードなし</b>で遊べます。</p>
${items || '<div class="empty">まだありません。期間限定モードが次のゲームに切り替わると、ここに追加されます。</div>'}
</div></body></html>`;
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}
