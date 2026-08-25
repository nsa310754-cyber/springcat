// Cloudflare Pages Function: X(Twitter) 検索プロキシ（/api/x-search）
// APIキー/シークレットは Cloudflare の環境変数(Secret) X_API_KEY / X_API_SECRET から読む。
// サーバー側で app-only Bearer を生成して recent search を実行（CORS回避・キー秘匿）。
//
// 通信調整: Cloudflare Workers からの素の fetch は User-Agent が付かず、
// X 側にボット扱いされて HTML ブロックページ(<!DOCTYPE ...)が返ることがある。
// これを防ぐため User-Agent / Accept を明示し、タイムアウトと堅牢な
// レスポンス解釈（非JSONでも落ちない）を入れている。
const UA = "DeliverySalesTool/1.0 (+https://springcat.ragdollp.site/eigyo/)";

async function fetchWithTimeout(url, opts = {}, ms = 15000) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), ms);
  try {
    return await fetch(url, { ...opts, signal: ctrl.signal });
  } finally {
    clearTimeout(t);
  }
}

export async function onRequestPost({ request, env }) {
  try {
    if (!env.X_API_KEY || !env.X_API_SECRET) {
      return json({ error: "X_API_KEY / X_API_SECRET が未設定です（Cloudflareの環境変数に設定してください）" }, 500);
    }
    const { query, maxResults } = await request.json().catch(() => ({}));
    const basic = btoa(`${env.X_API_KEY}:${env.X_API_SECRET}`);

    // 1) app-only Bearer Token
    let tokRes;
    try {
      tokRes = await fetchWithTimeout("https://api.twitter.com/oauth2/token", {
        method: "POST",
        headers: {
          Authorization: `Basic ${basic}`,
          "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
          Accept: "application/json",
          "User-Agent": UA,
        },
        body: "grant_type=client_credentials",
      });
    } catch (e) {
      return json({ error: `X認証への接続に失敗（${e.name === "AbortError" ? "タイムアウト" : e.message}）` }, 502);
    }
    const tokText = await tokRes.text();
    let tok;
    try { tok = JSON.parse(tokText); } catch {
      return json({ error: `X認証がJSON以外を返しました（${tokRes.status}）。X側がアクセスを制限している可能性: ${tokText.replace(/\s+/g, " ").slice(0, 120)}` }, 502);
    }
    if (!tok.access_token) return json({ error: `token取得失敗 ${tokRes.status}: ${JSON.stringify(tok).slice(0, 160)}` }, tokRes.status || 502);

    // 2) recent search
    const params = new URLSearchParams({
      query: query || "配達員 lang:ja -is:retweet",
      max_results: String(Math.max(10, Math.min(100, maxResults || 30))),
      "tweet.fields": "created_at,author_id,public_metrics",
      expansions: "author_id",
      "user.fields": "name,username,description,location,public_metrics",
    });
    let sr;
    try {
      sr = await fetchWithTimeout(`https://api.twitter.com/2/tweets/search/recent?${params}`, {
        headers: { Authorization: `Bearer ${tok.access_token}`, Accept: "application/json", "User-Agent": UA },
      });
    } catch (e) {
      return json({ error: `X検索への接続に失敗（${e.name === "AbortError" ? "タイムアウト" : e.message}）` }, 502);
    }
    const body = await sr.text();
    // JSONならそのまま返す。HTML等ならエラー化してフロントで扱えるようにする。
    try { JSON.parse(body); }
    catch {
      return json({ error: `X検索がJSON以外を返しました（${sr.status}）。X側がアクセスを制限している可能性: ${body.replace(/\s+/g, " ").slice(0, 120)}` }, sr.status >= 400 ? sr.status : 502);
    }
    return new Response(body, { status: sr.status, headers: { "Content-Type": "application/json" } });
  } catch (e) {
    return json({ error: String(e && e.message || e) }, 500);
  }
}
const json = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json" } });
