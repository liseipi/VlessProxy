package com.vlessproxy.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vlessvpn.R
import com.vlessproxy.model.VlessConfig

class ConfigAdapter(
    private val configs: MutableList<VlessConfig>,
    activeIndex: Int,
    private val onSelect: (Int) -> Unit,
    private val onEdit:   (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onShare:  (Int) -> Unit
) : RecyclerView.Adapter<ConfigAdapter.VH>() {

    private var activeIdx = activeIndex

    fun setActive(idx: Int) {
        val old = activeIdx
        activeIdx = idx
        notifyItemChanged(old)
        notifyItemChanged(idx)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:   TextView    = view.findViewById(R.id.tvName)
        val tvDetail: TextView    = view.findViewById(R.id.tvDetail)
        val tvActive: TextView    = view.findViewById(R.id.tvActive)
        val btnEdit:  ImageButton = view.findViewById(R.id.btnEdit)
        val btnDel:   ImageButton = view.findViewById(R.id.btnDelete)
        val btnShare: ImageButton = view.findViewById(R.id.btnShare)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_config, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val cfg = configs[position]
        holder.tvName.text   = cfg.name.ifBlank { cfg.server }
        holder.tvDetail.text = "${cfg.security.uppercase()} · ${cfg.server}:${cfg.port} → :${cfg.listenPort}"
        holder.tvActive.visibility = if (position == activeIdx) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onSelect(position) }
        holder.btnEdit.setOnClickListener  { onEdit(position) }
        holder.btnDel.setOnClickListener   { onDelete(position) }
        holder.btnShare.setOnClickListener { onShare(position) }

        // Highlight active item
        val bg = if (position == activeIdx)
            holder.itemView.context.getColor(R.color.active_item_bg)
        else
            holder.itemView.context.getColor(android.R.color.transparent)
        holder.itemView.setBackgroundColor(bg)
    }

    override fun getItemCount() = configs.size
}
