package com.example.guandan.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.guandan.R
import com.example.guandan.model.Card
import com.example.guandan.model.CardRank
import com.example.guandan.utils.CardComparator

class CardAdapter(
    private val cardList: MutableList<Card>,
    private val onCardClick: (Card, Boolean) -> Unit  // 修改：增加Boolean参数表示是否是手动点击
) : RecyclerView.Adapter<CardAdapter.CardViewHolder>() {

    inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivCard: ImageView = itemView.findViewById(R.id.iv_card)
        val viewSelected: View = itemView.findViewById(R.id.view_selected)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val card = cardList[position]
                    // 手动点击时，传递true表示这是手动点击
                    onCardClick(card, true)
                }
            }
        }

        fun updateSelectedState(card: Card) {
            if (card.isSelected) {
                itemView.translationY = -20f
                viewSelected.visibility = View.VISIBLE
            } else {
                itemView.translationY = 0f
                viewSelected.visibility = View.GONE
            }
        }

        fun bind(card: Card) {
            val resId = itemView.context.resources.getIdentifier(
                card.getResName(),
                "drawable",
                itemView.context.packageName
            )
            ivCard.setImageResource(if (resId != 0) resId else R.drawable.card_background)
            updateSelectedState(card)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_card, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        val card = cardList[position]
        holder.bind(card)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val card = cardList[position]
            holder.updateSelectedState(card)
        }
    }

    override fun getItemCount(): Int = cardList.size

    fun updateData(newCards: List<Card>, levelRank: CardRank? = null) {
        cardList.clear()
        cardList.addAll(newCards)
        if (levelRank != null) {
            val comparator = CardComparator(levelRank)
            cardList.sortWith(comparator)
        }
        notifyDataSetChanged()
    }

    fun refreshCardSelection(card: Card) {
        val position = cardList.indexOfFirst { it.suit == card.suit && it.rank == card.rank }
        if (position != -1) {
            notifyItemChanged(position, "SELECTION")
        }
    }
}