package site.ragdollp.kidsguard.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import site.ragdollp.kidsguard.R
import site.ragdollp.kidsguard.data.Prefs

/**
 * ランチャから最初に開く画面。
 *  - PIN 未設定なら「新規設定」モード
 *  - 設定済みなら「入力」モード
 * 認証後に MainActivity (または初期設定ウィザード) へ遷移。
 */
class PinActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.hasPin(this)) showEnter() else showSetup()
    }

    private fun showSetup() {
        val root = basicLayout()
        root.addView(title(getString(R.string.pin_setup_title)))
        root.addView(label(getString(R.string.pin_setup_desc)))
        val p1 = pinField(getString(R.string.pin_hint))
        val p2 = pinField(getString(R.string.confirm))
        root.addView(p1); root.addView(p2)
        val btn = Button(this).apply { text = getString(R.string.save) }
        btn.setOnClickListener {
            val a = p1.text.toString(); val b = p2.text.toString()
            if (a.length != 4 || !a.all { it.isDigit() }) {
                toast("4桁の数字で入力してください"); return@setOnClickListener
            }
            if (a != b) { toast(getString(R.string.pin_mismatch)); return@setOnClickListener }
            Prefs.setPin(this, a)
            goNext()
        }
        root.addView(btn)
        setContentView(root)
    }

    private fun showEnter() {
        val root = basicLayout()
        root.addView(title(getString(R.string.pin_enter_title)))
        val p = pinField(getString(R.string.pin_hint))
        root.addView(p)
        val btn = Button(this).apply { text = getString(R.string.ok) }
        btn.setOnClickListener {
            if (Prefs.verifyPin(this, p.text.toString())) goNext()
            else { toast(getString(R.string.pin_wrong)); p.setText("") }
        }
        root.addView(btn)
        setContentView(root)
    }

    private fun goNext() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun basicLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 96, 48, 48)
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 22f
        setPadding(0, 0, 0, 32)
    }
    private fun label(t: String) = TextView(this).apply {
        text = t; setPadding(0, 0, 0, 24)
    }
    private fun pinField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        textSize = 28f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            bottomMargin = 24
        }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
