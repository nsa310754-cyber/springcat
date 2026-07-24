package com.example.keystoredecoder

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.keystoredecoder.databinding.ActivityMainBinding

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
            binding.resultText.text = ""
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
            binding.resultText.text = "Could not read file:\n${t.message}"
            return
        } ?: run {
            binding.resultText.text = "Could not open the selected file."
            return
        }

        binding.progress.visibility = View.VISIBLE
        binding.decodeButton.isEnabled = false

        // Keystore parsing is fast enough for the UI thread on small files,
        // but run off-thread to keep the UI responsive on large ones.
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
                binding.resultText.text = ReportBuilder.build(selectedName, result.info)
            }
            is KeystoreDecoder.Result.PasswordRequired -> {
                val hint = if (passwordWasEmpty)
                    "\n\nEnter the keystore password and try again."
                else
                    "\n\nCheck the password and try again."
                binding.resultText.text = "🔒 ${result.message}$hint"
            }
            is KeystoreDecoder.Result.Failure -> {
                binding.resultText.text = "⚠️ ${result.message}"
            }
        }
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
