package com.springcat.filestore

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.springcat.filestore.databinding.ItemFileBinding

/**
 * Binds the metadata-only [StoredFile] list. RecyclerView recycles rows, so the
 * list stays light no matter how many (or how large) the files are.
 */
class FileAdapter(
    private val onOpen: (StoredFile) -> Unit,
    private val onMenu: (StoredFile) -> Unit
) : RecyclerView.Adapter<FileAdapter.Holder>() {

    private val items = mutableListOf<StoredFile>()

    fun submit(newItems: List<StoredFile>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.fileName.text = item.name
        val when_ = DateUtils.getRelativeTimeSpanString(item.lastModified)
        holder.binding.fileMeta.text = "${FileRepository.formatSize(item.size)} · $when_"
        holder.binding.root.setOnClickListener { onOpen(item) }
        holder.binding.menuButton.setOnClickListener { onMenu(item) }
    }

    override fun getItemCount(): Int = items.size
}
