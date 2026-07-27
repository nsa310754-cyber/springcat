package com.springcat.apkminstaller

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 画面と logcat の両方に出す簡易ログ。 */
object AppLog {

    private const val TAG = "SpringCat"
    private const val MAX_LINES = 500
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    val lines = MutableStateFlow<List<String>>(emptyList())

    fun i(msg: String) = add(msg, false)

    fun e(msg: String, t: Throwable? = null) {
        add(if (t == null) msg else "$msg (${t.javaClass.simpleName}: ${t.message})", true)
    }

    fun clear() {
        lines.value = emptyList()
    }

    private fun add(msg: String, isError: Boolean) {
        if (isError) Log.e(TAG, msg) else Log.i(TAG, msg)
        val line = "${fmt.format(Date())}  $msg"
        val next = lines.value + line
        lines.value = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
    }
}
