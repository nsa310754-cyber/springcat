package site.ragdollp.kidsguard.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import site.ragdollp.kidsguard.data.Prefs

/**
 * インストール済みアプリ一覧 (ランチャから起動できるもの) を表示し、
 * チェックしたパッケージを Prefs に保存する。
 * 自分自身と Play ストア・設定アプリは誤操作防止のため除外。
 */
class AppListActivity : AppCompatActivity() {

    private val excluded = setOf(
        "com.android.settings",
        "com.android.vending",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pm = packageManager
        val self = packageName

        val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val items = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != self && it.packageName !in excluded }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val blocked = Prefs.getBlocked(this).toMutableSet()

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AppListActivity)
            adapter = Adapter(items, blocked, pm) { pkg, on ->
                if (on) blocked.add(pkg) else blocked.remove(pkg)
                Prefs.setBlocked(this@AppListActivity, blocked)
            }
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "  ブロックするアプリを選択"
            textSize = 18f; setPadding(24, 32, 24, 24)
        })
        root.addView(rv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private class Adapter(
        private val items: List<ApplicationInfo>,
        private val blocked: MutableSet<String>,
        private val pm: PackageManager,
        private val onToggle: (String, Boolean) -> Unit,
    ) : RecyclerView.Adapter<Adapter.VH>() {

        class VH(val row: LinearLayout, val icon: ImageView,
                 val label: TextView, val cb: CheckBox) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 20, 24, 20)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val icon = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(96, 96).apply { rightMargin = 24 }
            }
            val label = TextView(ctx).apply {
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val cb = CheckBox(ctx)
            row.addView(icon); row.addView(label); row.addView(cb)
            return VH(row, icon, label, cb)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val info = items[pos]
            h.label.text = pm.getApplicationLabel(info).toString()
            h.icon.setImageDrawable(pm.getApplicationIcon(info))
            h.cb.setOnCheckedChangeListener(null)
            h.cb.isChecked = info.packageName in blocked
            h.cb.setOnCheckedChangeListener { _, on -> onToggle(info.packageName, on) }
            h.row.setOnClickListener { h.cb.isChecked = !h.cb.isChecked }
        }

        override fun getItemCount() = items.size
    }
}
