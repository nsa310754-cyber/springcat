package site.ragdollp.virusscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import site.ragdollp.virusscanner.databinding.ActivityDetectionBinding
import site.ragdollp.virusscanner.state.ScanResultsHolder
import site.ragdollp.virusscanner.ui.DetectionAdapter

/**
 * 検出欄。不審な権限の組み合わせを持つアプリの一覧を表示し、
 * 個別アンインストール・すべてアンインストールを選べる。
 *
 * 通常のアプリには他アプリをサイレントに削除する権限がないため、
 * OS標準のアンインストール確認ダイアログ(ACTION_DELETE)を1つずつ呼び出す。
 * 「すべてアンインストール」はこの確認ダイアログをキューで順番に呼び出す実装。
 */
class DetectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionBinding
    private lateinit var adapter: DetectionAdapter

    private val uninstallQueue = ArrayDeque<String>()
    private lateinit var uninstallLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        uninstallLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            processNextUninstall()
        }

        adapter = DetectionAdapter(
            items = ScanResultsHolder.results,
            onUninstallOne = { result -> confirmAndUninstall(listOf(result.packageName)) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.uninstallAllButton.setOnClickListener {
            val all = ScanResultsHolder.results.map { it.packageName }
            if (all.isNotEmpty()) confirmAndUninstall(all)
        }

        refreshEmptyState()
    }

    override fun onResume() {
        super.onResume()
        // アンインストール確認ダイアログから戻ってきた際に一覧を最新状態へ同期する。
        refreshEmptyState()
    }

    private fun confirmAndUninstall(packageNames: List<String>) {
        val message = if (packageNames.size == 1) {
            getString(R.string.confirm_uninstall_one, displayNameFor(packageNames[0]))
        } else {
            getString(R.string.confirm_uninstall_all, packageNames.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.uninstall_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.uninstall) { _, _ ->
                uninstallQueue.clear()
                uninstallQueue.addAll(packageNames)
                processNextUninstall()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun displayNameFor(packageName: String): String {
        return ScanResultsHolder.results.firstOrNull { it.packageName == packageName }?.label
            ?: packageName
    }

    private fun processNextUninstall() {
        syncResultsWithInstalledState()

        val next = uninstallQueue.removeFirstOrNull()
        if (next == null) {
            refreshEmptyState()
            return
        }
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$next"))
        uninstallLauncher.launch(intent)
    }

    private fun isInstalled(packageName: String): Boolean {
        return runCatching { packageManager.getPackageInfo(packageName, 0) }.isSuccess
    }

    private fun syncResultsWithInstalledState() {
        val before = ScanResultsHolder.results.size
        ScanResultsHolder.results.removeAll { !isInstalled(it.packageName) }
        if (ScanResultsHolder.results.size != before) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun refreshEmptyState() {
        syncResultsWithInstalledState()
        binding.emptyText.visibility =
            if (ScanResultsHolder.results.isEmpty()) View.VISIBLE else View.GONE
        binding.uninstallAllButton.isEnabled = ScanResultsHolder.results.isNotEmpty()
    }
}
