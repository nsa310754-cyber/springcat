package com.springcat.iso

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.springcat.iso.databinding.ItemEntryBinding

/** Renders a flat list of [IsoEntry] rows and reports clicks. */
class IsoEntryAdapter(
    private var items: List<IsoEntry>,
    private val onClick: (IsoEntry) -> Unit
) : RecyclerView.Adapter<IsoEntryAdapter.EntryViewHolder>() {

    fun submit(newItems: List<IsoEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val binding = ItemEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return EntryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class EntryViewHolder(
        private val binding: ItemEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: IsoEntry) {
            binding.icon.setImageResource(
                if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_file
            )
            binding.name.text = entry.name
            binding.size.text = entry.readableSize()
            binding.root.setOnClickListener { onClick(entry) }
        }
    }
}
