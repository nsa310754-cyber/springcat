package com.example.bionicdesktop

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/** 「アプリとして追加」されたエントリの一覧。タップで起動、長押しで削除。 */
class AppsActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private var entries: List<AppRegistry.Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)
        out = findViewById(R.id.appOut)
        val list = findViewById<ListView>(R.id.appList)

        fun refresh() {
            entries = AppRegistry.list(this)
            val labels = entries.map { "${it.name}   [${it.type}]" }
                .ifEmpty { listOf("(まだありません。ファイルを選んで実行すると登録されます)") }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        }

        list.setOnItemClickListener { _, _, pos, _ ->
            if (pos >= entries.size) return@setOnItemClickListener
            val e = entries[pos]
            append("$ 起動: ${e.name} [${e.type}]")
            thread {
                FileRunner.launch(this, e) { line -> runOnUiThread { append(line) } }
            }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos < entries.size) {
                AppRegistry.remove(this, entries[pos].path)
                append("削除: ${entries[pos].name}")
                refresh()
            }
            true
        }

        refresh()
    }

    private fun append(text: String) {
        out.append(text + "\n")
        (out.parent as? ScrollView)?.post { (out.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
