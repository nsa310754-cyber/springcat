package com.example.bionicdesktop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「アプリとして追加」= 起動可能エントリの永続リスト(filesDir/apps.json)。
 * PC の「インストール済みアプリ」に相当する最小の仕組み。
 */
object AppRegistry {

    enum class Type { ELF, EXE }

    data class Entry(val name: String, val type: Type, val path: String, val args: List<String> = emptyList())

    private fun file(ctx: Context) = File(ctx.filesDir, "apps.json")

    fun list(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    o.getString("name"),
                    Type.valueOf(o.getString("type")),
                    o.getString("path"),
                    o.optJSONArray("args")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("name", e.name)
                    .put("type", e.type.name)
                    .put("path", e.path)
                    .put("args", JSONArray(e.args)),
            )
        }
        file(ctx).writeText(arr.toString(2))
    }

    /** 同名(同パス)があれば置き換えて追加。 */
    fun add(ctx: Context, entry: Entry) {
        val cur = list(ctx).filterNot { it.path == entry.path }.toMutableList()
        cur.add(entry)
        save(ctx, cur)
    }

    fun remove(ctx: Context, path: String) {
        save(ctx, list(ctx).filterNot { it.path == path })
    }
}
