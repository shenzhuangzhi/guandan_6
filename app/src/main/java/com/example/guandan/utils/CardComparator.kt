package com.example.guandan.utils

import com.example.guandan.model.Card
import com.example.guandan.model.CardSuit

// 【修改】移除红桃3的特殊处理，按正常点数排序
class CardComparator : Comparator<Card> {
    override fun compare(card1: Card, card2: Card): Int {
        // 直接按点数从小到大排序
        val rankCompare = card1.rank.value - card2.rank.value
        if (rankCompare != 0) {
            return rankCompare
        }

        // 点数相同时按花色排序：黑桃>梅花>方块>红桃
        val suitOrder = mapOf<CardSuit, Int>(
            CardSuit.SPADE to 0,   // 黑桃
            CardSuit.CLUB to 1,    // 梅花
            CardSuit.DIAMOND to 2, // 方块
            CardSuit.HEART to 3,   // 红桃（不再特殊处理）
            CardSuit.JOKER to 4    // 王
        )
        return suitOrder[card1.suit]!! - suitOrder[card2.suit]!!
    }
}