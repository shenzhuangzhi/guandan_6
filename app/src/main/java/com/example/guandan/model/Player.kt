package com.example.guandan.model

// 玩家模型（适配掼蛋规则）
data class Player(
    val id: String,
    val name: String,
    val isAI: Boolean = false,       // 是否AI玩家
    val isOnline: Boolean = true,    // 是否在线（联网模式用）
    var cards: MutableList<Card> = mutableListOf(), // 手牌
    var score: Int = 0,              // 积分
    var isCurrentTurn: Boolean = false, // 是否当前出牌回合
    var isWinner: Boolean = false,   // 是否本轮赢家
    var needTribute: Boolean = false, // 是否需要进贡
    var tributeCard: Card? = null,   // 进贡的牌
    var receiveTributeCard: Card? = null, // 接贡的牌
    var isFirstOut: Boolean = false,  // 是否首轮出牌
    var team: Int = 0                // 【新增】队伍：0或1，掼蛋分两队
)