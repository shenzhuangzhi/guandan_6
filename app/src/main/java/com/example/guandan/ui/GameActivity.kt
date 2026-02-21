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

    // 【修改】保存两队等级，分别升级
    private var savedTeam0Level: Int = 2
    private var savedTeam1Level: Int = 2

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

            initGame(gameMode, savedTeam0Level, savedTeam1Level)

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

    // 修改 initGame 函数签名
    private fun initGame(gameMode: GameMode, restoreTeam0Level: Int? = null, restoreTeam1Level: Int? = null, firstPlayerPosition: Int = 0) {
        guandanGame = GuandanGame()

        if (restoreTeam0Level != null && restoreTeam1Level != null) {
            guandanGame?.setTeamLevels(restoreTeam0Level, restoreTeam1Level)
        }

        // 【修改】传入位置索引
        gameRoom = guandanGame?.initGame(gameMode, firstPlayerPosition)
        humanPlayer = gameRoom?.players?.firstOrNull { !it.isAI }

        if (gameRoom == null || humanPlayer == null) {
            Toast.makeText(this, "游戏初始化失败", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (restoreTeam0Level != null && restoreTeam1Level != null) {
            guandanGame?.resortAllCards()
        }

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


    // 修改 restartGame 函数
    private fun restartGame() {
        handler.removeCallbacksAndMessages(null)
        selectedCards.clear()

        // 【关键】获取头游位置
        val touYouPosition = guandanGame?.lastTouYouPosition ?: 0

        initGame(currentGameMode, savedTeam0Level, savedTeam1Level, touYouPosition)

        if (gameRoom?.players?.find { it.isCurrentTurn }?.isAI == true) {
            startAIAutoPlayChain()
        }

        val currentPlayer = gameRoom?.players?.find { it.isCurrentTurn }
        Toast.makeText(this, "已重新开牌，${currentPlayer?.name}先出", Toast.LENGTH_SHORT).show()
    }

    // 检查并启动AI链（统一入口）
// 检查并启动AI链（统一入口）
    private fun checkAndStartAIChain() {
        val room = gameRoom ?: return
        val currentPlayer = room.players.find { it.isCurrentTurn } ?: return

        // 如果当前是AI回合且没有在运行AI链，则启动
        if (currentPlayer.isAI && !isAIChainRunning) {
            android.util.Log.d("AI_CHAIN", "检测到AI回合且链未运行，启动AI链")
            startAIAutoPlayChain()
        } else {
            android.util.Log.d("AI_CHAIN", "无需启动AI链: isAI=${currentPlayer.isAI}, isRunning=$isAIChainRunning")
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
            // 【修改】使用当前玩家所在队伍的级牌更新数据
            cardAdapter.updateData(player.cards, game.currentLevelRank)
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
        if (isAIChainRunning) {
            android.util.Log.d("AI_CHAIN", "AI链已在运行，忽略重复启动")
            return
        }

        // 【关键】清除之前的所有回调，确保干净启动
        handler.removeCallbacksAndMessages(null)

        isAIChainRunning = true
        android.util.Log.d("AI_CHAIN", "========== 启动AI链 ==========")

        // 立即开始，不延迟
        processNextAIPlayer()
    }

    // 处理下一个AI玩家
    private fun processNextAIPlayer() {
        val room = gameRoom ?: run {
            android.util.Log.e("AI_CHAIN", "gameRoom为空，停止AI链")
            isAIChainRunning = false
            return
        }
        val game = guandanGame ?: run {
            android.util.Log.e("AI_CHAIN", "guandanGame为空，停止AI链")
            isAIChainRunning = false
            return
        }

        // 检查游戏是否结束
        if (game.isGameOver()) {
            android.util.Log.d("AI_CHAIN", "游戏结束，停止AI链")
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

        android.util.Log.d("AI_CHAIN", "AI玩家 ${currentPlayer.name} 开始决策，剩余${currentPlayer.cards.size}张牌")

        // 【关键修改】AI执行出牌前，先检查是否真的轮到它
        if (!currentPlayer.isCurrentTurn) {
            android.util.Log.w("AI_CHAIN", "状态不同步，${currentPlayer.name} 不是当前回合，停止AI链")
            isAIChainRunning = false
            return
        }

        // AI执行出牌
        val playedCard = game.autoPlayOneCard(currentPlayer)

        // 【关键修改】检查autoPlayOneCard是否正确执行了过牌或出牌
        // 如果playedCard为null且lastPlayedCards没有变化，说明是过牌
        val currentLastCards = game.lastPlayedCardsPublic
        val aiPlayedName = game.lastPlayerNamePublic
        val actuallyPlayed = playedCard != null && currentLastCards.isNotEmpty() && aiPlayedName == currentPlayer.name

        android.util.Log.d("AI_CHAIN", "${currentPlayer.name} 出牌结果: playedCard=${playedCard != null}, actuallyPlayed=$actuallyPlayed, lastName=$aiPlayedName")

        // 更新UI
        playerLastCards[currentPlayer.id] = if (actuallyPlayed) currentLastCards.toList() else emptyList()
        playerHasPlayed[currentPlayer.id] = true

        updateAllUI()

        // 检查游戏是否结束
        if (game.isGameOver()) {
            android.util.Log.d("AI_CHAIN", "AI出牌后游戏结束")
            isAIChainRunning = false
            gameOver()
            return
        }

        // 【关键修改】无论AI是出牌还是过牌，都继续下一个
        // 延迟后继续下一个AI（给UI刷新时间）
        handler.postDelayed({
            // 递归调用前检查是否还在运行
            if (isAIChainRunning) {
                processNextAIPlayer()
            } else {
                android.util.Log.d("AI_CHAIN", "AI链已被停止，不再继续")
            }
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
        val game = guandanGame ?: return
        val curr = room.players.find { it.isCurrentTurn }

        // 【修改】显示当前局固定的级牌（不随出牌玩家变化）
        val fixedLevel = game.getFixedLevel()
        binding.tvCurrentPlayer.text = "当前打${fixedLevel}级(🔵${game.team0Level}🔴${game.team1Level}) | 出牌：${curr?.name ?: "无"}"

        room.players.forEach { player ->
            val teamColor = if (player.team == 0) "🔵" else "🔴"
            val teammateMark = if (player.team == 0) "(友)" else "(敌)"
            val nameText = "${teamColor}${player.name}${teammateMark}\n剩${player.cards.size}张"

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
        val room = gameRoom ?: return

        // 【关键】先保存升级前的两队等级
        val oldTeam0Level = game.team0Level
        val oldTeam1Level = game.team1Level

        // 获取赢家（内部会执行升级）
        val winner = game.getWinner()
        if (winner == null) return

        // 【关键】升级后马上保存新的两队等级，以便下一局使用
        savedTeam0Level = game.team0Level
        savedTeam1Level = game.team1Level

        // 计算实际升级级数
        val team0Upgrade = savedTeam0Level - oldTeam0Level
        val team1Upgrade = savedTeam1Level - oldTeam1Level

        // 计算排名
        val sortedPlayers = room.players.sortedBy { it.cards.size }
        val winnerRank = sortedPlayers.indexOfFirst { it.id == winner.id } + 1
        val teammate = sortedPlayers.find { it.team == winner.team && it.id != winner.id }
        val teammateRank = if (teammate != null) sortedPlayers.indexOfFirst { it.id == teammate.id } + 1 else 4

        // 判断是否过A
        val winnerTeam = winner.team
        val winnerOldLevel = if (winnerTeam == 0) oldTeam0Level else oldTeam1Level
        val winnerNewLevel = if (winnerTeam == 0) savedTeam0Level else savedTeam1Level
        val isOverA = winnerOldLevel == 14 && teammateRank <= 3
        val needRetryA = winnerOldLevel == 14 && teammateRank == 4

        // 构建提示信息
        val message = StringBuilder()
        message.appendLine("🎉 游戏结束！")
        message.appendLine()
        message.appendLine("🏆 赢家：${winner.name}（头游）")
        message.appendLine("👥 队友：${teammate?.name ?: "无"}（${getRankText(teammateRank)}）")
        message.appendLine()
        message.appendLine("📊 本局结果：")
        sortedPlayers.forEachIndexed { index, player ->
            val rank = index + 1
            val teamMark = if (player.team == 0) "🔵" else "🔴"
            message.appendLine("  ${rank}. ${teamMark}${player.name} - 剩${player.cards.size}张")
        }
        message.appendLine()
        message.appendLine("🎯 升级情况：")
        message.appendLine("  🔵0队：${oldTeam0Level}级 -> ${savedTeam0Level}级")
        message.appendLine("  🔴1队：${oldTeam1Level}级 -> ${savedTeam1Level}级")

        if (needRetryA) {
            message.appendLine()
            message.appendLine("  ❌ 打A失败！队友为末游")
            message.appendLine("  需退回2重打")
        } else if (isOverA) {
            message.appendLine()
            message.appendLine("🎊🎊🎊 恭喜${winnerTeam}队成功过A！🎊🎊🎊")
        }

        AlertDialog.Builder(this)
            .setTitle("游戏结束")
            .setMessage(message.toString())
            .setPositiveButton("确定") { _, _ ->
                if (isOverA) {
                    finish()
                } else {
                    // 【修改】传入两队最新等级
                    restartGameWithLevel(savedTeam0Level, savedTeam1Level)
                }
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 【新增】获取排名文字
     */
    private fun getRankText(rank: Int): String {
        return when (rank) {
            1 -> "头游"
            2 -> "二游"
            3 -> "三游"
            4 -> "末游"
            else -> "未知"
        }
    }


    // 修改 restartGameWithLevel 函数
    private fun restartGameWithLevel(team0Level: Int, team1Level: Int) {
        handler.removeCallbacksAndMessages(null)
        selectedCards.clear()

        savedTeam0Level = team0Level
        savedTeam1Level = team1Level

        // 【关键】获取头游位置
        val touYouPosition = guandanGame?.lastTouYouPosition ?: 0
        println("重新开始游戏，头游位置=$touYouPosition")

        guandanGame?.resetUpgradeFlag()

        // 【关键】传入头游位置
        initGame(currentGameMode, team0Level, team1Level, touYouPosition)

        if (gameRoom?.players?.find { it.isCurrentTurn }?.isAI == true) {
            startAIAutoPlayChain()
        }

        val currentPlayer = gameRoom?.players?.find { it.isCurrentTurn }
        val currentTeam = currentPlayer?.team ?: 0
        val currentLevel = if (currentTeam == 0) team0Level else team1Level
        Toast.makeText(this, "下一局：${currentPlayer?.name}先出，打$currentLevel", Toast.LENGTH_SHORT).show()
    }



    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        isAIChainRunning = false
    }
    override fun onResume() {
        super.onResume()
        android.util.Log.d("GameActivity", "onResume")
        // 从后台返回时，检查是否需要启动AI
        checkAndStartAIChain()
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("GameActivity", "onPause")
        // 进入后台时停止AI链
        handler.removeCallbacksAndMessages(null)
        isAIChainRunning = false
    }

}