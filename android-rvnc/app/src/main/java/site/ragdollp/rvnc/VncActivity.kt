package site.ragdollp.rvnc

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class VncActivity : AppCompatActivity() {

    private lateinit var client: RfbClient
    private lateinit var view: VncView
    private lateinit var status: TextView
    private var bmp: Bitmap? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        view = VncView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(view)

        status = TextView(this).apply {
            text = "接続中..."
            setBackgroundColor(0xAA000000.toInt()); setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 16, 24, 16)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(status)

        // ツールバー (下部): キーボード表示 / Esc / Ctrl+Alt+Del / 切断
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.gravity = android.view.Gravity.BOTTOM
            layoutParams = lp
        }
        bar.addView(barBtn("⌨") { toggleKeyboard() })
        bar.addView(barBtn("Esc") { tapKey(0xff1b) })
        bar.addView(barBtn("Tab") { tapKey(0xff09) })
        bar.addView(barBtn("Win") { tapKey(0xffeb) })
        bar.addView(barBtn("Ctrl+Alt+Del") {
            client.sendKey(0xffe3, true)           // Ctrl
            client.sendKey(0xffe9, true)           // Alt
            client.sendKey(0xffff, true)           // Delete
            client.sendKey(0xffff, false)
            client.sendKey(0xffe9, false)
            client.sendKey(0xffe3, false)
        })
        bar.addView(barBtn("切断") { finish() })
        root.addView(bar)

        setContentView(root)

        view.onPointer = { x, y, mask -> if (::client.isInitialized) client.sendPointer(x, y, mask) }

        val host = intent.getStringExtra("host")!!
        val port = intent.getIntExtra("port", 5900)
        val pw = intent.getStringExtra("pw")

        thread {
            try {
                client = RfbClient(host, port, pw)
                client.connect()
                runOnUiThread {
                    status.text = "接続成功: ${client.serverName}  ${client.fbWidth}x${client.fbHeight}"
                    bmp = Bitmap.createBitmap(client.fbWidth, client.fbHeight, Bitmap.Config.ARGB_8888)
                    view.bitmap = bmp
                }
                client.requestFull()
                running = true
                while (running) {
                    client.readMessage(bmp!!)
                    client.requestIncremental()
                    runOnUiThread { view.invalidate() }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    status.text = "エラー: ${t.javaClass.simpleName}: ${t.message}"
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun barBtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setPadding(8, 8, 8, 8)
        layoutParams = LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun tapKey(keysym: Int) {
        if (!::client.isInitialized) return
        client.sendKey(keysym, true); client.sendKey(keysym, false)
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        view.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return handleKey(keyCode, event, true) || super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return handleKey(keyCode, event, false) || super.onKeyUp(keyCode, event)
    }

    /** Android キーコード & Unicode 文字 → X11 keysym 変換 */
    private fun handleKey(keyCode: Int, event: KeyEvent?, down: Boolean): Boolean {
        if (!::client.isInitialized) return false
        val ks = keyCodeToKeysym(keyCode, event) ?: return false
        client.sendKey(ks, down)
        return true
    }

    private fun keyCodeToKeysym(code: Int, e: KeyEvent?): Int? {
        // 特殊キーの直接マッピング
        val special = mapOf(
            KeyEvent.KEYCODE_DEL to 0xff08,       // BackSpace
            KeyEvent.KEYCODE_FORWARD_DEL to 0xffff,
            KeyEvent.KEYCODE_ENTER to 0xff0d,
            KeyEvent.KEYCODE_TAB to 0xff09,
            KeyEvent.KEYCODE_ESCAPE to 0xff1b,
            KeyEvent.KEYCODE_DPAD_UP to 0xff52,
            KeyEvent.KEYCODE_DPAD_DOWN to 0xff54,
            KeyEvent.KEYCODE_DPAD_LEFT to 0xff51,
            KeyEvent.KEYCODE_DPAD_RIGHT to 0xff53,
            KeyEvent.KEYCODE_SHIFT_LEFT to 0xffe1,
            KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe2,
            KeyEvent.KEYCODE_CTRL_LEFT to 0xffe3,
            KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe4,
            KeyEvent.KEYCODE_ALT_LEFT to 0xffe9,
            KeyEvent.KEYCODE_ALT_RIGHT to 0xffea,
            KeyEvent.KEYCODE_SPACE to 0x20,
        )
        special[code]?.let { return it }
        // Unicode 文字が取れるならそれを keysym にする (基本は Latin1 = そのまま)
        val uc = e?.unicodeChar ?: 0
        if (uc != 0) {
            return if (uc < 0x100) uc else uc + 0x01000000  // X11 の「Unicode 直接」規則
        }
        return null
    }

    override fun onDestroy() {
        running = false
        if (::client.isInitialized) client.close()
        super.onDestroy()
    }
}
