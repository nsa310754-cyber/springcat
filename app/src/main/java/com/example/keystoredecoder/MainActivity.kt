package com.example.keystoredecoder

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.keystoredecoder.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var selectedUri: Uri? = null
    private var selectedName: String? = null

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't grant persistable permission; that's fine.
            }
            selectedUri = uri
            selectedName = queryName(uri)
            binding.fileNameText.text = selectedName ?: uri.toString()
            binding.decodeButton.isEnabled = true
            clearResults()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pickButton.setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }

        binding.decodeButton.isEnabled = false
        binding.decodeButton.setOnClickListener { decode() }
    }

    private fun decode() {
        val uri = selectedUri ?: run {
            toast("Please select a keystore file first.")
            return
        }
        val password = binding.passwordInput.text?.toString() ?: ""

        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            showMessage("Could not read file:\n${t.message}")
            return
        } ?: run {
            showMessage("Could not open the selected file.")
            return
        }

        binding.progress.visibility = View.VISIBLE
        binding.decodeButton.isEnabled = false
        clearResults()

        Thread {
            val result = KeystoreDecoder.decode(bytes, password.toCharArray())
            runOnUiThread {
                binding.progress.visibility = View.GONE
                binding.decodeButton.isEnabled = true
                renderResult(result, password.isEmpty())
            }
        }.start()
    }

    private fun renderResult(result: KeystoreDecoder.Result, passwordWasEmpty: Boolean) {
        when (result) {
            is KeystoreDecoder.Result.Success -> {
                val rows = ReportBuilder.items(selectedName, result.info)
                renderRows(rows)
                val fullText = ReportBuilder.build(selectedName, result.info)
                binding.copyAllButton.visibility = View.VISIBLE
                binding.copyAllButton.setOnClickListener { copy("Keystore report", fullText) }
            }
            is KeystoreDecoder.Result.PasswordRequired -> {
                val hint = if (passwordWasEmpty)
                    "\n\nEnter the keystore password and try again."
                else
                    "\n\nCheck the password and try again."
                showMessage("🔒 ${result.message}$hint")
            }
            is KeystoreDecoder.Result.Failure -> {
                showMessage("⚠️ ${result.message}")
            }
        }
    }

    private fun renderRows(rows: List<ReportBuilder.Row>) {
        val container = binding.resultContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (row in rows) {
            when (row) {
                is ReportBuilder.Row.Header -> {
                    val tv = inflater.inflate(
                        R.layout.row_header, container, false
                    ) as TextView
                    tv.text = row.text
                    container.addView(tv)
                }
                is ReportBuilder.Row.Plain -> {
                    val tv = inflater.inflate(
                        R.layout.row_plain, container, false
                    ) as TextView
                    tv.text = row.text
                    container.addView(tv)
                }
                is ReportBuilder.Row.Field -> {
                    val view = inflater.inflate(R.layout.row_field, container, false)
                    view.findViewById<TextView>(R.id.fieldLabel).text = row.label
                    view.findViewById<TextView>(R.id.fieldValue).text = row.value
                    val doCopy = { copy(row.label, row.value) }
                    view.setOnClickListener { doCopy() }
                    view.findViewById<MaterialButton>(R.id.copyButton)
                        .setOnClickListener { doCopy() }
                    container.addView(view)
                }
            }
        }
    }

    /** Shows a single plain message (for errors / password prompts). */
    private fun showMessage(message: String) {
        clearResults()
        val tv = LayoutInflater.from(this)
            .inflate(R.layout.row_plain, binding.resultContainer, false) as TextView
        tv.text = message
        binding.resultContainer.addView(tv)
    }

    private fun clearResults() {
        binding.resultContainer.removeAllViews()
        binding.copyAllButton.visibility = View.GONE
    }

    private fun copy(label: String, value: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        toast("Copied: $label")
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
