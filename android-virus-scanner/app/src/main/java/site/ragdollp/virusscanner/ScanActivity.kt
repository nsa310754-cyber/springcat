package site.ragdollp.virusscanner

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.ragdollp.virusscanner.databinding.ActivityScanBinding
import site.ragdollp.virusscanner.model.AppScanResult
import site.ragdollp.virusscanner.scan.AppScanner
import site.ragdollp.virusscanner.state.ScanResultsHolder

/**
 * スキャン開始と同時に、システムアプリ以外の全アプリを1つずつ解凍・調査する画面。
 * 進捗(何個中何個目・調査中のパッケージ名)を表示し、完了したら検出結果画面へ遷移する。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        runScan()
    }

    private fun runScan() {
        val scanner = AppScanner(applicationContext)

        lifecycleScope.launch {
            val targets = withContext(Dispatchers.IO) { scanner.listScanTargets() }
            binding.progressBar.max = targets.size.coerceAtLeast(1)
            binding.progressBar.progress = 0
            binding.countText.text = getString(R.string.scan_total_format, targets.size)

            val detections = mutableListOf<AppScanResult>()

            targets.forEachIndexed { index, info ->
                binding.statusText.text = getString(
                    R.string.scan_progress_format,
                    index + 1,
                    targets.size,
                    info.packageName
                )
                val result = withContext(Dispatchers.IO) { scanner.scanOne(info) }
                if (result != null) detections += result
                binding.progressBar.progress = index + 1
            }

            ScanResultsHolder.results = detections
            binding.statusText.setText(R.string.scan_done)

            startActivity(Intent(this@ScanActivity, DetectionActivity::class.java))
            finish()
        }
    }
}
