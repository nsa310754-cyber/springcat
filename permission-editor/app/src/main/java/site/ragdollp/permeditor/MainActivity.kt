package site.ragdollp.permeditor

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.ragdollp.permeditor.databinding.ActivityMainBinding
import site.ragdollp.permeditor.databinding.ItemPermissionBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var cachedApkFile: File? = null
    private var pendingOutputApk: File? = null
    private var originalFileName: String = ""
    private var targetPackageName: String = ""
    private var originalPermissions: List<String> = emptyList()
    private val removed = mutableSetOf<String>()
    private val added = mutableListOf<Pair<String, String>>() // name, tag

    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handlePickedUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickApk.setOnClickListener { pickApkLauncher.launch(arrayOf("*/*")) }
        binding.btnReset.setOnClickListener { resetState() }
        binding.btnAdd.setOnClickListener { tryAddPermission(binding.addInput.text.toString()) }
        binding.btnApply.setOnClickListener { onApplyClicked() }
    }

    private fun showError(msg: String) {
        binding.errorText.text = msg
        binding.errorText.visibility = View.VISIBLE
    }

    private fun clearError() {
        binding.errorText.visibility = View.GONE
    }

    private fun handlePickedUri(uri: Uri) {
        clearError()
        binding.resultBox.visibility = View.GONE
        try {
            val name = queryDisplayName(uri) ?: "selected.apk"
            if (!name.endsWith(".apk", ignoreCase = true)) {
                showError(".apk ファイルを選んでください。")
                return
            }
            val dest = File(cacheDir, "input_${System.currentTimeMillis()}.apk")
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                showError("ファイルを読み込めませんでした。")
                return
            }

            val pkgInfo = packageManager.getPackageArchiveInfo(dest.absolutePath, PackageManager.GET_PERMISSIONS)
            if (pkgInfo == null) {
                showError("AndroidManifest.xml を読み取れませんでした。APKファイルとして正しくないようです。")
                dest.delete()
                return
            }

            cachedApkFile = dest
            originalFileName = name
            targetPackageName = pkgInfo.packageName ?: "(不明)"
            originalPermissions = (pkgInfo.requestedPermissions ?: emptyArray()).toList()
            removed.clear()
            added.clear()

            renderAll(dest.length())
            binding.summaryCard.visibility = View.VISIBLE
            binding.permCard.visibility = View.VISIBLE
            binding.addCard.visibility = View.VISIBLE
            binding.applyCard.visibility = View.VISIBLE
        } catch (e: Exception) {
            showError("APKの読み込みに失敗しました: ${e.message}")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst()) cursor.getString(idx) else uri.lastPathSegment
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun renderAll(fileSize: Long) {
        binding.summaryFileName.text = "ファイル名: $originalFileName"
        binding.summarySize.text = "サイズ: ${"%.2f".format(fileSize / 1024.0 / 1024.0)} MB"
        binding.summaryPackage.text = "パッケージ名: $targetPackageName"
        binding.summaryCount.text = "要求している権限: ${originalPermissions.size} 件"
        renderPermList()
        renderSuggestions()
        renderAddedChips()
    }

    private fun activePermissionNames(): Set<String> {
        val active = originalPermissions.filterNot { removed.contains(it) }.toMutableSet()
        active.addAll(added.map { it.first })
        return active
    }

    private fun renderPermList() {
        binding.permListContainer.removeAllViews()
        for (name in originalPermissions) {
            val row = ItemPermissionBinding.inflate(LayoutInflater.from(this), binding.permListContainer, false)
            row.permName.text = name
            val info = PermissionCatalog.CATALOG[name]
            if (info != null) {
                row.permLabel.text = info.label
                row.permLabel.visibility = View.VISIBLE
                row.dangerBadge.visibility = if (info.danger) View.VISIBLE else View.GONE
            } else {
                row.permLabel.visibility = View.GONE
                row.dangerBadge.visibility = View.GONE
            }
            row.checkbox.isChecked = !removed.contains(name)
            row.checkbox.setOnCheckedChangeListener { _, checked ->
                if (checked) removed.remove(name) else removed.add(name)
                renderSuggestions()
            }
            binding.permListContainer.addView(row.root)
        }
    }

    private fun renderSuggestions() {
        binding.suggestionChips.removeAllViews()
        val have = activePermissionNames()
        for (name in PermissionCatalog.COMMON_ADDABLE) {
            if (have.contains(name)) continue
            val chip = Chip(this)
            chip.text = name.removePrefix("android.permission.")
            chip.isClickable = true
            chip.setOnClickListener { tryAddPermission(name) }
            binding.suggestionChips.addView(chip)
        }
    }

    private fun renderAddedChips() {
        binding.addedChips.removeAllViews()
        if (added.isEmpty()) return
        for ((name, _) in added.toList()) {
            val chip = Chip(this)
            chip.text = name
            chip.isCloseIconVisible = true
            chip.setOnCloseIconClickListener {
                added.removeAll { it.first == name }
                renderSuggestions()
                renderAddedChips()
            }
            binding.addedChips.addView(chip)
        }
    }

    private fun tryAddPermission(raw: String) {
        val name = raw.trim()
        binding.addMsg.text = ""
        if (name.isEmpty()) return
        if (!Regex("^[A-Za-z0-9_.]+$").matches(name)) {
            binding.addMsg.text = "権限名には英数字・ドット・アンダースコアのみ使用できます（例: android.permission.CAMERA）。"
            return
        }
        val inOriginalActive = originalPermissions.contains(name) && !removed.contains(name)
        if (inOriginalActive) {
            binding.addMsg.text = "この権限は既に要求されています。"
            return
        }
        val wasRemoved = originalPermissions.contains(name) && removed.contains(name)
        if (wasRemoved) {
            removed.remove(name)
            renderPermList()
            renderSuggestions()
            renderAddedChips()
            return
        }
        if (added.any { it.first == name }) {
            binding.addMsg.text = "既に追加予定リストに入っています。"
            return
        }
        added.add(name to "uses-permission")
        binding.addInput.setText("")
        renderSuggestions()
        renderAddedChips()
    }

    private fun onApplyClicked() {
        clearError()
        binding.resultBox.visibility = View.GONE
        if (removed.isEmpty() && added.isEmpty()) {
            showError("変更がありません。権限のチェックを外すか、権限を追加してください。")
            return
        }
        val input = cachedApkFile ?: return

        binding.btnApply.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressText.visibility = View.VISIBLE
        binding.progressText.text = "準備中…"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val outFile = File(cacheDir, "edited_${System.currentTimeMillis()}.apk")
                val result = ApkRebuilder.buildModifiedApk(
                    input, cacheDir, outFile, removed.toList(), added.toList()
                ) { stage ->
                    lifecycleScope.launch(Dispatchers.Main) { binding.progressText.text = stage }
                }
                withContext(Dispatchers.Main) { showResult(result.outputApk) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("処理中にエラーが発生しました: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) {
                    binding.btnApply.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.progressText.visibility = View.GONE
                }
            }
        }
    }

    private fun showResult(outFile: File) {
        pendingOutputApk = outFile
        binding.resultText.text = "✅ 完了しました。新しい署名で再パッケージ済みです。\n" +
            "元のアプリを端末にインストール済みの場合、署名が変わっているため上書き更新はできません。先にアンインストールしてから、この編集版をインストールしてください。"
        binding.resultBox.visibility = View.VISIBLE
        binding.btnInstall.setOnClickListener { installApk(outFile) }
        binding.btnShare.setOnClickListener { shareApk(outFile) }
    }

    private fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)

    private fun installApk(file: File) {
        val uri = fileProviderUri(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun shareApk(file: File) {
        val uri = fileProviderUri(file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "APKを共有"))
    }

    private fun resetState() {
        cachedApkFile = null
        pendingOutputApk = null
        originalPermissions = emptyList()
        removed.clear()
        added.clear()
        binding.summaryCard.visibility = View.GONE
        binding.permCard.visibility = View.GONE
        binding.addCard.visibility = View.GONE
        binding.applyCard.visibility = View.GONE
        binding.resultBox.visibility = View.GONE
        clearError()
    }
}
