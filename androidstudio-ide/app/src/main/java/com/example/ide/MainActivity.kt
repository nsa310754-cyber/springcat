package com.example.ide

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.ide.databinding.ActivityMainBinding
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var jsEngine: WebView
    private var engineReady = false
    private var highlighting = false

    private val fileName = "main.js"

    private val sampleCode = """
        // AndroidstudioDEMO — JavaScript を書いて ▶実行
        function greet(name) {
          return "Hello, " + name + "!";
        }

        console.log(greet("Android"));
        console.log("1 + 2 =", 1 + 2);

        // 10までの合計
        let sum = 0;
        for (let i = 1; i <= 10; i++) {
          sum += i;
        }
        console.log("合計:", sum);
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Local, offline JavaScript engine (no INTERNET permission needed).
        jsEngine = WebView(this)
        jsEngine.settings.javaScriptEnabled = true
        jsEngine.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                engineReady = true
            }
        }
        jsEngine.loadUrl("about:blank")

        // Syntax highlighting as you type.
        binding.codeEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (highlighting) return
                highlighting = true
                highlight(s)
                highlighting = false
            }
        })

        // Load the last saved file, or start with the sample.
        val saved = File(filesDir, fileName)
        binding.codeEditor.setText(if (saved.exists()) saved.readText() else sampleCode)

        binding.runButton.setOnClickListener { runCode() }
        binding.saveButton.setOnClickListener { saveCode() }
        binding.openButton.setOnClickListener { openCode() }
    }

    // ---- Run (JavaScript) ----

    private fun runCode() {
        if (!engineReady) {
            binding.consoleOutput.text = "エンジン初期化中… もう一度 ▶実行 を押してください。"
            return
        }
        val userCode = binding.codeEditor.text.toString()
        // Capture console.log/error output and the final value, all inside a sandboxed IIFE.
        val wrapped = "(function(){" +
                "var __o=[];" +
                "function __s(a){return Array.prototype.slice.call(a).map(function(x){" +
                "return (typeof x==='object')?JSON.stringify(x):String(x);}).join(' ');}" +
                "var console={log:function(){__o.push(__s(arguments));}," +
                "error:function(){__o.push('Error: '+__s(arguments));}," +
                "warn:function(){__o.push(__s(arguments));}," +
                "info:function(){__o.push(__s(arguments));}};" +
                "try{var __r=eval(${JSONObject.quote(userCode)});" +
                "if(__r!==undefined)__o.push(String(__r));}" +
                "catch(e){__o.push('Error: '+e.message);}" +
                "return __o.join('\\n');})();"

        jsEngine.evaluateJavascript(wrapped) { result ->
            val text = decodeJsString(result)
            binding.consoleOutput.text = if (text.isBlank()) "(出力なし)" else text
            binding.consoleScroll.post { binding.consoleScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun decodeJsString(result: String?): String {
        if (result == null || result == "null") return ""
        return try {
            when (val v = JSONTokener(result).nextValue()) {
                is String -> v
                else -> v.toString()
            }
        } catch (e: Exception) {
            result
        }
    }

    // ---- Save / Open ----

    private fun saveCode() {
        try {
            File(filesDir, fileName).writeText(binding.codeEditor.text.toString())
            Toast.makeText(this, "保存しました (main.js)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存に失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCode() {
        val saved = File(filesDir, fileName)
        if (saved.exists()) {
            binding.codeEditor.setText(saved.readText())
            Toast.makeText(this, "読み込みました (main.js)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "保存されたファイルがありません", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Syntax highlighting ----

    private val keywordPattern = Pattern.compile(
        "\\b(var|let|const|function|return|if|else|for|while|do|break|continue|" +
                "new|typeof|this|true|false|null|undefined|try|catch|finally|throw|" +
                "class|switch|case|default|void|in|of|instanceof|delete|yield|await|async)\\b"
    )
    private val numberPattern = Pattern.compile("\\b\\d+(\\.\\d+)?\\b")
    private val stringPattern = Pattern.compile("\"[^\"\\n]*\"|'[^'\\n]*'|`[^`]*`")
    private val commentPattern = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL)

    private fun highlight(e: Editable) {
        for (span in e.getSpans(0, e.length, ForegroundColorSpan::class.java)) {
            e.removeSpan(span)
        }
        val text = e.toString()
        // Order matters: later spans win over earlier ones on overlap.
        apply(e, text, keywordPattern, 0xFFCC7832.toInt())  // keywords (orange)
        apply(e, text, numberPattern, 0xFF6897BB.toInt())   // numbers (blue)
        apply(e, text, stringPattern, 0xFF6A8759.toInt())   // strings (green)
        apply(e, text, commentPattern, 0xFF808080.toInt())  // comments (gray) — wins
    }

    private fun apply(e: Editable, text: String, p: Pattern, color: Int) {
        val m = p.matcher(text)
        while (m.find()) {
            e.setSpan(
                ForegroundColorSpan(color),
                m.start(), m.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
