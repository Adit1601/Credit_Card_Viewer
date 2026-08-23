package com.cardvault.ui.home

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cardvault.R
import com.cardvault.data.db.CardType
import com.google.android.material.card.MaterialCardView

/**
 * Adapter for the home card list. Two knobs the fragment controls:
 *   - [reorderMode] — when true, drag handles are visible and the handle grabs a MotionEvent.
 *   - [onDragHandleTouched] — invoked when the user presses the handle; fragment calls
 *     ItemTouchHelper.startDrag(vh).
 */
class CardTileAdapter(
    private val onTap: (CardDisplay) -> Unit,
    private val onLongPress: (CardDisplay) -> Unit,
    private val onDragHandleTouched: (RecyclerView.ViewHolder) -> Unit
) : ListAdapter<CardDisplay, CardTileAdapter.VH>(DIFF) {

    var reorderMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_card_tile, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_MODE)) {
            holder.applyMode(reorderMode)
        } else {
            holder.bind(getItem(position))
        }
    }

    /** Public because the fragment needs to consult it while wiring ItemTouchHelper. */
    fun currentIds(): List<String> = currentList.map { it.id }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: MaterialCardView = itemView.findViewById(R.id.cardTile)
        private val nickname: TextView = itemView.findViewById(R.id.nickname)
        private val cardNumber: TextView = itemView.findViewById(R.id.cardNumber)
        private val nameOnCard: TextView = itemView.findViewById(R.id.nameOnCard)
        private val expiry: TextView = itemView.findViewById(R.id.expiry)
        private val networkLogo: ImageView = itemView.findViewById(R.id.networkLogo)
        private val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)
        private val cardTypeBadge: TextView = itemView.findViewById(R.id.cardTypeBadge)
        private val expiringSoonBadge: TextView = itemView.findViewById(R.id.expiringSoonBadge)

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: CardDisplay) {
            nickname.text = item.nickname
            cardNumber.text = item.maskedNumber
            nameOnCard.text = item.nameOnCard.uppercase()
            expiry.text = item.expiry
            networkLogo.setImageResource(item.network.logoRes)
            networkLogo.contentDescription = itemView.context.getString(item.network.contentDescRes)

            if (item.cardType == CardType.UNKNOWN) {
                cardTypeBadge.visibility = View.GONE
            } else {
                cardTypeBadge.setText(item.cardType.labelRes)
                cardTypeBadge.visibility = View.VISIBLE
            }
            expiringSoonBadge.visibility = if (item.isExpiringSoon) View.VISIBLE else View.GONE

            cardView.setCardBackgroundColor(parseColor(item.colorHex))

            cardView.setOnClickListener { if (!reorderMode) onTap(item) }
            cardView.setOnLongClickListener {
                if (!reorderMode) {
                    onLongPress(item); true
                } else false
            }
            dragHandle.setOnTouchListener { _, event ->
                if (reorderMode && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onDragHandleTouched(this)
                }
                false
            }
            applyMode(reorderMode)
        }

        fun applyMode(reorder: Boolean) {
            dragHandle.visibility = if (reorder) View.VISIBLE else View.GONE
        }

        private fun parseColor(hex: String): Int = try {
            Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (_: IllegalArgumentException) {
            itemView.context.getColor(R.color.card_slate)
        }
    }

    companion object {
        private val PAYLOAD_MODE = Any()

        val DIFF = object : DiffUtil.ItemCallback<CardDisplay>() {
            override fun areItemsTheSame(a: CardDisplay, b: CardDisplay) = a.id == b.id
            override fun areContentsTheSame(a: CardDisplay, b: CardDisplay) = a == b
        }
    }
}
