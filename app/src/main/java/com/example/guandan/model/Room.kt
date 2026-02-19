package com.example.guandan.model

// 游戏房间模型（适配掼蛋规则）
data class GameRoom(
    val roomId: String,
    val gameMode: GameMode,
    val players: MutableList<Player> = mutableListOf(),
    var isStarted: Boolean = false,
    var currentPlayerId: String = "", // 当前出牌玩家ID
    var tributeState: TributeState = TributeState.NONE, // 进贡状态
    var currentPokerType: String? = null, // 当前出牌的牌型（如：单张、对子、顺子）
    var roundCount: Int = 1, // 回合数
    var lastOutCards: MutableList<Card> = mutableListOf(), // 上一轮出的牌
    var lastOutPlayerId: String = "" // 上一轮出牌玩家ID
)

// 进贡状态枚举
enum class TributeState {
    NONE,        // 无进贡
    TRIBUTING,   // 进贡中
    RECEIVING,   // 接贡中
    COMPLETED    // 进贡完成
}