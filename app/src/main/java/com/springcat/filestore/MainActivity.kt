package com.springcat.filestore

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.springcat.filestore.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: FileRepository
    private lateinit var adapter: FileAdapter

    private val io = Executors.newSingleThreadExecutor()

    /** ACTION_OPEN_DOCUMENT gives us a stable, streamable content URI for any file. */
    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) importUri(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        repo = FileRepository(this)
        adapter = FileAdapter(onOpen = ::openFile, onMenu = ::showItemMenu)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener { picker.launch(arrayOf("*/*")) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = repo.list()
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) android.view.View.VISIBLE
        else android.view.View.GONE
        val total = FileRepository.formatSize(repo.totalSize())
        supportActionBar?.subtitle = getString(R.string.subtitle_summary, items.size, total)
    }

    private fun importUri(uri: Uri) {
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.fab.isEnabled = false

        io.execute {
            try {
                repo.importFile(uri) { copied, total ->
                    if (total > 0) {
                        val pct = ((copied * 100) / total).toInt()
                        runOnUiThread { binding.progressBar.progress = pct }
                    } else {
                        runOnUiThread { binding.progressBar.isIndeterminate = true }
                    }
                }
                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.fab.isEnabled = true
                    Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                    refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressBar.visibility = android.view.View.GONE
                    binding.fab.isEnabled = true
                    Toast.makeText(
                        this,
                        getString(R.string.save_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun uriFor(stored: StoredFile): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", stored.file)

    private fun openFile(stored: StoredFile) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(stored), mimeOf(stored.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(stored: StoredFile) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(stored.name)
            putExtra(Intent.EXTRA_STREAM, uriFor(stored))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun showItemMenu(stored: StoredFile) {
        val options = arrayOf(
            getString(R.string.open),
            getString(R.string.share),
            getString(R.string.delete)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(stored.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFile(stored)
                    1 -> shareFile(stored)
                    2 -> confirmDelete(stored)
                }
            }
            .show()
    }

    private fun confirmDelete(stored: StoredFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.delete_confirm, stored.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (repo.delete(stored)) refresh()
            }
            .show()
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }
}
