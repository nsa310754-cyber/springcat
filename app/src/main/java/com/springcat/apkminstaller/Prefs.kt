package com.springcat.apkminstaller

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("springcat", Context.MODE_PRIVATE)

    /** 受け取ったファイルを確認なしですぐインストールキューに入れる。 */
    var autoInstall: Boolean
        get() = sp.getBoolean(KEY_AUTO, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO, v).apply()

    /** true なら split を選別せず全部入れる。 */
    var installAllSplits: Boolean
        get() = sp.getBoolean(KEY_ALL_SPLITS, false)
        set(v) = sp.edit().putBoolean(KEY_ALL_SPLITS, v).apply()

    /** フォルダ監視の有効/無効。 */
    var watchEnabled: Boolean
        get() = sp.getBoolean(KEY_WATCH, false)
        set(v) = sp.edit().putBoolean(KEY_WATCH, v).apply()

    /** 監視対象フォルダ (SAF ツリー URI)。 */
    var watchTreeUri: String?
        get() = sp.getString(KEY_TREE, null)
        set(v) = sp.edit().putString(KEY_TREE, v).apply()

    /** 監視ポーリング間隔 (秒)。 */
    var pollSeconds: Int
        get() = sp.getInt(KEY_POLL, 8)
        set(v) = sp.edit().putInt(KEY_POLL, v.coerceIn(3, 300)).apply()

    fun isSeen(key: String): Boolean = seen().contains(key)

    fun markSeen(key: String) {
        val set = seen().toMutableSet()
        set.add(key)
        // 無限に増えないように適当に間引く
        val trimmed = if (set.size > 400) set.toList().takeLast(200).toSet() else set
        sp.edit().putStringSet(KEY_SEEN, trimmed).apply()
    }

    fun clearSeen() = sp.edit().remove(KEY_SEEN).apply()

    private fun seen(): Set<String> = sp.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()

    private companion object {
        const val KEY_AUTO = "auto_install"
        const val KEY_ALL_SPLITS = "all_splits"
        const val KEY_WATCH = "watch_enabled"
        const val KEY_TREE = "watch_tree"
        const val KEY_POLL = "poll_seconds"
        const val KEY_SEEN = "seen_keys"
    }
}
