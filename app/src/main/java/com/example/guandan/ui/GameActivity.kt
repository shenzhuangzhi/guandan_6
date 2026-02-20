package com.example.guandan.ui

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.guandan.databinding.ActivityGameBinding
import com.example.guandan.logic.GuandanGame
import com.example.guandan.model.Card
import com.example.guandan.model.GameMode
import com.example.guandan.model.GameRoom
import com.example.guandan.model.Player
import com.example.guandan.ui.adapter.CardAdapter
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.View
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class GameActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGameBinding
    private var guandanGame: GuandanGame? = null
    private var gameRoom: GameRoom? = null
    private lateinit var cardAdapter: CardAdapter
    private val selectedCards = mutableListOf<Card>()
    private var humanPlayer: Player? = null

    private val handler = Handler(Looper.getMainLooper())
    private val AI_PLAY_DELAY = 1000L

    // 记录每个玩家上轮出的牌
    private val playerLastCards = mutableMapOf<String, List<Card>>()
    // 记录每个玩家是否出过牌（用于首次判断）
    private val playerHasPlayed = mutableMapOf<String, Boolean>()

    // 保存当前游戏模式，用于重新开牌
    private var currentGameMode: GameMode = GameMode.SINGLE_PLAYER

    // 服务器配置
    private val UPDATE_SERVER_URL = "http://120.26.136.185/guandan"
    private val APK_NAME = "app-release.apk"

    // 标记是否正在运行AI链，防止重复启动
    private var isAIChainRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityGameBinding.inflate(layoutInflater)
            setContentView(binding.root)

            val gameModeOrdinal = intent.getIntExtra("GAME_MODE_ORDINAL", 0)
            val gameMode = GameMode.values().getOrNull(gameModeOrdinal) ?: GameMode.SINGLE_PLAYER
            currentGameMode = gameMode

            initGame(gameMode)

            // 设置按钮点击事件
            binding.btnSettings.setOnClickListener { showSettingsDialog() }

            binding.btnPlayCards.setOnClickListener { playSelectedCards() }
            binding.btnPass.setOnClickListener { passTurn() }

            // 检查是否需要启动AI
            checkAndStartAIChain()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "启动失败：${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // 初始化游戏
    private fun initGame(gameMode: GameMode) {
        guandanGame = GuandanGame()
        gameRoom = guandanGame?.initGame(gameMode)
        humanPlayer = gameRoom?.players?.firstOrNull { !it.isAI }

        if (gameRoom == null || humanPlayer == null) {
            Toast.makeText(this, "游戏初始化失败", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 初始化记录
        playerLastCards.clear()
        playerHasPlayed.clear()
        gameRoom?.players?.forEach { player ->
            playerLastCards[player.id] = emptyList()
            playerHasPlayed[player.id] = false
        }

        initCardRecyclerView()
        updateAllUI()
    }

    // 显示设置对话框
    private fun showSettingsDialog() {
        val options = arrayOf("重新开牌", "检查APP更新", "手动强制更新")
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRestartGameConfirmDialog()
                    1 -> checkForUpdate()
                    2 -> manualForceUpdate()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 显示重新开牌确认对话框
    private fun showRestartGameConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("重新开牌")
            .setMessage("确定要重新开牌吗？当前游戏进度将丢失。")
            .setPositiveButton("确定") { _, _ ->
                restartGame()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 重新开牌
    private fun restartGame() {
        // 停止AI自动出牌
        handler.removeCallbacksAndMessages(null)
        isAIChainRunning = false

        // 清空选中的牌
        selectedCards.clear()

        // 重新初始化游戏
        initGame(currentGameMode)

        // 检查是否需要启动AI
        checkAndStartAIChain()

        Toast.makeText(this, "已重新开牌", Toast.LENGTH_SHORT).show()
    }

    // 检查并启动AI链（统一入口）
    private fun checkAndStartAIChain() {
        val room = gameRoom ?: return
        val currentPlayer = room.players.find { it.isCurrentTurn } ?: return

        // 如果当前是AI回合且没有在运行AI链，则启动
        if (currentPlayer.isAI && !isAIChainRunning) {
            startAIAutoPlayChain()
        }
    }

    // 检查APP更新（优化版）
    private fun checkForUpdate() {
        val apkUrl = "$UPDATE_SERVER_URL/$APK_NAME"
        // 使用唯一文件名，避免冲突
        val uniqueName = "app-update-${System.currentTimeMillis()}.apk"
        val localFile = File(filesDir, uniqueName)

        AlertDialog.Builder(this)
            .setTitle("检查更新")
            .setMessage("从服务器检查并下载最新版本？\n\n$apkUrl")
            .setPositiveButton("开始下载") { _, _ ->
                Executors.newSingleThreadExecutor().execute {
                    try {
                        URL(apkUrl).openStream().use { `in` ->
                            localFile.outputStream().use { out ->
                                `in`.copyTo(out)
                            }
                        }
                        runOnUiThread { launchInstallApk(localFile) }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                        // 下载失败也要清理
                        localFile.delete()
                    }
                }
            }
            .setNegativeButton("取消") { _, _ ->
                // 取消时清理（如果文件已存在）
                localFile.delete()
            }
            .show()
    }

    // 手动强制更新（外网手动更新）
    private fun manualForceUpdate() {
        // 核心修改：把局域网 IP 改成阿里云服务器公网 IP + APK 路径
        val apkUrl = "$UPDATE_SERVER_URL/$APK_NAME"
        // 使用唯一文件名，避免冲突
        val uniqueName = "app-release-${System.currentTimeMillis()}.apk"
        val localFile = File(filesDir, uniqueName)

        AlertDialog.Builder(this)
            .setTitle("手动强制更新")
            .setMessage("从服务器 HTTP 下载并安装？\n\n$apkUrl")
            .setPositiveButton("开始下载") { _, _ ->
                Executors.newSingleThreadExecutor().execute {
                    try {
                        URL(apkUrl).openStream().use { `in` ->
                            localFile.outputStream().use { out ->
                                `in`.copyTo(out)
                            }
                        }
                        runOnUiThread { launchInstallApk(localFile) }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                        // 下载失败也要清理
                        localFile.delete()
                    }
                }
            }
            .setNegativeButton("取消") { _, _ ->
                // 取消时清理（如果文件已存在）
                localFile.delete()
            }
            .show()
    }

    // 启动安装APK
    private fun launchInstallApk(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }

            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "安装失败：${e.message}", Toast.LENGTH_SHORT).show()
            // 安装失败也清理文件
            file.delete()
        }
    }

    private fun initCardRecyclerView() {
        val playerCards = humanPlayer?.cards?.toMutableList() ?: mutableListOf()
        cardAdapter = CardAdapter(playerCards) { card ->
            if (card.isSelected) selectedCards.add(card)
            else selectedCards.remove(card)
        }

        binding.rvCards.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCards.adapter = cardAdapter

        binding.rvCards.clipChildren = false
        binding.rvCards.clipToPadding = false
    }

    private fun playSelectedCards() {
        val game = guandanGame ?: return
        val player = humanPlayer ?: return

        if (selectedCards.isEmpty()) {
            Toast.makeText(this, "请选择要出的牌", Toast.LENGTH_SHORT).show()
            return
        }

        val ok = game.playCards(player.id, selectedCards)
        if (ok) {
            playerLastCards[player.id] = selectedCards.toList()
            playerHasPlayed[player.id] = true

            player.cards.forEach { it.isSelected = false }
            selectedCards.clear()
            cardAdapter.updateData(player.cards)
            updateAllUI()

            if (game.isGameOver()) {
                gameOver()
                return
            }

            // 人类出牌后，检查是否需要启动AI链
            checkAndStartAIChain()
        } else {
            Toast.makeText(this, "出牌不合法", Toast.LENGTH_SHORT).show()
        }
    }

    private fun passTurn() {
        val player = humanPlayer ?: return
        val playerId = player.id

        guandanGame?.passTurn(playerId)

        playerLastCards[playerId] = emptyList()
        playerHasPlayed[playerId] = true

        updateAllUI()

        // 人类过牌后，检查是否需要启动AI链
        checkAndStartAIChain()
    }

    // 【核心修复】AI自动出牌链 - 使用循环而非递归，更可靠
    private fun startAIAutoPlayChain() {
        // 防止重复启动
        if (isAIChainRunning) return

        isAIChainRunning = true
        handler.removeCallbacksAndMessages(null)

        processNextAIPlayer()
    }

    // 处理下一个AI玩家
    private fun processNextAIPlayer() {
        val room = gameRoom ?: run {
            isAIChainRunning = false
            return
        }
        val game = guandanGame ?: run {
            isAIChainRunning = false
            return
        }

        // 检查游戏是否结束
        if (game.isGameOver()) {
            isAIChainRunning = false
            gameOver()
            return
        }

        val currentPlayer = room.players.find { it.isCurrentTurn }

        // 找不到当前玩家
        if (currentPlayer == null) {
            android.util.Log.e("AI_CHAIN", "找不到当前玩家，停止AI链")
            isAIChainRunning = false
            return
        }

        // 如果不是AI回合，停止链（等待人类操作）
        if (!currentPlayer.isAI) {
            android.util.Log.d("AI_CHAIN", "轮到人类玩家 ${currentPlayer.name}，暂停AI链")
            isAIChainRunning = false
            return
        }

        android.util.Log.d("AI_CHAIN", "AI玩家 ${currentPlayer.name} 出牌")

        // AI执行出牌
        val playedCard = game.autoPlayOneCard(currentPlayer)
        val currentLastCards = game.lastPlayedCardsPublic
        val aiPlayedName = game.lastPlayerNamePublic

        val actuallyPlayed = playedCard != null && currentLastCards.isNotEmpty() && aiPlayedName == currentPlayer.name

        playerLastCards[currentPlayer.id] = if (actuallyPlayed) currentLastCards.toList() else emptyList()
        playerHasPlayed[currentPlayer.id] = true

        updateAllUI()

        // 检查游戏是否结束
        if (game.isGameOver()) {
            isAIChainRunning = false
            gameOver()
            return
        }

        // 延迟后继续下一个AI
        handler.postDelayed({
            processNextAIPlayer()
        }, AI_PLAY_DELAY)
    }

    private fun getCardDesc(cards: List<Card>): String {
        if (cards.isEmpty()) return "过牌"

        val firstCard = cards[0]
        val rankName = firstCard.rank.displayName
        val suitName = firstCard.suit.displayName

        return when (cards.size) {
            1 -> "$suitName$rankName"
            2 -> "$suitName$rankName（一对）"
            3 -> "$suitName$rankName（三个）"
            4 -> "$suitName$rankName（炸弹）"
            in 5..8 -> "$suitName$rankName（${cards.size}张炸弹）"
            else -> "${suitName}${rankName}等${cards.size}张"
        }
    }

    private fun updateAllUI() {
        updatePlayerInfo()
        updateLastPlayedDisplay()
        updateTurnIndicator()
    }

    // 新增：更新回合指示器，明确显示当前是谁的回合
    private fun updateTurnIndicator() {
        val room = gameRoom ?: return
        val currentPlayer = room.players.find { it.isCurrentTurn }

        // 高亮当前玩家
        val isHumanTurn = currentPlayer?.id == humanPlayer?.id

        // 可以根据需要在这里添加更明显的UI提示
        // 例如改变边框颜色、显示动画等
        binding.tvCurrentPlayer.setTextColor(
            if (isHumanTurn) android.graphics.Color.GREEN
            else android.graphics.Color.WHITE
        )
    }

    private fun updatePlayerInfo() {
        val room = gameRoom ?: return
        val curr = room.players.find { it.isCurrentTurn }

        binding.tvCurrentPlayer.text = "当前出牌：${curr?.name ?: "无"}"

        room.players.forEach { player ->
            val nameText = "${player.name}\n剩${player.cards.size}张"
            when {
                player.isAI && room.players.indexOf(player) == 1 -> {
                    binding.tvAi1.text = nameText
                }
                player.isAI && room.players.indexOf(player) == 2 -> {
                    binding.tvAi2.text = nameText
                }
                player.isAI && room.players.indexOf(player) == 3 -> {
                    binding.tvAi3.text = nameText
                }
                !player.isAI -> {
                    binding.tvPlayer.text = nameText
                }
            }
        }
    }

    private fun updateLastPlayedDisplay() {
        val room = gameRoom ?: return

        val ai1Id = room.players.getOrNull(1)?.id
        displayPlayerLastPlay(ai1Id, binding.layoutLastAi1, binding.tvPassAi1)

        val ai2Id = room.players.getOrNull(2)?.id
        displayPlayerLastPlay(ai2Id, binding.layoutLastAi2, binding.tvPassAi2)

        val ai3Id = room.players.getOrNull(3)?.id
        displayPlayerLastPlay(ai3Id, binding.layoutLastAi3, binding.tvPassAi3)

        val playerId = humanPlayer?.id
        displayPlayerLastPlay(playerId, binding.layoutLastPlayer, binding.tvPassPlayer)
    }

    private fun displayPlayerLastPlay(playerId: String?, layout: LinearLayout, passText: android.widget.TextView) {
        if (playerId == null) return

        val hasPlayed = playerHasPlayed[playerId] ?: false
        val cards = playerLastCards[playerId] ?: emptyList()

        layout.removeAllViews()
        layout.visibility = View.GONE
        passText.visibility = View.GONE

        if (!hasPlayed) {
            return
        }

        if (cards.isEmpty()) {
            passText.visibility = View.VISIBLE
        } else {
            layout.visibility = View.VISIBLE
            layout.setBackgroundColor(0x00000000)
            displayCardsInLayout(layout, cards)
        }
    }

    private fun displayCardsInLayout(layout: LinearLayout, cards: List<Card>) {
        layout.removeAllViews()
        layout.setBackgroundColor(0x00000000)

        val density = resources.displayMetrics.density
        val cardWidth = (47 * density).toInt()
        val cardHeight = (67 * density).toInt()

        cards.forEach { card ->
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                    marginStart = (2 * density).toInt()
                    marginEnd = (2 * density).toInt()
                }
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(0x00000000)

                val resId = resources.getIdentifier(
                    card.getResName(),
                    "drawable",
                    packageName
                )
                val fallbackResId = try {
                    com.example.guandan.R.drawable.card_background
                } catch (e: Exception) {
                    android.R.drawable.ic_menu_gallery
                }
                setImageResource(if (resId != 0) resId else fallbackResId)
            }
            layout.addView(imageView)
        }
    }

    private fun gameOver() {
        val game = guandanGame ?: return
        val win = game.getWinner()
        Toast.makeText(this, "游戏结束！赢家：${win?.name ?: "无"}", Toast.LENGTH_LONG).show()
        handler.postDelayed({ finish() }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        isAIChainRunning = false
    }

    override fun onResume() {
        super.onResume()
        // 从后台返回时，检查是否需要启动AI
        checkAndStartAIChain()
    }

    override fun onPause() {
        super.onPause()
        // 进入后台时停止AI链
        handler.removeCallbacksAndMessages(null)
        isAIChainRunning = false
    }
}