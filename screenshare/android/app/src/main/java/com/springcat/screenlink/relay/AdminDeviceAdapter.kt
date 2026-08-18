package com.springcat.screenlink.relay

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.springcat.screenlink.R

class AdminDeviceAdapter(
    private val onShowCreds: (DeviceAllowlist.Entry) -> Unit,
    private val onRemove: (DeviceAllowlist.Entry) -> Unit
) : RecyclerView.Adapter<AdminDeviceAdapter.VH>() {

    private var items: List<DeviceAllowlist.Entry> = emptyList()

    fun submit(entries: List<DeviceAllowlist.Entry>) {
        items = entries
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val meta: TextView = view.findViewById(R.id.deviceMeta)
        val showBtn: Button = view.findViewById(R.id.btnShowCreds)
        val removeBtn: Button = view.findViewById(R.id.btnRemoveDevice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.name.text = entry.name
        holder.meta.text = "group=${entry.group}"
        holder.showBtn.setOnClickListener { onShowCreds(entry) }
        holder.removeBtn.setOnClickListener { onRemove(entry) }
    }

    override fun getItemCount(): Int = items.size
}
