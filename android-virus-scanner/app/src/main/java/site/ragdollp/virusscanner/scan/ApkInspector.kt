package site.ragdollp.virusscanner.scan

import java.io.File
import java.util.zip.ZipFile

/**
 * APK(zip)の中身を実際に解凍して調べた結果。
 */
data class ApkStructureFindings(
    val dexEntryCount: Int,
    val hiddenDexInAssets: Boolean,
    val suspiciousStringHits: List<String>
)

/**
 * APKファイルをzipとして解凍し、classes.dexの数やassets配下に隠されたdexの有無、
 * 既知の不審な文字列(動的コード読み込み・root検出など)をひととおり確認する。
 *
 * AndroidManifest.xml自体はバイナリXML形式で、要求権限の取得は
 * PackageManagerに正式なパーサーを任せる([AppScanner]側で実施)。
 * ここでは「解凍してmanifest以外のファイルも見る」部分を担当する。
 */
object ApkInspector {

    private val SUSPICIOUS_DEX_STRINGS = listOf(
        "DexClassLoader",
        "dalvik.system.PathClassLoader",
        "getRuntime().exec",
        "/system/bin/su",
        "su -c",
    )

    // 重いAPK(数十MB)でも端末が固まらないよう、文字列走査は一定サイズまでに制限する。
    private const val MAX_SCAN_BYTES = 6L * 1024 * 1024

    fun inspect(apkPath: String): ApkStructureFindings {
        val file = File(apkPath)
        if (!file.exists()) {
            return ApkStructureFindings(0, false, emptyList())
        }

        var dexCount = 0
        var hiddenDex = false
        val stringHits = mutableSetOf<String>()

        runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    when {
                        name == "classes.dex" || Regex("^classes\\d+\\.dex$").matches(name) -> {
                            dexCount++
                        }
                        name.startsWith("assets/") && name.endsWith(".dex") -> {
                            // assets配下に実行コード(dex)を隠しておき、実行時に動的読み込みする
                            // 検知回避の手口として知られている。
                            hiddenDex = true
                        }
                    }

                    if (name == "classes.dex" && entry.size in 1..MAX_SCAN_BYTES) {
                        runCatching {
                            zip.getInputStream(entry).use { input ->
                                val text = String(input.readBytes(), Charsets.ISO_8859_1)
                                for (needle in SUSPICIOUS_DEX_STRINGS) {
                                    if (text.contains(needle)) stringHits += needle
                                }
                            }
                        }
                    }
                }
            }
        }

        return ApkStructureFindings(dexCount, hiddenDex, stringHits.toList())
    }
}
