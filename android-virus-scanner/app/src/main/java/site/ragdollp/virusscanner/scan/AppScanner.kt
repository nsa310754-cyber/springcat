package site.ragdollp.virusscanner.scan

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import site.ragdollp.virusscanner.model.AppScanResult

/**
 * スキャン処理の中核。
 *
 * 1. システムアプリ以外の全アプリを列挙する。
 * 2. 各アプリのAPKを解凍してAndroidManifest(要求権限)・classes.dex・assets等を調べる。
 * 3. 不審な権限を複数(2つ以上)要求しているアプリだけを検出結果として返す。
 */
class AppScanner(context: Context) {

    private val pm: PackageManager = context.applicationContext.packageManager

    /** システムアプリを除いたスキャン対象アプリの一覧を返す。 */
    fun listScanTargets(): List<ApplicationInfo> {
        return getInstalledApplicationsCompat().filter { !isSystemApp(it) }
    }

    private fun isSystemApp(info: ApplicationInfo): Boolean {
        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        // プリインストールのシステムアプリは対象外。ただしユーザーが更新をかけた
        // システムアプリ(ブラウザ等、差し替えの可能性がある)はスキャン対象に含める。
        return isSystem && !isUpdatedSystem
    }

    /**
     * 1アプリを解凍・調査する。不審な権限が[PermissionRules.SUSPICION_THRESHOLD]未満の場合はnullを返す
     * (=検出欄には表示しない)。
     */
    fun scanOne(info: ApplicationInfo): AppScanResult? {
        val packageName = info.packageName
        val sourceDir = info.sourceDir ?: return null

        // AndroidManifest.xml(バイナリXML)の要求権限を正式なパーサーで取得する。
        val packageInfo = getPackageInfoCompat(packageName) ?: return null
        val requestedPermissions = packageInfo.requestedPermissions?.toList().orEmpty()
        val matchedPermissions = requestedPermissions
            .filter { PermissionRules.SUSPICIOUS_PERMISSIONS.containsKey(it) }
            .distinct()

        // APK(zip)を実際に解凍してclasses.dexやassets配下も調べる。
        val structure = ApkInspector.inspect(sourceDir)

        // 不審権限が2つ未満(0〜1個)は正常アプリの可能性が高いため検出対象から外す。
        if (matchedPermissions.size < PermissionRules.SUSPICION_THRESHOLD) {
            return null
        }

        val extraFindings = mutableListOf<String>()
        if (structure.dexEntryCount > 1) {
            extraFindings += "複数のdexファイル(${structure.dexEntryCount}個)を含む"
        }
        if (structure.hiddenDexInAssets) {
            extraFindings += "assets内に隠されたdex(動的コード読み込みの疑い)"
        }
        if (structure.suspiciousStringHits.isNotEmpty()) {
            extraFindings += "不審なコード文字列を検出: " + structure.suspiciousStringHits.joinToString(", ")
        }

        val label = pm.getApplicationLabel(info).toString()
        val icon = runCatching { pm.getApplicationIcon(info) }.getOrNull()

        return AppScanResult(
            packageName = packageName,
            label = label,
            versionName = packageInfo.versionName,
            icon = icon,
            suspiciousPermissions = matchedPermissions.map { PermissionRules.SUSPICIOUS_PERMISSIONS[it] ?: it },
            extraFindings = extraFindings
        )
    }

    @Suppress("DEPRECATION")
    private fun getInstalledApplicationsCompat(): List<ApplicationInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackageInfoCompat(packageName: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
    }.getOrNull()
}
