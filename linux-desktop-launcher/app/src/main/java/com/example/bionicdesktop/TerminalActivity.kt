package com.example.bionicdesktop

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * コマンドプロンプト。
 * ・先頭が "root " なら su 経由、"glibc " なら glibc ローダ経由、それ以外は /system/bin/sh で実行。
 * ・./filesDir/bin 以下に置いた Bionic ELF もそのまま実行できる(targetSdk 28)。
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        output = findViewById(R.id.output)
        scroll = findViewById(R.id.outputScroll)
        val input = findViewById<EditText>(R.id.input)

        println("BionicDesktop terminal")
        append("HOME=${filesDir.absolutePath}")
        append("bin =${NativeExecutor.binDir(this).absolutePath}")
        append("lib =${NativeExecutor.nativeLibDir(this).absolutePath}")
        append("------------------------------------------")

        val runCmd = {
            val line = input.text.toString().trim()
            if (line.isNotEmpty()) {
                append("$ $line")
                input.setText("")
                thread {
                    val result = when {
                        line.startsWith("root ") ->
                            RootShell.exec(line.removePrefix("root "))
                        line.startsWith("glibc ") -> {
                            val parts = line.removePrefix("glibc ").split(" ")
                            GlibcRuntime.runViaLoader(this, parts.first(), parts.drop(1))
                        }
                        else -> NativeExecutor.sh(this, line)
                    }
                    runOnUiThread { append(result.trimEnd()) }
                }
            }
        }

        findViewById<Button>(R.id.run).setOnClickListener { runCmd() }
        input.setOnEditorActionListener { _, _, _ -> runCmd(); true }
    }

    private fun append(text: String) {
        output.append(text + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
