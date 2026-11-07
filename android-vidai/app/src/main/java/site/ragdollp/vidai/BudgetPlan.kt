package site.ragdollp.vidai

/**
 * ユーザーが保有すると申告したクレジット (USD) に応じて使うモデルと
 * フレームサンプリング密度を決める。
 *
 * 要件 (ユーザー指定):
 *   $100+  → Opus 5.5    (claude-opus-5-5)
 *   $50+   → Sonnet 5.5  (claude-sonnet-5-5)
 *   $25+   → Haiku 5.5   (claude-haiku-5-5)
 *   $5+    → Haiku 4.5   (claude-haiku-4-5)   ※ユーザーが書いた "HAIKU 4.8" は存在しないので代替
 *   <$5    → 不可
 *
 * 価格 ($/1Mトークン, 2026-11 時点):
 *   opus-5-5   : in 4.00 / out 20.00
 *   sonnet-5-5 : in 2.00 / out 10.00
 *   haiku-5-5  : in 0.10 / out  0.50
 *   haiku-4-5  : in 1.00 / out  5.00
 */
data class BudgetPlan(
    val modelId: String,
    val tierLabel: String,
    val inPricePerMTok: Double,
    val outPricePerMTok: Double,
    /** 動画 1 秒あたり何フレームを解析するか (0.5 = 2 秒に 1 枚) */
    val framesPerSecond: Double,
    /** 1 フレーム (小さい JPEG) を送るおよそのトークン数 */
    val tokensPerFrame: Int = 1200,
) {
    /** この予算で何フレームまで送れるかを概算 (入力コストのみ、半分だけ使う前提) */
    fun maxAffordableFrames(creditUsd: Double): Int {
        val budget = creditUsd * 0.5          // 半分までを入力 (vision) に使うポリシー
        val perFrame = inPricePerMTok * tokensPerFrame / 1_000_000.0
        if (perFrame <= 0) return 500
        return (budget / perFrame).toInt().coerceAtMost(500)
    }

    companion object {
        /** 予算から段階を決める。足りない場合は null。 */
        fun pick(creditUsd: Double): BudgetPlan? = when {
            creditUsd >= 100.0 -> BudgetPlan(
                modelId = "claude-opus-5-5", tierLabel = "Opus 5.5 (最高精度)",
                inPricePerMTok = 4.0, outPricePerMTok = 20.0,
                framesPerSecond = 0.5)                     // 2秒に1枚
            creditUsd >= 50.0  -> BudgetPlan(
                modelId = "claude-sonnet-5-5", tierLabel = "Sonnet 5.5 (高精度)",
                inPricePerMTok = 2.0, outPricePerMTok = 10.0,
                framesPerSecond = 0.33)                    // 3秒に1枚
            creditUsd >= 25.0  -> BudgetPlan(
                modelId = "claude-haiku-5-5", tierLabel = "Haiku 5.5 (標準)",
                inPricePerMTok = 0.10, outPricePerMTok = 0.50,
                framesPerSecond = 0.2)                     // 5秒に1枚
            creditUsd >= 5.0   -> BudgetPlan(
                modelId = "claude-haiku-4-5", tierLabel = "Haiku 4.5 (節約)",
                inPricePerMTok = 1.0, outPricePerMTok = 5.0,
                framesPerSecond = 0.1)                     // 10秒に1枚
            else -> null
        }
    }
}
