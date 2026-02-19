package com.example.guandan.model

// 牌花色
enum class CardSuit(val displayName: String) {
    HEART("红桃"),
    DIAMOND("方块"),
    CLUB("梅花"),
    SPADE("黑桃"),
    JOKER("王")
}

// 牌点数枚举：value 从小到大，3 < 4 < 5 < ... < 2 < 小王 < 大王
enum class CardRank(val value: Int, val displayName: String) {
    THREE(3, "3"),
    FOUR(4, "4"),
    FIVE(5, "5"),
    SIX(6, "6"),
    SEVEN(7, "7"),
    EIGHT(8, "8"),
    NINE(9, "9"),
    TEN(10, "10"),
    JACK(11, "J"),
    QUEEN(12, "Q"),
    KING(13, "K"),
    ACE(14, "A"),
    TWO(15, "2"),
    JOKER_SMALL(16, "小王"),
    JOKER_BIG(17, "大王");

    // 可选：判断是否是王
    fun isJoker() = this == JOKER_SMALL || this == JOKER_BIG
}

// 单张牌模型
data class Card(
    val suit: CardSuit,
    val rank: CardRank,
    var isSelected: Boolean = false // 是否被选中（出牌用）
) {
    // 获取牌的显示名称（如：红桃3、大王）
    fun getDisplayName(): String {
        return if (rank.isJoker()) {
            rank.displayName
        } else {
            "${suit.displayName}${rank.displayName}"
        }
    }

    // 获取牌的资源名称（用于加载图片，如：heart_3、joker_big）
    fun getResName(): String {
        return if (rank.isJoker()) {
            "joker_${rank.displayName.substring(0, 1).lowercase()}"
        } else {
            "${suit.name.lowercase()}_${rank.displayName.lowercase()}"
        }
    }

    // 判断是否是逢人配（红桃3）
    fun isFengRenPei(): Boolean {
        return suit == CardSuit.HEART && rank == CardRank.THREE
    }
}