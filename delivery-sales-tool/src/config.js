import "dotenv/config";

export const config = {
  port: Number(process.env.PORT) || 3000,
  xBearerToken: process.env.X_BEARER_TOKEN || "",
  anthropicApiKey: process.env.ANTHROPIC_API_KEY || "",
  aiModel: process.env.AI_MODEL || "claude-opus-5",
};

/** X 公式 API が使えるか（未設定ならモックにフォールバック） */
export const hasXApi = () => Boolean(config.xBearerToken);

/** Claude API が使えるか（未設定ならルールベース判定にフォールバック） */
export const hasAi = () => Boolean(config.anthropicApiKey);

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
