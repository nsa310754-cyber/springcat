package site.ragdollp.pocketzip

import android.content.Context

/**
 * 「保存しないファイル/フォルダ」として除外したパスの一覧を永続化する。
 * あるパスが除外されていれば、その配下も自動的に除外扱いになる
 * (isPathExcluded を参照)。
 */
class ExclusionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("pocketzip_prefs", Context.MODE_PRIVATE)

    fun load(): Set<String> = prefs.getStringSet(KEY_EXCLUDED, emptySet()) ?: emptySet()

    fun save(paths: Set<String>) {
        prefs.edit().putStringSet(KEY_EXCLUDED, paths).apply()
    }

    companion object {
        private const val KEY_EXCLUDED = "excluded_paths"
    }
}

/** path 自身、またはその祖先フォルダが excluded に含まれていれば true。 */
fun isPathExcluded(path: String, excluded: Set<String>): Boolean {
    if (path in excluded) return true
    for (e in excluded) {
        if (path.startsWith("$e/")) return true
    }
    return false
}
