package com.example.guandan.utils

import com.example.guandan.model.Card
import com.example.guandan.model.CardRank
import com.example.guandan.model.CardSuit

/**
 * 【修改】支持级牌排序的比较器
 * 排序规则（从小到大）：
 * - 2（非级牌时）：最小 (value=2)
 * - 3,4,5,6,7,8,9,10,J,Q,K,A：正常顺序 (3-14)
 * - 级牌（如打3时，3为级牌）：15（比A大，比小王小）
 * - 小王：16
 * - 大王：17
 */
class CardComparator(private val levelRank: CardRank? = null) : Comparator<Card> {

    override fun compare(card1: Card, card2: Card): Int {
        val value1 = getEffectiveValue(card1.rank)
        val value2 = getEffectiveValue(card2.rank)

        // 先按有效点数排序
        val rankCompare = value1 - value2
        if (rankCompare != 0) {
            return rankCompare
        }

        // 点数相同时按花色排序：黑桃>梅花>方块>红桃>王
        val suitOrder = mapOf<CardSuit, Int>(
            CardSuit.SPADE to 0,   // 黑桃
            CardSuit.CLUB to 1,    // 梅花
            CardSuit.DIAMOND to 2, // 方块
            CardSuit.HEART to 3,   // 红桃
            CardSuit.JOKER to 4    // 王
        )
        return suitOrder[card1.suit]!! - suitOrder[card2.suit]!!
    }

    /**
     * 【修改】获取有效比较值
     * 注意：CardRank.TWO 的原始 value=15，但在不是级牌时要当最小牌处理
     *
     * 映射规则：
     * - 2（非级牌）：2（最小）
     * - 3-10：3-10
     * - J：11, Q：12, K：13, A：14
     * - 级牌：15（比A大，比小王小）
     * - 红桃级牌（逢人配）：15（与级牌相同，但花色排序让它排在级牌后面）
     * - 小王：16
     * - 大王：17
     */
    private fun getEffectiveValue(rank: CardRank): Int {
        return when (rank) {
            // 王：固定值
            CardRank.JOKER_SMALL -> 16
            CardRank.JOKER_BIG -> 17

            // 【关键】2的处理
            CardRank.TWO -> {
                // 如果当前打2，2是级牌，按15处理
                // 如果当前不打2，2是最小的牌，按2处理
                if (levelRank == CardRank.TWO) 15 else 2
            }

            // 【关键】级牌（非2时）：按15处理
            else -> {
                if (rank == levelRank) {
                    // 这是级牌（且不是2，因为2上面已处理）
                    15
                } else {
                    // 普通牌（3-A）：用原始value
                    rank.value
                }
            }
        }
    }

    /**
     * 【新增】判断是否是逢人配（红桃级牌）
     */
    fun isFengRenPei(card: Card): Boolean {
        return card.suit == CardSuit.HEART && card.rank == levelRank
    }

}