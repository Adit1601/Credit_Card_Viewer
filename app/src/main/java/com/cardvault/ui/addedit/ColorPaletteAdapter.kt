package com.cardvault.ui.addedit

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cardvault.R

class ColorPaletteAdapter(
    private val colors: List<String>,
    private var selected: String,
    private val onSelected: (String) -> Unit
) : RecyclerView.Adapter<ColorPaletteAdapter.VH>() {

    fun setSelected(hex: String) {
        val prev = colors.indexOf(selected)
        val next = colors.indexOf(hex)
        selected = hex
        if (prev >= 0) notifyItemChanged(prev)
        if (next >= 0) notifyItemChanged(next)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_color_swatch, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = colors.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val hex = colors[position]
        holder.bind(hex, hex == selected)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fill: View = itemView.findViewById(R.id.swatchFill)
        private val ring: View = itemView.findViewById(R.id.swatchRing)

        fun bind(hex: String, isSelected: Boolean) {
            fill.background.mutate().setTint(runCatching { Color.parseColor(hex) }.getOrDefault(Color.DKGRAY))
            ring.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onSelected(hex) }
        }
    }
}
