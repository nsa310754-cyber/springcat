package site.ragdollp.kidsguard.ui

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import site.ragdollp.kidsguard.R
import site.ragdollp.kidsguard.admin.KidsAdminReceiver
import site.ragdollp.kidsguard.service.GuardAccessibilityService

/**
 * 認証後のメイン画面。
 *  - 必要な権限の状態表示 & 設定画面への誘導
 *  - ブロック対象アプリの選択へ移動
 *  - デバイス管理者の解除 (アプリ本体のアンインストール前に必要)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComp: ComponentName
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComp = KidsAdminReceiver.component(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        root.addView(TextView(this).apply { text = "安心フィルター"; textSize = 24f })
        status = TextView(this).apply {
            setPadding(0, 24, 0, 24); textSize = 14f
        }
        root.addView(status)

        root.addView(bigButton(getString(R.string.grant_admin)) { requestAdmin() })
        root.addView(bigButton(getString(R.string.grant_usage)) { openUsageSettings() })
        root.addView(bigButton(getString(R.string.grant_a11y)) { openA11ySettings() })
        root.addView(bigButton(getString(R.string.grant_overlay)) { openOverlaySettings() })

        root.addView(divider())

        root.addView(bigButton(getString(R.string.pick_apps)) {
            startActivity(Intent(this, AppListActivity::class.java))
        })
        root.addView(bigButton(getString(R.string.disable_admin)) { disableAdmin() })

        setContentView(root)
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun refreshStatus() {
        val admin = dpm.isAdminActive(adminComp)
        val a11y = isA11yEnabled()
        val usage = hasUsageAccess()
        val overlay = Settings.canDrawOverlays(this)
        status.text = """
            デバイス管理者 : ${mark(admin)}
            使用履歴アクセス: ${mark(usage)}
            ユーザー補助  : ${mark(a11y)}
            オーバーレイ  : ${mark(overlay)}
        """.trimIndent()
    }
    private fun mark(b: Boolean) = if (b) "✅ 有効" else "❌ 未設定"

    private fun requestAdmin() {
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_description))
        }
        startActivity(i)
    }
    private fun disableAdmin() {
        if (dpm.isAdminActive(adminComp)) dpm.removeActiveAdmin(adminComp)
    }
    private fun openUsageSettings() =
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    private fun openA11ySettings() =
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    private fun openOverlaySettings() =
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))

    private fun isA11yEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val target = "$packageName/${GuardAccessibilityService::class.java.name}"
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { it.id == target }
    }

    private fun hasUsageAccess(): Boolean {
        // UsageStats パーミッションは AppOps で判定するのが定石
        return try {
            val appOps = getSystemService(APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(
                android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), packageName
            )
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) { false }
    }

    private fun bigButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setPadding(24, 24, 24, 24)
        setOnClickListener { onClick() }
    }
    private fun divider() = TextView(this).apply {
        setPadding(0, 32, 0, 16)
        text = "―――――"
        gravity = Gravity.CENTER
    }
}
