import crypto from "crypto";
import { collectTweets } from "./xClient.js";
import { analyzeCandidates } from "./aiClient.js";
import { upsertCandidates } from "./store.js";

/**
 * 検索 → AI 判定 → 一覧化 の一連のパイプライン。
 * 検索条件でフィルタした候補者リストを保存して返す。
 *
 * @param {Object} search 検索条件（store.getConfig() の形）
 */
export async function runPipeline(search) {
  // 1. X から投稿を収集
  const { source: tweetSource, tweets, error: xError } = await collectTweets(search);

  // 2. まとめて AI 判定
  const { source: aiSource, analyses } = await analyzeCandidates(tweets, search);

  // 3. 候補者レコードに整形
  let candidates = tweets.map((t, i) => {
    const a = analyses[i] || {};
    return {
      id: crypto.randomUUID(),
      username: t.author.username,
      name: t.author.name,
      profileUrl: t.author.profileUrl,
      tweetUrl: t.tweetUrl,
      tweetText: t.text,
      bio: t.author.description,
      followers: t.author.followers,
      estimatedRegion: a.region || t.author.location || "",
      estimatedService: a.service || "",
      aiScore: a.score ?? 0,
      aiReason: a.reason || "",
      isTarget: Boolean(a.isTarget),
      dmSent: false,
      replyReceived: false,
      dmText: "",
      collectedAt: new Date().toISOString(),
    };
  });

  // 4. フィルタ: 最小スコア・地域
  const minScore = Number(search.minScore) || 0;
  candidates = candidates.filter((c) => c.aiScore >= minScore);

  const regions = (search.regions || []).filter(Boolean);
  if (regions.length) {
    candidates = candidates.filter((c) =>
      regions.some((r) => (c.estimatedRegion || "").includes(r)),
    );
  }

  // 5. 保存（既存の DM/返信ステータスはマージで維持）
  const saved = upsertCandidates(candidates);

  return {
    meta: {
      tweetSource, // 'x-api' | 'mock'
      aiSource, // 'ai' | 'heuristic'
      xError: xError || null, // X API 失敗時の原因（402/403 等）
      collected: tweets.length,
      matched: candidates.length,
      total: saved.length,
    },
    candidates: saved,
  };
}
