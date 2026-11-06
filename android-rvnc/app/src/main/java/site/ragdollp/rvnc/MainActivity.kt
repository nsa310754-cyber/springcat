package site.ragdollp.rvnc

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("rvnc", MODE_PRIVATE)

        val sv = ScrollView(this)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 72, 48, 48)
        }
        sv.addView(ll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        ll.addView(TextView(this).apply { text = "PCリモート (VNC)"; textSize = 22f })
        ll.addView(TextView(this).apply {
            text = "PC 側に VNC サーバ (TightVNC など) を入れておいてください。\n" +
                   "同じ Wi-Fi で、PC の IP アドレスとポート 5900、設定したパスワードを入力。"
            setPadding(0, 16, 0, 24)
        })

        ll.addView(label("PC の IP アドレス"))
        val host = edit(sp.getString("host", "192.168.") ?: "")
        ll.addView(host)

        ll.addView(label("ポート (TightVNC 既定: 5900)"))
        val port = edit(sp.getString("port", "5900") ?: "5900").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        ll.addView(port)

        ll.addView(label("パスワード (VNC 認証のもの)"))
        val pw = edit(sp.getString("pw", "") ?: "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        ll.addView(pw)

        ll.addView(Button(this).apply {
            text = "接続"
            setPadding(24, 24, 24, 24)
            setOnClickListener {
                val h = host.text.toString().trim()
                val p = port.text.toString().toIntOrNull() ?: 5900
                val k = pw.text.toString()
                if (h.isBlank()) { toast("IP を入力してください"); return@setOnClickListener }
                sp.edit()
                    .putString("host", h).putString("port", p.toString()).putString("pw", k)
                    .apply()
                startActivity(Intent(this@MainActivity, VncActivity::class.java).apply {
                    putExtra("host", h); putExtra("port", p); putExtra("pw", k)
                })
            }
        })

        ll.addView(TextView(this).apply {
            text = "\n【PC 側の設定 (TightVNC の場合)】\n" +
                   "1. tightvnc.com から Windows 版をインストール\n" +
                   "2. 「TightVNC Service」を実行、Primary password を 8 文字以内で設定\n" +
                   "3. Windows の Firewall でポート 5900 を許可\n" +
                   "4. PC の IP: cmd で ipconfig → IPv4 アドレス"
            setPadding(0, 32, 0, 0)
        })

        setContentView(sv)
    }

    private fun label(s: String) = TextView(this).apply { text = s; setPadding(0, 20, 0, 4) }
    private fun edit(initial: String) = EditText(this).apply { setText(initial) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
