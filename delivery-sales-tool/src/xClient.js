import { config, hasXApi } from "./config.js";
import { mockTweets } from "./mockData.js";

const X_SEARCH_URL = "https://api.twitter.com/2/tweets/search/recent";
const X_TOKEN_URL = "https://api.twitter.com/oauth2/token";

// 生成した app-only Bearer Token をキャッシュする
let cachedBearer = "";

/**
 * 検索に使う Bearer Token を取得する。
 * X_BEARER_TOKEN があればそれを使い、無ければ API Key/Secret から
 * client_credentials で app-only Bearer を生成してキャッシュする。
 */
async function getBearerToken() {
  if (config.xBearerToken) return config.xBearerToken;
  if (cachedBearer) return cachedBearer;

  const basic = Buffer.from(`${config.xApiKey}:${config.xApiSecret}`).toString("base64");
  const res = await fetch(X_TOKEN_URL, {
    method: "POST",
    headers: {
      Authorization: `Basic ${basic}`,
      "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
    },
    body: "grant_type=client_credentials",
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.access_token) {
    throw new Error(`X token error ${res.status}: ${JSON.stringify(data).slice(0, 200)}`);
  }
  cachedBearer = data.access_token;
  return cachedBearer;
}

/**
 * 検索条件からキーワード投稿を収集する。
 * X_BEARER_TOKEN が設定されていれば公式 API（recent search）を使い、
 * 未設定ならモックデータを返す。返り値は共通の正規化形。
 *
 * @param {{keywords:string[], maxResults:number, langJa:boolean, excludeRetweets:boolean}} search
 * @returns {Promise<{source:'x-api'|'mock', tweets:NormalizedTweet[], error?:string}>}
 */
export async function collectTweets(search) {
  if (!hasXApi()) {
    return { source: "mock", tweets: filterMock(search) };
  }
  try {
    const tweets = await searchWithXApi(search);
    return { source: "x-api", tweets };
  } catch (err) {
    // 認証切れ・プラン未対応(402/403)・レート制限などはクラッシュさせず
    // モックにフォールバックし、原因を meta に載せて UI に伝える。
    console.error("[xClient] X API 検索に失敗、モックに切替:", err.message);
    return { source: "mock", tweets: filterMock(search), error: err.message };
  }
}

function filterMock(search) {
  const kws = (search.keywords || []).map((k) => k.toLowerCase());
  let list = mockTweets;
  if (kws.length) {
    list = list.filter((t) => {
      const hay = (t.text + " " + t.author.description).toLowerCase();
      return kws.some((k) => hay.includes(k));
    });
  }
  return list.slice(0, search.maxResults || 30).map(normalizeMock);
}

function normalizeMock(t) {
  return {
    tweetId: t.id,
    text: t.text,
    createdAt: t.createdAt,
    tweetUrl: `https://x.com/${t.author.username}/status/${t.id}`,
    author: {
      userId: t.author.id,
      username: t.author.username,
      name: t.author.name,
      description: t.author.description,
      location: t.author.location || "",
      followers: t.author.followers ?? 0,
      profileUrl: `https://x.com/${t.author.username}`,
    },
  };
}

/**
 * X API v2 recent search を呼び出して正規化する。
 * クエリは (kw1 OR kw2 ...) lang:ja -is:retweet の形で組み立てる。
 */
async function searchWithXApi(search) {
  const query = buildQuery(search);
  const params = new URLSearchParams({
    query,
    max_results: String(clamp(search.maxResults || 30, 10, 100)),
    "tweet.fields": "created_at,author_id,lang,public_metrics",
    expansions: "author_id",
    "user.fields": "name,username,description,location,public_metrics",
  });

  const bearer = await getBearerToken();
  const res = await fetch(`${X_SEARCH_URL}?${params.toString()}`, {
    headers: { Authorization: `Bearer ${bearer}` },
  });

  if (!res.ok) {
    const body = await res.text().catch(() => "");
    throw new Error(`X API error ${res.status}: ${body.slice(0, 300)}`);
  }

  const data = await res.json();
  const users = new Map(
    (data.includes?.users || []).map((u) => [u.id, u]),
  );

  return (data.data || []).map((t) => {
    const u = users.get(t.author_id) || {};
    const username = u.username || "unknown";
    return {
      tweetId: t.id,
      text: t.text,
      createdAt: t.created_at || "",
      tweetUrl: `https://x.com/${username}/status/${t.id}`,
      author: {
        userId: t.author_id,
        username,
        name: u.name || username,
        description: u.description || "",
        location: u.location || "",
        followers: u.public_metrics?.followers_count ?? 0,
        profileUrl: `https://x.com/${username}`,
      },
    };
  });
}

function buildQuery(search) {
  const kws = (search.keywords || []).filter(Boolean);
  const orGroup = kws
    .map((k) => (k.includes(" ") ? `"${k}"` : k))
    .join(" OR ");
  let q = kws.length ? `(${orGroup})` : "配達員";
  if (search.langJa) q += " lang:ja";
  if (search.excludeRetweets) q += " -is:retweet";
  return q;
}

function clamp(n, min, max) {
  return Math.max(min, Math.min(max, n));
}

/**
 * @typedef {Object} NormalizedTweet
 * @property {string} tweetId
 * @property {string} text
 * @property {string} createdAt
 * @property {string} tweetUrl
 * @property {{userId:string,username:string,name:string,description:string,location:string,followers:number,profileUrl:string}} author
 */
