package com.springcat.apkminstaller

import android.content.Context
import java.io.File

/**
 * 取り出した APK をパッケージ単位にまとめる。
 * split は必ず同じセッションで入れる必要があり、別アプリは別セッションにする必要がある。
 */
object ApkGrouper {

    class Group(val packageName: String?, val label: String, val files: List<File>)

    fun group(context: Context, files: List<File>): List<Group> {
        val pm = context.packageManager
        val pkgOf = files.associateWith { f ->
            runCatching { pm.getPackageArchiveInfo(f.absolutePath, 0)?.packageName }.getOrNull()
        }
        val distinct = pkgOf.values.filterNotNull().distinct()

        // 単一パッケージ (= 普通の APKM/APKS/XAPK) はまとめて 1 セッション
        if (distinct.size <= 1) {
            return listOf(Group(distinct.firstOrNull(), distinct.firstOrNull() ?: "(unknown)", files))
        }

        val groups = linkedMapOf<String, MutableList<File>>()
        val unknown = mutableListOf<File>()
        files.forEach { f ->
            val p = pkgOf[f]
            if (p == null) unknown += f else groups.getOrPut(p) { mutableListOf() } += f
        }
        // 解析できなかった split は、名前の前方一致が近いグループへ寄せる
        unknown.forEach { f ->
            val target = groups.keys.maxByOrNull { commonPrefix(f.name.lowercase(), it.lowercase()) }
            if (target != null && commonPrefix(f.name.lowercase(), target.lowercase()) >= 3) {
                groups.getValue(target) += f
            } else {
                groups.getOrPut(f.name) { mutableListOf() } += f
            }
        }
        return groups.map { (pkg, fs) -> Group(pkg, pkg, fs.sortedBy { it.name }) }
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < minOf(a.length, b.length) && a[i] == b[i]) i++
        return i
    }
}
