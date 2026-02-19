package com.example.guandan.model

// 游戏模式枚举（必须保证ordinal顺序：SINGLE_PLAYER=0，TWO_PLAYER_VS_AI=1，以此类推）
enum class GameMode {
    SINGLE_PLAYER,       // 单人模式（1人VS3AI）
    TWO_PLAYER_VS_AI,    // 双人模式（2人VS2AI）
    THREE_PLAYER_VS_AI,  // 三人模式（3人VS1AI）
    ONLINE_4_PLAYERS     // 四人联网模式
}