package com.example.guandan.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.guandan.databinding.ActivityMainBinding
import com.example.guandan.model.GameMode

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isButtonClickable = true // 防重复点击标记

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 单人模式
        binding.btnSinglePlayer.setOnClickListener {
            onGameModeClick(GameMode.SINGLE_PLAYER)
        }

        // 双人模式
        binding.btnTwoPlayerVsAi.setOnClickListener {
            onGameModeClick(GameMode.TWO_PLAYER_VS_AI)
        }

        // 三人模式
        binding.btnThreePlayerVsAi.setOnClickListener {
            onGameModeClick(GameMode.THREE_PLAYER_VS_AI)
        }

        // 四人联网模式（暂未实现完整逻辑，仅跳转）
        binding.btnOnline4Players.setOnClickListener {
            onGameModeClick(GameMode.ONLINE_4_PLAYERS)
        }
    }

    // 游戏模式点击处理（防重复点击）
    private fun onGameModeClick(gameMode: GameMode) {
        if (!isButtonClickable) return

        isButtonClickable = false
        startGame(gameMode)

        // 1秒后恢复点击
        binding.root.postDelayed({
            isButtonClickable = true
        }, 1000)
    }

    // 启动游戏界面（修复：枚举传参改用ordinal，避免序列化问题）
    private fun startGame(gameMode: GameMode) {
        val intent = Intent(this, GameActivity::class.java).apply {
            // 替换：不用name/序列化，改用ordinal（枚举下标）传参
            putExtra("GAME_MODE_ORDINAL", gameMode.ordinal)
        }
        startActivity(intent)
    }
}