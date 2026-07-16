package com.springcat.iso

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.springcat.iso.databinding.ActivityMainBinding
import java.util.ArrayDeque

/**
 * Entry point. Lets the user pick a `.iso` image (or receive one via a VIEW
 * intent) and browse the files inside it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: IsoEntryAdapter

    private var reader: IsoReader? = null

    /** Navigation stack: the directory we are currently in is the last item. */
    private val pathStack = ArrayDeque<IsoEntry>()

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadIso(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = IsoEntryAdapter(emptyList()) { entry -> onEntryClicked(entry) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.openButton.setOnClickListener {
            openDocument.launch(arrayOf("application/x-iso9660-image", "application/octet-stream", "*/*"))
        }

        // If launched by opening a .iso from a file manager, honour the intent.
        intent?.data?.let { loadIso(it) }
    }

    private fun loadIso(uri: Uri) {
        showLoading(true)
        Thread {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { IsoReader.open(it) }
                    ?: error("Could not open the selected file")
            }
            runOnUiThread {
                showLoading(false)
                result.onSuccess { r ->
                    reader = r
                    pathStack.clear()
                    pathStack.addLast(r.root())
                    binding.emptyHint.visibility = View.GONE
                    refresh()
                    title = uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.app_name)
                }.onFailure { e ->
                    Toast.makeText(
                        this,
                        getString(R.string.error_open, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun onEntryClicked(entry: IsoEntry) {
        if (entry.isDirectory) {
            pathStack.addLast(entry)
            refresh()
        } else {
            Toast.makeText(
                this,
                getString(R.string.file_info, entry.name, entry.readableSize()),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun refresh() {
        val r = reader ?: return
        val current = pathStack.peekLast() ?: return
        val entries = runCatching { r.list(current) }.getOrElse { emptyList() }
        adapter.submit(entries)
        binding.breadcrumb.text = pathStack.joinToString(" / ") {
            if (it.name == "/") "" else it.name
        }.ifBlank { "/" }
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pathStack.size > 1) {
            pathStack.removeLast()
            refresh()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
