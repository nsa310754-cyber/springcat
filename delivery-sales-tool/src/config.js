import "dotenv/config";

const anthropicApiKey = process.env.ANTHROPIC_API_KEY || "";
const geminiApiKey = process.env.GEMINI_API_KEY || "";

// AI プロバイダの決定。AI_PROVIDER が指定されていればそれを優先し、
// 未指定ならキーの有無から自動判定する。
let aiProvider = (process.env.AI_PROVIDER || "").toLowerCase();
if (!aiProvider) {
  if (anthropicApiKey) aiProvider = "anthropic";
  else if (geminiApiKey) aiProvider = "gemini";
  else aiProvider = "none";
}

export const config = {
  port: Number(process.env.PORT) || 3000,
  xBearerToken: process.env.X_BEARER_TOKEN || "",

  // AI 共通
  aiProvider, // 'anthropic' | 'gemini' | 'none'

  // Anthropic (Claude)
  anthropicApiKey,
  anthropicModel: process.env.AI_MODEL || "claude-opus-5",

  // Google (Gemini)
  geminiApiKey,
  geminiModel: process.env.GEMINI_MODEL || "gemini-3.6-flash",
};

/** X 公式 API が使えるか（未設定ならモックにフォールバック） */
export const hasXApi = () => Boolean(config.xBearerToken);

/** AI 判定が使えるか（プロバイダに対応するキーがあるか。無ければルールベース） */
export const hasAi = () => {
  if (config.aiProvider === "anthropic") return Boolean(config.anthropicApiKey);
  if (config.aiProvider === "gemini") return Boolean(config.geminiApiKey);
  return false;
};

/** 現在の AI モデル名（表示用） */
export const aiModelName = () => {
  if (config.aiProvider === "anthropic") return config.anthropicModel;
  if (config.aiProvider === "gemini") return config.geminiModel;
  return "";
};

/**
 * デフォルトの検索・抽出条件。管理画面から上書きできる。
 */
export const defaultSearchConfig = {
  // 収集キーワード（いずれかを含む投稿を収集）
  keywords: [
    "ロケットナウ",
    "Uber Eats",
    "出前館",
    "配達員",
    "フーデリ",
    "単価低い",
    "鳴らない",
    "稼げない",
  ],
  // 地域フィルタ（空なら全地域。例: ["大阪", "東京"]）
  regions: [],
  // 収集件数の上限
  maxResults: 30,
  // 営業対象とみなす最小スコア（0-100）
  minScore: 50,
  // 除外リツイート・日本語のみ 等の検索オプション
  langJa: true,
  excludeRetweets: true,
  // 自社が売り込みたいサービス名（DM 生成で使用）
  ourService: "ロケットナウ",
  ourPitch: "高単価・鳴りやすいエリアで稼ぎやすい新しいフードデリバリー",
};
