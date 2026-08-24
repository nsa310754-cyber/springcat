import Anthropic from "@anthropic-ai/sdk";
import { config, hasAi } from "./config.js";

let client = null;
function getClient() {
  if (!client) client = new Anthropic({ apiKey: config.anthropicApiKey });
  return client;
}

/** 既知の地域名（推定のヒント / フォールバック用） */
const REGION_HINTS = [
  "北海道", "青森", "岩手", "宮城", "秋田", "山形", "福島",
  "東京", "神奈川", "横浜", "川崎", "千葉", "埼玉", "茨城", "栃木", "群馬",
  "愛知", "名古屋", "岐阜", "三重", "静岡",
  "大阪", "京都", "兵庫", "神戸", "奈良", "滋賀", "和歌山",
  "広島", "岡山", "福岡", "北九州", "熊本", "鹿児島", "沖縄", "宮崎", "大分", "長崎", "佐賀",
  "新潟", "石川", "富山", "福井", "長野", "山梨",
];

/** 配達サービス名（推定のヒント / フォールバック用） */
const SERVICE_HINTS = [
  "Uber Eats", "ウーバー", "Uber", "出前館", "ロケットナウ", "Wolt", "menu", "foodpanda",
];

/**
 * 収集した投稿をまとめて AI 判定する。
 * ANTHROPIC_API_KEY があれば Claude、無ければルールベースにフォールバック。
 *
 * @param {import('./xClient.js').NormalizedTweet[]} tweets
 * @param {{ourService:string}} search
 * @returns {Promise<{source:'ai'|'heuristic', analyses:Analysis[]}>}
 */
export async function analyzeCandidates(tweets, search) {
  if (!tweets.length) return { source: hasAi() ? "ai" : "heuristic", analyses: [] };
  if (!hasAi()) {
    return { source: "heuristic", analyses: tweets.map((t) => heuristicAnalyze(t)) };
  }
  try {
    const analyses = await aiAnalyze(tweets, search);
    return { source: "ai", analyses };
  } catch (err) {
    console.error("[aiClient] AI 判定に失敗、ルールベースに切替:", err.message);
    return { source: "heuristic", analyses: tweets.map((t) => heuristicAnalyze(t)) };
  }
}

async function aiAnalyze(tweets, search) {
  const items = tweets.map((t, i) => ({
    index: i,
    tweet: t.text,
    profile: t.author.description,
    location: t.author.location,
    followers: t.author.followers,
  }));

  const system = [
    "あなたはフードデリバリー会社の営業リサーチャーです。",
    `自社は「${search.ourService}」という配達サービスで、新しい配達員を獲得したいと考えています。`,
    "X（旧Twitter）の投稿とプロフィールから、配達員として活動している/興味がありそうで、",
    "他社サービスへの不満（単価が低い・鳴らない・稼げない等）を持ち、乗り換え営業の対象になりそうな人物を評価してください。",
    "各投稿について次の項目を推定します:",
    "- isTarget: 営業対象になりそうか (true/false)",
    "- score: 営業優先度 0〜100 の整数（不満が強く配達員である可能性が高いほど高得点）",
    "- region: 推定地域（都道府県または市名。不明なら空文字）",
    "- service: 現在利用中と推定される配達サービス（複数可、カンマ区切り。不明なら空文字）",
    "- reason: なぜ営業対象と判断したか（日本語で簡潔に1〜2文）",
    "必ず指定の JSON 形式のみで出力してください。前置きや説明文は不要です。",
  ].join("\n");

  const userMsg = [
    "以下の投稿を評価し、JSON で返してください。",
    'フォーマット: {"results":[{"index":0,"isTarget":true,"score":75,"region":"大阪","service":"Uber Eats,出前館","reason":"..."}]}',
    "",
    JSON.stringify(items, null, 2),
  ].join("\n");

  const response = await getClient().messages.create({
    model: config.aiModel,
    max_tokens: 8000,
    system,
    messages: [{ role: "user", content: userMsg }],
  });

  const text = response.content
    .filter((b) => b.type === "text")
    .map((b) => b.text)
    .join("");

  const parsed = extractJson(text);
  const byIndex = new Map((parsed.results || []).map((r) => [r.index, r]));

  return tweets.map((_, i) => {
    const r = byIndex.get(i) || {};
    return {
      isTarget: Boolean(r.isTarget),
      score: clampScore(r.score),
      region: String(r.region || ""),
      service: String(r.service || ""),
      reason: String(r.reason || "判定理由が取得できませんでした。"),
    };
  });
}

/** コードフェンス等を除去して最初の JSON オブジェクトを取り出す */
function extractJson(text) {
  const fenced = text.match(/```(?:json)?\s*([\s\S]*?)```/);
  const raw = fenced ? fenced[1] : text;
  const start = raw.indexOf("{");
  const end = raw.lastIndexOf("}");
  if (start === -1 || end === -1) throw new Error("JSON が見つかりません: " + text.slice(0, 200));
  return JSON.parse(raw.slice(start, end + 1));
}

