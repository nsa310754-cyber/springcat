package com.springcat.screenlink.net

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.springcat.screenlink.AppSession
import com.springcat.screenlink.R

class PeerAdapter(
    private val onClick: (AppSession.Peer) -> Unit
) : RecyclerView.Adapter<PeerAdapter.VH>() {

    private var items: List<AppSession.Peer> = emptyList()

    fun submit(peers: List<AppSession.Peer>) {
        items = peers
        notifyDataSetChanged()
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.peerName)
        val status: TextView = view.findViewById(R.id.peerStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val peer = items[position]
        holder.name.text = peer.name
        holder.status.text = if (peer.sharing) "オンライン" else "オンライン(応答不可)"
        holder.itemView.setOnClickListener { onClick(peer) }
    }

    override fun getItemCount(): Int = items.size
}
