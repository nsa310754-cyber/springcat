package site.ragdollp.vidai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * 設定 UI。
 *  - API キー (SharedPreferences に保存。Keystore に移すのは余力があれば)
 *  - 保有クレジット額 → 自動で使用モデルを決める (BudgetPlan)
 *  - 編集指示 (自由テキスト: 「無音部分を飛ばして」「ハイライトだけ」「10秒くらいに縮めて」等)
 *  - 動画選択 → 実行
 */
class MainActivity : AppCompatActivity() {

    private lateinit var picked: TextView
    private lateinit var log: TextView
    private lateinit var tierLabel: TextView
    private var inputUri: Uri? = null
    private var inputName: String? = null

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            inputUri = r.data?.data
            inputName = inputUri?.lastPathSegment
            picked.text = "動画: $inputName"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("vidai", MODE_PRIVATE)

        val sv = ScrollView(this)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 72, 48, 48)
        }
        sv.addView(ll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        ll.addView(TextView(this).apply { text = "Vid-AI 動画AI編集"; textSize = 22f })
        ll.addView(TextView(this).apply {
            text = "Claude の vision でフレームを解析し、\n" +
                   "残すべき区間を決めて動画を自動編集します。"
            setPadding(0, 16, 0, 24)
        })

        ll.addView(label("Claude API キー (sk-ant-…)"))
        val keyField = EditText(this).apply {
            setText(sp.getString("key", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ll.addView(keyField)

        ll.addView(label("保有クレジット額 (USD)"))
        val credField = EditText(this).apply {
            setText(sp.getString("credit", "100") ?: "100")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        ll.addView(credField)

        tierLabel = TextView(this).apply {
            setPadding(0, 8, 0, 24); textSize = 14f
        }
        ll.addView(tierLabel)

        // 金額が変わるたびに段階を再計算
        credField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateTier(credField.text.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        updateTier(credField.text.toString())

        ll.addView(label("編集指示 (自由記述)"))
        val instField = EditText(this).apply {
            setText(sp.getString("inst",
                "動きのある見せ場だけを残して1本のハイライト動画にしてください。") ?: "")
            minLines = 3
        }
        ll.addView(instField)

        picked = TextView(this).apply { text = "動画未選択"; setPadding(0, 16, 0, 8) }
        ll.addView(picked)
        ll.addView(button("動画を選ぶ") {
            pickVideo.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
            })
        })
        ll.addView(button("AI 編集を実行") {
            sp.edit().putString("key", keyField.text.toString())
                     .putString("credit", credField.text.toString())
                     .putString("inst", instField.text.toString()).apply()
            run(keyField.text.toString().trim(),
                credField.text.toString().toDoubleOrNull() ?: 0.0,
                instField.text.toString())
        })

        log = TextView(this).apply {
            setPadding(0, 24, 0, 0); setTextIsSelectable(true); textSize = 13f
        }
        ll.addView(log)

        ll.addView(TextView(this).apply {
            text = "\n注意: API キーは端末内 SharedPreferences に保存されます。\n" +
                   "料金は Claude の API 利用料として実際に発生します。"
            setPadding(0, 32, 0, 0); setTextColor(0xFFFFCC66.toInt())
        })

        setContentView(sv)
    }

    private fun updateTier(credStr: String) {
        val c = credStr.toDoubleOrNull() ?: 0.0
        val p = BudgetPlan.pick(c)
        tierLabel.text = if (p == null)
            "⚠ \$5 未満ではモデルを選択できません"
        else
            "→ 使用モデル: ${p.tierLabel}  (${p.modelId})\n" +
            "   最大フレーム数: ${p.maxAffordableFrames(c)}, " +
            "サンプリング: ${"%.2f".format(p.framesPerSecond)} FPS"
    }

    private fun run(apiKey: String, credit: Double, inst: String) {
        if (apiKey.isBlank()) return toast("API キー未入力")
        val uri = inputUri ?: return toast("動画未選択")
        val plan = BudgetPlan.pick(credit) ?: return toast("クレジット額が足りません (\$5+)")
        appendLog("=== 開始 ===")
        appendLog("モデル: ${plan.modelId} / 予算 \$$credit / 指示: $inst")

        thread {
            try {
                appendLog("[1/3] フレーム抽出中...")
                val maxF = plan.maxAffordableFrames(credit)
                val (dur, frames) = FrameSampler.sample(contentResolver, uri,
                    plan.framesPerSecond, maxF) { cur, total ->
                    if (cur % 10 == 0 || cur == total)
                        runOnUiThread { appendLog("  frames $cur/$total") }
                }
                appendLog("  動画長 ${"%.1f".format(dur)}s / ${frames.size} フレーム抽出")

                appendLog("[2/3] Claude に送信 → 編集決定取得...")
                val client = ClaudeClient(apiKey)
                val segs = client.planEdits(plan.modelId, inst, dur, frames)
                appendLog("  返答: ${segs.size} セグメント")
                segs.forEachIndexed { i, s ->
                    appendLog("    ${i+1}: ${"%.2f".format(s.start)}s - ${"%.2f".format(s.end)}s  ${s.why}")
                }

                appendLog("[3/3] 動画カット中...")
                val outDir = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "VidAI").apply { mkdirs() }
                val outFile = File(outDir, "edited_${System.currentTimeMillis()}.mp4")
                VideoCutter.cut(contentResolver, uri, segs, outFile) { pct ->
                    if (pct % 10 == 0) runOnUiThread { appendLog("  muxing ${pct}%") }
                }
                appendLog("✅ 完了: ${outFile.absolutePath}  (${outFile.length() / 1024}KB)")
            } catch (t: Throwable) {
                appendLog("❌ ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun label(s: String) = TextView(this).apply { text = s; setPadding(0, 16, 0, 4) }
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; setPadding(24, 24, 24, 24); setOnClickListener { onClick() }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun appendLog(s: String) = runOnUiThread {
        log.text = (log.text.toString() + "\n" + s).takeLast(6000)
    }
}
