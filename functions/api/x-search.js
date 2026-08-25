// Cloudflare Pages Function: X(Twitter) 検索プロキシ（/api/x-search）
// APIキー/シークレットは Cloudflare の環境変数(Secret) X_API_KEY / X_API_SECRET から読む。
// サーバー側で app-only Bearer を生成して recent search を実行（CORS回避・キー秘匿）。
export async function onRequestPost({ request, env }) {
  try {
    if (!env.X_API_KEY || !env.X_API_SECRET) {
      return json({ error: "X_API_KEY / X_API_SECRET が未設定です（Cloudflareの環境変数に設定してください）" }, 500);
    }
    const { query, maxResults } = await request.json();
    const basic = btoa(`${env.X_API_KEY}:${env.X_API_SECRET}`);

    // 1) app-only Bearer Token
    const tokRes = await fetch("https://api.twitter.com/oauth2/token", {
      method: "POST",
      headers: { Authorization: `Basic ${basic}`, "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
      body: "grant_type=client_credentials",
    });
    const tokText = await tokRes.text();
    let tok;
    try { tok = JSON.parse(tokText); } catch {
      return json({ error: `X認証がJSON以外を返しました（${tokRes.status}）: ${tokText.replace(/\s+/g, " ").slice(0, 140)}` }, 502);
    }
    if (!tok.access_token) return json({ error: `token取得失敗 ${tokRes.status}: ${JSON.stringify(tok).slice(0, 160)}` }, 502);

    // 2) recent search
    const params = new URLSearchParams({
      query: query || "配達員 lang:ja -is:retweet",
      max_results: String(Math.max(10, Math.min(100, maxResults || 30))),
      "tweet.fields": "created_at,author_id,public_metrics",
      expansions: "author_id",
      "user.fields": "name,username,description,location,public_metrics",
    });
    const sr = await fetch(`https://api.twitter.com/2/tweets/search/recent?${params}`, {
      headers: { Authorization: `Bearer ${tok.access_token}` },
    });
    const body = await sr.text();
    return new Response(body, { status: sr.status, headers: { "Content-Type": "application/json" } });
  } catch (e) {
    return json({ error: String(e && e.message || e) }, 500);
  }
}
const json = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json" } });
