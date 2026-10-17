// Cloudflare Pages Function: 期間限定モード（/randommode）
//   管理者が /gameedit で公開したゲーム (ゲームのパスワードで暗号化済み) を、パスワード入力画面つきで返す。
//   正しいパスワードでだけブラウザ内で復号して遊べる。アプリ内では受信済みのパスワードを自動入力する
//   (親ウィンドウからの postMessage。パスワードは画面に表示しない)。
const PAGE = (encJson, updatedAt) => `<!DOCTYPE html>
<html lang="ja"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
<meta name="robots" content="noindex,nofollow"><title>⏳ 期間限定モード</title>
<style>
  :root{ color-scheme:dark; }
  body{ margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center; background:radial-gradient(circle at 30% 20%,#3b2a0b,#0b1120 60%); color:#e2e8f0; font-family:system-ui,-apple-system,"Noto Sans JP",sans-serif; }
  .box{ background:rgba(17,24,39,.92); border:1px solid #b45309; border-radius:14px; padding:1.4rem; width:min(22rem, calc(100vw - 2rem)); box-sizing:border-box; text-align:center; box-shadow:0 10px 30px rgba(0,0,0,.4); }
  h1{ font-size:1.25rem; margin:.2rem 0 .4rem; color:#fde68a; }
  p{ font-size:.85rem; color:#cbd5e1; line-height:1.6; margin:.3rem 0 .8rem; }
  input{ width:100%; box-sizing:border-box; padding:.65rem .7rem; border-radius:8px; border:1px solid #92400e; background:#0f172a; color:#fff; font-size:1rem; margin:.3rem 0 .6rem; }
  button{ width:100%; padding:.65rem; border:none; border-radius:8px; cursor:pointer; font-weight:bold; color:#fff; font-size:1rem; background:linear-gradient(135deg,#f59e0b,#b45309); }
  #m{ color:#fca5a5; font-size:.85rem; min-height:1.3em; margin-top:.5rem; }
  .sub{ font-size:.72rem; color:#94a3b8; margin-top:.6rem; }
</style></head><body>
<div class="box">
  <h1>⏳ 期間限定モード</h1>
  ${encJson ? `<p>遊ぶにはパスワードが必要です。</p>
  <form id="f" autocomplete="off"><input id="p" type="password" placeholder="パスワード" autocomplete="off"><button>▶ 遊ぶ</button></form>
  <div id="m"></div><div class="sub">更新: ${new Date(updatedAt).toLocaleString("ja-JP", { timeZone: "Asia/Tokyo" })}</div>` : `<p>現在、期間限定モードはありません。<br>次の公開をお楽しみに!</p>`}
</div>
<script>
var B = ${encJson || "null"};
function u8(s){ var b = atob(s), u = new Uint8Array(b.length); for(var i = 0; i < b.length; i++) u[i] = b.charCodeAt(i); return u }
async function dec(pw){
  var base = await crypto.subtle.importKey('raw', new TextEncoder().encode(pw), 'PBKDF2', false, ['deriveKey'])
  var key = await crypto.subtle.deriveKey({ name:'PBKDF2', salt:u8(B.s), iterations:B.it, hash:'SHA-256' }, base, { name:'AES-GCM', length:256 }, false, ['decrypt'])
  var buf = await crypto.subtle.decrypt({ name:'AES-GCM', iv:u8(B.i) }, key, u8(B.d))
  if(B.z) buf = await new Response(new Blob([buf]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer()
  return new TextDecoder().decode(buf)
}
var busy = false
async function play(pw, auto){
  if(!B || busy) return false
  busy = true; var m = document.getElementById('m'); if(m) m.textContent = auto ? '🔑 受信したパスワードで開いています...' : '確認中...'
  try { var html = await dec(pw); document.open(); document.write(html); document.close(); return true }
  catch(e){ busy = false; if(m) m.textContent = auto ? '受信したパスワードでは開けませんでした (新しいパスワードの発信を待つか、入力してください)' : 'パスワードが違います'; return false }
}
var f = document.getElementById('f')
if(f) f.onsubmit = function(e){ e.preventDefault(); play(document.getElementById('p').value, false) }
// アプリ (親ウィンドウ) から受信済みのパスワードを受け取って自動入力 (画面には出さない)
window.addEventListener('message', function(e){
  if(e.source !== window.parent || !e.data || e.data.type !== 'bd-rm-pw' || typeof e.data.pw !== 'string') return
  play(e.data.pw, true)
})
if(window.parent !== window && B){ try { window.parent.postMessage({ type:'bd-rm-ready', updatedAt:${Number(updatedAt) || 0} }, '*') } catch(e){} }
</script></body></html>`;

export async function onRequestGet({ env }) {
  let enc = null, updatedAt = 0;
  try {
    if (env.NEWS_KV) { enc = await env.NEWS_KV.get("rm:enc"); updatedAt = Number(await env.NEWS_KV.get("rm:updatedAt")) || 0; }
  } catch (e) {}
  // JSON をそのまま script に埋め込む ("</" を避ける)
  const encJson = enc ? enc.replace(/</g, "\\u003c") : "";
  return new Response(PAGE(encJson, updatedAt), { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}
