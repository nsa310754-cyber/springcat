package site.ragdollp.kidsguard.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import site.ragdollp.kidsguard.R

/**
 * ブロック対象アプリが前面に出た瞬間に重ねて表示する全画面ダイアログ。
 * 戻るボタンでも閉じられないようにし、「ホームへ戻る」のみ許可。
 */
class BlockActivity : AppCompatActivity() {

    companion object { const val EXTRA_PKG = "pkg" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val pkg = intent.getStringExtra(EXTRA_PKG) ?: "?"
        val label = runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 96, 48, 96)
        }
        root.addView(TextView(this).apply {
            text = "🚫"; textSize = 72f; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.block_title); textSize = 24f
            gravity = Gravity.CENTER; setPadding(0, 24, 0, 8)
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "$label\n${getString(R.string.block_desc)}"
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 48)
            setTextColor(0xFFEEEEEE.toInt())
        })
        root.addView(Button(this).apply {
            text = getString(R.string.back_home)
            setOnClickListener { goHome() }
        })
        setContentView(root)
    }

    override fun onBackPressed() { goHome() }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
        finish()
    }
}
