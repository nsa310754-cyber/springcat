// Cloudflare Pages Function: 期間限定モード API（/api/randommode）
//   GET  /api/randommode                  → { updatedAt }           誰でも可
//   POST {action:"load"}                  → { enc, updatedAt }      要パスワード (enc はゲームのパスワードで暗号化済み)
//   POST {action:"save", enc, pwBox, archive:{html,title}?} → { ok, updatedAt, archived }   要パスワード
//        archive があれば、それまで公開していたゲームを /game/Blockdestoryarchive に追加する (パスワードなし)。
//        pwBox = ゲームのパスワードを管理者パスワードで暗号化したもの (次の公開時に前のゲームを復号してアーカイブするため)
// 認証はお知らせ編集と共通: ヘッダー X-News-Pass、照合は Secret NEWS_PASS_HASH。保存先も NEWS_KV ("rm:" キー)。
// ゲーム本体はブラウザ側で暗号化してから送られてくる (サーバーには平文もゲームのパスワードも届かない)。
const MAX = 24 * 1024 * 1024;
const CORS = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Methods": "GET, POST, OPTIONS", "Access-Control-Allow-Headers": "Content-Type, X-News-Pass" };
const json = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...CORS } });

export async function onRequestOptions() { return new Response(null, { status: 204, headers: CORS }); }

export async function onRequestGet({ env }) {
  const updatedAt = env.NEWS_KV ? Number(await env.NEWS_KV.get("rm:updatedAt")) || 0 : 0;
  return json({ updatedAt });
}

async function sha256hex(s) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export async function onRequestPost({ request, env }) {
  try {
    if (!env.NEWS_KV || !env.NEWS_PASS_HASH) return json({ error: "サーバー設定(NEWS_KV / NEWS_PASS_HASH)がありません" }, 500);
    const ip = request.headers.get("CF-Connecting-IP") || "?";
    const failKey = "fail:" + ip;
    const fails = Number(await env.NEWS_KV.get(failKey)) || 0;
    if (fails >= 8) return json({ error: "失敗が続いたため一時的にロックしています。10分ほど待ってください。" }, 429);
    const h = await sha256hex("springcat-news:" + (request.headers.get("X-News-Pass") || ""));
    if (h !== env.NEWS_PASS_HASH) {
      await env.NEWS_KV.put(failKey, String(fails + 1), { expirationTtl: 600 });
      return json({ error: "パスワードが違います" }, 401);
    }
    const body = await request.json().catch(() => ({}));
    if (body.action === "load") {
      const [enc, updatedAt, pwBox] = await Promise.all([env.NEWS_KV.get("rm:enc"), env.NEWS_KV.get("rm:updatedAt"), env.NEWS_KV.get("rm:pwBox")]);
      return json({ enc: enc || null, updatedAt: Number(updatedAt) || 0, pwBox: pwBox || null });
    }
    if (body.action === "save") {
      if (typeof body.enc !== "string" || !body.enc) return json({ error: "データが空です" }, 400);
      if (body.enc.length > MAX) return json({ error: "大きすぎます" }, 413);
      let o; try { o = JSON.parse(body.enc); } catch (e) { return json({ error: "形式が違います" }, 400); }
      if (!o || typeof o.s !== "string" || typeof o.i !== "string" || typeof o.d !== "string") return json({ error: "暗号化されたデータではありません" }, 400);
      const now = Date.now();
      let archived = false;
      // 🗄️ それまで公開していたゲームをアーカイブに追加 (追加式。古いものは消さない)
      const prevAt = Number(await env.NEWS_KV.get("rm:updatedAt")) || 0;
      const a = body.archive;
      if (a && typeof a.html === "string" && a.html.trim() && prevAt && (await env.NEWS_KV.get("rm:enc"))) {
        if (a.html.length > MAX) return json({ error: "アーカイブするゲームが大きすぎます" }, 413);
        const id = String(prevAt);
        let index = [];
        try { index = JSON.parse((await env.NEWS_KV.get("arc:index")) || "[]"); } catch (e) { index = []; }
        if (!Array.isArray(index)) index = [];
        if (!index.some((x) => x && x.id === id)) {
          await env.NEWS_KV.put("arc:" + id, a.html);
          index.unshift({ id, title: String(a.title || "").slice(0, 120), from: prevAt, to: now, size: a.html.length });
          await env.NEWS_KV.put("arc:index", JSON.stringify(index));
        }
        archived = true;
      }
      await env.NEWS_KV.put("rm:enc", body.enc);
      if (typeof body.pwBox === "string" && body.pwBox.length < 4000) await env.NEWS_KV.put("rm:pwBox", body.pwBox);
      await env.NEWS_KV.put("rm:updatedAt", String(now));
      return json({ ok: true, updatedAt: now, archived });
    }
    return json({ error: "不明な操作です" }, 400);
  } catch (e) {
    return json({ error: String((e && e.message) || e) }, 500);
  }
}
