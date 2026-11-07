package site.ragdollp.vidai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Claude Messages API に vision リクエストを投げ、
 * 「どの区間を残すか」の JSON (EDL) を返してもらうだけの軽量クライアント。
 *
 * Anthropic 公式 SDK は Android 用には提供されていないので OkHttp で生 HTTP を叩く。
 * 送信モデルは呼び出し側 (BudgetPlan) が決める。
 */
class ClaudeClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * フレーム群 + 指示を投げて、残すべき区間の配列を受け取る。
     * 失敗したら例外。
     */
    fun planEdits(
        modelId: String,
        instruction: String,
        videoDurationSec: Double,
        frames: List<FrameSampler.Frame>,
    ): List<Segment> {
        val sys = """
            You are a video editor's assistant. The user sends sampled frames of a video
            (with timestamps in seconds) and an instruction in Japanese.
            Decide which parts of the video should be KEPT in the edit.
            Return ONLY a JSON object of the form:
              {"segments":[{"start":0.0,"end":5.3,"why":"..."}, {"start":10.1,"end":18.7,"why":"..."}]}
            Rules:
              - Times are in seconds, 0 <= start < end <= ${"%.2f".format(videoDurationSec)}.
              - Segments must be sorted by "start" and must not overlap.
              - Keep only the parts that genuinely match the user's instruction.
              - It's fine to return a very short edit (even 1 segment) if that's what the instruction warrants.
              - "why" is a brief Japanese reason tag (<= 20 chars).
              - No markdown, no prose outside the JSON.
        """.trimIndent()

        val content = JSONArray()
        // 各フレームを image + 時刻テキストの 2 ブロックで送る
        for (f in frames) {
            content.put(JSONObject().apply {
                put("type", "image")
                put("source", JSONObject().apply {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", f.jpegBase64)
                })
            })
            content.put(JSONObject().apply {
                put("type", "text")
                put("text", "t=${"%.2f".format(f.tsSec)}s")
            })
        }
        content.put(JSONObject().apply {
            put("type", "text")
            put("text", "User instruction (Japanese): $instruction\n" +
                "Video duration: ${"%.2f".format(videoDurationSec)} seconds.\n" +
                "Now output the JSON described above and nothing else.")
        })

        val body = JSONObject().apply {
            put("model", modelId)
            put("max_tokens", 4096)
            put("system", sys)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = http.newCall(req).execute()
        val text = resp.body?.string() ?: ""
        if (!resp.isSuccessful) throw RuntimeException("API ${resp.code}: $text")

        // 返答の text ブロックから最初の JSON オブジェクトを抜き出す
        val json = JSONObject(text)
        val contentArr = json.getJSONArray("content")
        var combined = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val b = contentArr.getJSONObject(i)
            if (b.getString("type") == "text") combined.append(b.getString("text"))
        }
        val raw = combined.toString()
        val startIdx = raw.indexOf('{')
        val endIdx = raw.lastIndexOf('}')
        if (startIdx < 0 || endIdx < startIdx) throw RuntimeException("no JSON in response: $raw")
        val plan = JSONObject(raw.substring(startIdx, endIdx + 1))
        val segs = plan.getJSONArray("segments")
        val out = ArrayList<Segment>(segs.length())
        for (i in 0 until segs.length()) {
            val s = segs.getJSONObject(i)
            out.add(Segment(
                start = s.getDouble("start"),
                end = s.getDouble("end"),
                why = s.optString("why", "")
            ))
        }
        return out
    }

    data class Segment(val start: Double, val end: Double, val why: String)
}