/**
 * ルールベースの簡易判定（API キーが無いとき用）。
 * キーワードの一致数からスコアを算出し、地域・サービスは文字列マッチで推定する。
 */
export function heuristicAnalyze(tweet) {
  const text = tweet.text || "";
  const bio = tweet.author?.description || "";
  const hay = (text + " " + bio).toLowerCase();

  const painWords = ["単価低い", "単価が低い", "鳴らない", "稼げない", "きつい", "改悪", "やってられん", "泣ける", "下がった"];
  const jobWords = ["配達員", "配達", "フーデリ", "デリバリー", "専業", "掛け持ち", "かけもち", "ライダー"];

  const painHits = painWords.filter((w) => hay.includes(w.toLowerCase())).length;
  const jobHits = jobWords.filter((w) => hay.includes(w.toLowerCase())).length;

  const service = SERVICE_HINTS.filter((s) => hay.includes(s.toLowerCase()))
    // 表記ゆれを Uber Eats に寄せる
    .map((s) => (/uber|ウーバー/i.test(s) ? "Uber Eats" : s))
    .filter((v, i, a) => a.indexOf(v) === i)
    .join(",");

  const region =
    REGION_HINTS.find((r) => text.includes(r) || bio.includes(r) || (tweet.author?.location || "").includes(r)) ||
    (tweet.author?.location || "");

  // スコア: 職業シグナル最大40 + 不満シグナル最大40 + サービス言及20
  let score = Math.min(jobHits * 20, 40) + Math.min(painHits * 20, 40) + (service ? 20 : 0);
  score = clampScore(score);

  const isTarget = jobHits > 0 && score >= 50;

  const reasonParts = [];
  if (jobHits) reasonParts.push("配達員関連の語を含む");
  if (painHits) reasonParts.push("他社サービスへの不満が見られる");
  if (service) reasonParts.push(`利用中サービス: ${service}`);
  const reason = reasonParts.length
    ? reasonParts.join("／") + "ため営業対象候補と判定。"
    : "配達員である明確なシグナルが弱いため優先度は低め。";

  return { isTarget, score, region, service, reason };
}

/**
 * 候補者ごとに営業 DM 文章を生成する。
 * @param {Object} candidate 候補者レコード
 * @param {{ourService:string, ourPitch:string}} search
 * @returns {Promise<string>}
 */
export async function generateDm(candidate, search) {
  if (!hasAi()) return heuristicDm(candidate, search);
  try {
    const system = [
      "あなたはフードデリバリー会社の丁寧で誠実な営業担当者です。",
      `自社サービス「${search.ourService}」（${search.ourPitch}）への登録を、X の DM で個別に案内する文章を作成します。`,
      "条件:",
      "- 相手の投稿内容・状況（不満や地域）に触れて共感から入る",
      "- 押し付けがましくなく、丁寧語で、200文字前後",
      "- 誇大表現や虚偽の約束はしない",
      "- 絵文字は多用しない（0〜1個）",
      "- 文章のみを出力し、前置きや解説は書かない",
    ].join("\n");

    const user = [
      `相手のアカウント名: ${candidate.name}`,
      `プロフィール: ${candidate.bio}`,
      `該当投稿: ${candidate.tweetText}`,
      `推定地域: ${candidate.estimatedRegion || "不明"}`,
      `推定利用サービス: ${candidate.estimatedService || "不明"}`,
      "この相手に送る営業 DM を1通作成してください。",
    ].join("\n");

    const response = await getClient().messages.create({
      model: config.aiModel,
      max_tokens: 1000,
      system,
      messages: [{ role: "user", content: user }],
    });

    return response.content
      .filter((b) => b.type === "text")
      .map((b) => b.text)
      .join("")
      .trim();
  } catch (err) {
    console.error("[aiClient] DM 生成に失敗、テンプレートに切替:", err.message);
    return heuristicDm(candidate, search);
  }
}

/** テンプレートベースの DM（API キーが無いとき用） */
export function heuristicDm(candidate, search) {
  const name = candidate.name || "配達員";
  const region = candidate.estimatedRegion ? `${candidate.estimatedRegion}エリアで` : "";
  const svc = candidate.estimatedService ? `${candidate.estimatedService}を` : "他社サービスを";
  return [
    `${name} さん、はじめまして。突然のご連絡失礼いたします。`,
    `${svc}お使いの配達員の方の投稿を拝見しました。`,
    `私たちは${search.ourService}（${search.ourPitch}）を運営しており、${region}稼働いただける配達員の方を募集しています。`,
    "単価や案件数でお役に立てるかもしれません。もしご興味あれば、詳細をお送りします。ご検討いただけると嬉しいです。",
  ].join("");
}

function clampScore(n) {
  const v = Math.round(Number(n));
  if (Number.isNaN(v)) return 0;
  return Math.max(0, Math.min(100, v));
}

/**
 * @typedef {Object} Analysis
 * @property {boolean} isTarget
 * @property {number} score
 * @property {string} region
 * @property {string} service
 * @property {string} reason
 */
