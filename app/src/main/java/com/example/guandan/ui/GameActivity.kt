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

// 导入滑动选牌需要的类
import androidx.recyclerview.widget.RecyclerView
import android.view.MotionEvent


class GameActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGameBinding
    private var guandanGame: GuandanGame? = null
    private var gameRoom: GameRoom? = null
    private lateinit var cardAdapter: CardAdapter
    private val selectedCards = mutableListOf<Card>()
    private var humanPlayer: Player? = null

    private val handler = Handler(Looper.getMainLooper())
    private val AI_PLAY_DELAY = 2000L  // 【修改】AI间隔改为2秒

    // 记录每个玩家上轮出的牌
    private val playerLastCards = mutableMapOf<String, List<Card>>()
    // 记录每个玩家是否出过牌（用于首次判断）
    private val playerHasPlayed = mutableMapOf<String, Boolean>()

    // 保存当前游戏模式，用于重新开牌
    private var currentGameMode: GameMode = GameMode.SINGLE_PLAYER

    // 保存两队等级，分别升级
    private var savedTeam0Level: Int = 2
    private var savedTeam1Level: Int = 2

    // 服务器配置
    private val UPDATE_SERVER_URL = "http://120.26.136.185/guandan"
    private val APK_NAME = "app-release.apk"

    // 标记是否正在运行AI链，防止重复启动
    private var isAIChainRunning = false

    // 标记游戏是否已结束（用于判断是否可以退出）
    private var isGameFinished = false

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

        // 传入位置索引
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
        val options = arrayOf("重新开牌", "回到主界面", "终止游戏", "检查APP更新", "手动强制更新")
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRestartGameConfirmDialog()
                    1 -> showBackToMainConfirmDialog()
                    2 -> showExitGameConfirmDialog()
                    3 -> checkForUpdate()
                    4 -> manualForceUpdate()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 显示回到主界面确认对话框
    private fun showBackToMainConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("回到主界面")
            .setMessage("确定要回到主界面吗？当前游戏进度将保留，可以重新进入继续游戏。")
            .setPositiveButton("确定") { _, _ ->
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 显示终止游戏确认对话框（退出整个APP）
    private fun showExitGameConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("终止游戏")
            .setMessage("确定要退出整个APP吗？")
            .setPositiveButton("确定退出") { _, _ ->
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
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

    // 拦截返回键，游戏未结束时弹出确认对话框
    override fun onBackPressed() {
        if (isGameFinished) {
            super.onBackPressed()
            return
        }

        val game = guandanGame
        val room = gameRoom

        if (game == null || room == null || game.isGameOver()) {
            super.onBackPressed()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("确认退出")
            .setMessage("牌局正在进行中，确定要退出吗？\n（当前进度将丢失）")
            .setPositiveButton("确定退出") { _, _ ->
                super.onBackPressed()
            }
            .setNegativeButton("继续游戏", null)
            .setCancelable(true)
            .show()
    }

    // 修改 restartGame 函数
    private fun restartGame() {
        handler.removeCallbacksAndMessages(null)

        forceClearAllSelection("restartGame")

        val touYouPosition = guandanGame?.lastTouYouPosition ?: 0

        initGame(currentGameMode, savedTeam0Level, savedTeam1Level, touYouPosition)

        if (gameRoom?.players?.find { it.isCurrentTurn }?.isAI == true) {
            startAIAutoPlayChain()
        }

        val currentPlayer = gameRoom?.players?.find { it.isCurrentTurn }
        Toast.makeText(this, "已重新开牌，${currentPlayer?.name}先出", Toast.LENGTH_SHORT).show()
    }

    // 检查并启动AI链（统一入口）
    private fun checkAndStartAIChain() {
        val room = gameRoom ?: return
        val currentPlayer = room.players.find { it.isCurrentTurn } ?: return

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
                        localFile.delete()
                    }
                }
            }
            .setNegativeButton("取消") { _, _ ->
                localFile.delete()
            }
            .show()
    }

    // 手动强制更新（外网手动更新）
    private fun manualForceUpdate() {
        val apkUrl = "$UPDATE_SERVER_URL/$APK_NAME"
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
                        localFile.delete()
                    }
                }
            }
            .setNegativeButton("取消") { _, _ ->
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
            file.delete()
        }
    }
    // 【修改】初始化RecyclerView，支持滑动选牌和手动点击分离
    private fun initCardRecyclerView() {
        // 直接使用 humanPlayer?.cards，不要复制
        val playerCards = humanPlayer?.cards ?: emptyList()

        // onCardClick增加isManualClick参数
        cardAdapter = CardAdapter(playerCards.toMutableList()) { card, isManualClick ->
            if (isManualClick) {
                // 手动点击时，先清空滑动选牌的数据，避免冲突
                clearSwipeSelectionData()
            }

            if (card.isSelected) selectedCards.add(card)
            else selectedCards.remove(card)
        }

        binding.rvCards.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCards.adapter = cardAdapter

        binding.rvCards.clipChildren = false
        binding.rvCards.clipToPadding = false

        // 添加滑动选牌功能 - 传入humanPlayer.cards的实时引用
        setupSwipeToSelect(binding.rvCards)
    }


    // 【新增】清空滑动选牌的数据
    private fun clearSwipeSelectionData() {
        android.util.Log.d("SELECT", "手动点击，清空滑动选牌的临时数据")

        selectedCards.clear()

        humanPlayer?.cards?.forEach { card ->
            card.isSelected = false
        }

        cardAdapter.notifyDataSetChanged()
    }
    // 设置滑动选牌

    // 【修改】设置滑动选牌 - 不再传入cards参数，直接使用humanPlayer?.cards
    private fun setupSwipeToSelect(recyclerView: RecyclerView) {
        recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            private var isSwiping = false
            private var startPosition = -1
            private val processedPositions = mutableSetOf<Int>()

            // 【新增】获取当前手牌列表的辅助函数
            private fun getCards(): List<Card> {
                return humanPlayer?.cards ?: emptyList()
            }

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                val cards = getCards()

                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val child = rv.findChildViewUnder(e.x, e.y)
                        if (child != null) {
                            val position = rv.getChildAdapterPosition(child)
                            if (position != RecyclerView.NO_POSITION && position < cards.size) {
                                isSwiping = true
                                startPosition = position
                                processedPositions.clear()
                                processedPositions.add(position)

                                val card = cards[position]
                                val newState = !card.isSelected

                                toggleCardAtPosition(rv, position, newState)

                                return true
                            }
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isSwiping) {
                            handleMoveEvent(rv, e)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        resetSwipeState()
                        // 【新增】滑动结束时显示选中的牌
                       // showSelectedCardsInfo()
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        if (isSwiping) handleMoveEvent(rv, e)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        resetSwipeState()
                        // 【新增】滑动结束时显示选中的牌
                       // showSelectedCardsInfo()
                    }
                }
            }

            private fun handleMoveEvent(rv: RecyclerView, e: MotionEvent) {
                val cards = getCards()
                val child = rv.findChildViewUnder(e.x, e.y)
                if (child != null) {
                    val position = rv.getChildAdapterPosition(child)
                    if (position != RecyclerView.NO_POSITION && position < cards.size && position !in processedPositions) {
                        processedPositions.add(position)

                        val card = cards[position]
                        val startCard = if (startPosition >= 0 && startPosition < cards.size) cards[startPosition] else null
                        val targetState = startCard?.isSelected ?: !card.isSelected

                        if (card.isSelected != targetState) {
                            toggleCardAtPosition(rv, position, targetState)
                        }
                    }
                }
            }

            private fun resetSwipeState() {
                isSwiping = false
                startPosition = -1
                processedPositions.clear()
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    // 【新增】显示当前选中的牌信息（日志+提示）
    private fun showSelectedCardsInfo() {
        val count = selectedCards.size
        if (count == 0) {
            android.util.Log.d("CardSelect", "【选牌】当前未选中任何牌")
            // 可选：Toast提示
            // Toast.makeText(this, "未选牌", Toast.LENGTH_SHORT).show()
            return
        }

        // 按点数和花色排序，方便查看
        val sortedCards = selectedCards.sortedWith(compareBy<Card> { it.rank.value }.thenBy { it.suit.ordinal })

        // 构建牌面描述
        val cardsDesc = sortedCards.joinToString(", ") { "${it.suit.symbol}${it.rank.displayName}" }

        // 输出日志
       // android.util.Log.d("CardSelect", "【选牌】共选中 $count 张牌: $cardsDesc")
        //android.util.Log.d("CardSelect", "【选牌】详细列表: ${sortedCards.map { "${it.suit.name}_${it.rank.name}(选中=${it.isSelected})" }}")

        // 输出到控制台（方便调试）
        println("【选牌】共选中 $count 张牌: $cardsDesc")

        // 显示Toast提示（短提示，避免干扰）
        val toastMsg = if (count <= 5) {
            "已选: $cardsDesc"
        } else {
            "已选 $count 张牌"
        }
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
    }

    // 切换指定位置的牌选中状态
    private fun toggleCardAtPosition(recyclerView: RecyclerView, position: Int, select: Boolean) {
        val card = humanPlayer?.cards?.getOrNull(position) ?: return

        if (card.isSelected == select) return

        card.isSelected = select

        if (select) {
            // 【修改】掼蛋有两副牌，相同花色点数可以重复，直接添加不检查重复
            selectedCards.add(card)
            //android.util.Log.d("CardSelect", "【添加】位置$position: ${card.getShortName()} (当前共${selectedCards.size}张)")

            // 【新增】检查是否有重复（调试用）
            val duplicates = selectedCards.groupBy { "${it.suit.name}_${it.rank.name}" }
                .filter { it.value.size > 1 }
            if (duplicates.isNotEmpty()) {
                //android.util.Log.d("CardSelect", "【调试】发现重复: ${duplicates.map { "${it.key}=${it.value.size}张" }}")
            }
        } else {
            // 【修改】只移除这一个实例（用removeAt找索引，避免removeAll删多个）
            val index = selectedCards.indexOf(card)
            if (index >= 0) {
                selectedCards.removeAt(index)
                //android.util.Log.d("CardSelect", "【移除】位置$position: ${card.getShortName()} (当前共${selectedCards.size}张)")
            } else {
                android.util.Log.d("CardSelect", "【警告】位置$position: ${card.getShortName()} 未找到，无法移除")
            }
        }

        val holder = recyclerView.findViewHolderForAdapterPosition(position) as? CardAdapter.CardViewHolder
        if (holder != null) {
            holder.updateSelectedState(card)
        } else {
            cardAdapter.notifyItemChanged(position, "SELECTION")
        }
    }

    // 强制清空所有选中状态
    private fun forceClearAllSelection(from: String = "unknown") {
        android.util.Log.d("SELECT", "[$from] 强制清空所有选中状态")

        selectedCards.clear()

        humanPlayer?.cards?.forEach { card ->
            card.isSelected = false
        }

        cardAdapter.notifyDataSetChanged()

        android.util.Log.d("SELECT", "清空完成，选中数=${selectedCards.size}")
    }

    // 出牌
    private fun playSelectedCards() {
        if (selectedCards.isEmpty()) {
            forceClearAllSelection("playSelectedCards_empty")
            Toast.makeText(this, "请选择要出的牌", Toast.LENGTH_SHORT).show()
            return
        }

        val game = guandanGame ?: return
        val player = humanPlayer ?: return

        // 【修改】验证选中的牌是否都在手牌中（按对象引用比较，保留重复牌）
        val validSelected = mutableListOf<Card>()
        val remainingHand = player.cards.toMutableList()  // 复制手牌，用于匹配

        for (selectedCard in selectedCards) {
            // 在手牌中找这个对象（按引用匹配）
            val matchIndex = remainingHand.indexOf(selectedCard)
            if (matchIndex >= 0) {
                validSelected.add(selectedCard)
                remainingHand.removeAt(matchIndex)  // 移除已匹配的，避免重复匹配同一张
            } else {
                android.util.Log.w("CardSelect", "选中的牌不在手牌中: ${selectedCard.getShortName()}, isSelected=${selectedCard.isSelected}")
                // 【调试】输出手牌内容
                //android.util.Log.w("CardSelect", "当前手牌: ${player.cards.map { "${it.getShortName()}(ref=${System.identityHashCode(it)})" }}")
                //android.util.Log.w("CardSelect", "选中牌: ${selectedCards.map { "${it.getShortName()}(ref=${System.identityHashCode(it)})" }}")
            }
        }

        selectedCards.clear()
        selectedCards.addAll(validSelected)

        if (selectedCards.isEmpty()) {
            forceClearAllSelection("playSelectedCards_invalid")
            Toast.makeText(this, "请选择要出的牌", Toast.LENGTH_SHORT).show()
            return
        }

        // 【修改】按玩家手牌顺序排序（不再去重）
        val sortedCards = selectedCards.sortedWith { c1, c2 ->
            val idx1 = player.cards.indexOf(c1)  // 按对象引用找位置
            val idx2 = player.cards.indexOf(c2)
            idx1 - idx2
        }
        selectedCards.clear()
        selectedCards.addAll(sortedCards)

        android.util.Log.d("CardSelect", "【出牌】准备出 ${selectedCards.size} 张牌: ${selectedCards.map { it.getShortName() }}")

        val ok = game.playCards(player.id, selectedCards)
        if (ok) {
            playerLastCards[player.id] = selectedCards.toList()
            playerHasPlayed[player.id] = true

            forceClearAllSelection("playSelectedCards_success")

            cardAdapter.updateData(player.cards, game.currentLevelRank)
            updateAllUI()

            if (game.isGameOver()) {
                gameOver()
                return
            }

            checkAndStartAIChain()
        } else {
            forceClearAllSelection("playSelectedCards_fail")
            Toast.makeText(this, "出牌不合法", Toast.LENGTH_SHORT).show()
        }
    }

    // 过牌
    private fun passTurn() {
        val player = humanPlayer ?: return
        val playerId = player.id

        guandanGame?.passTurn(playerId)

        playerLastCards[playerId] = emptyList()
        playerHasPlayed[playerId] = true

        forceClearAllSelection("passTurn")

        updateAllUI()

        checkAndStartAIChain()
    }

    // AI自动出牌链
    private fun startAIAutoPlayChain() {
        if (isAIChainRunning) {
            android.util.Log.d("AI_CHAIN", "AI链已在运行，忽略重复启动")
            return
        }

        handler.removeCallbacksAndMessages(null)

        isAIChainRunning = true
        android.util.Log.d("AI_CHAIN", "========== 启动AI链 ==========")

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

        if (game.isGameOver()) {
            android.util.Log.d("AI_CHAIN", "游戏结束，停止AI链")
            isAIChainRunning = false
            gameOver()
            return
        }

        val currentPlayer = room.players.find { it.isCurrentTurn }

        if (currentPlayer == null) {
            android.util.Log.e("AI_CHAIN", "找不到当前玩家，停止AI链")
            isAIChainRunning = false
            return
        }

        if (!currentPlayer.isAI) {
            android.util.Log.d("AI_CHAIN", "轮到人类玩家 ${currentPlayer.name}，暂停AI链")
            isAIChainRunning = false
            return
        }

        android.util.Log.d("AI_CHAIN", "AI玩家 ${currentPlayer.name} 开始决策，剩余${currentPlayer.cards.size}张牌")

        if (!currentPlayer.isCurrentTurn) {
            android.util.Log.w("AI_CHAIN", "状态不同步，${currentPlayer.name} 不是当前回合，停止AI链")
            isAIChainRunning = false
            return
        }

        val playedCard = game.autoPlayOneCard(currentPlayer)

        val currentLastCards = game.lastPlayedCardsPublic
        val aiPlayedName = game.lastPlayerNamePublic
        val actuallyPlayed = playedCard != null && currentLastCards.isNotEmpty() && aiPlayedName == currentPlayer.name

        android.util.Log.d("AI_CHAIN", "${currentPlayer.name} 出牌结果: playedCard=${playedCard != null}, actuallyPlayed=$actuallyPlayed, lastName=$aiPlayedName")

        playerLastCards[currentPlayer.id] = if (actuallyPlayed) currentLastCards.toList() else emptyList()
        playerHasPlayed[currentPlayer.id] = true

        updateAllUI()

        if (game.isGameOver()) {
            android.util.Log.d("AI_CHAIN", "AI出牌后游戏结束")
            isAIChainRunning = false
            gameOver()
            return
        }

        handler.postDelayed({
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

    // 更新回合指示器
    private fun updateTurnIndicator() {
        val room = gameRoom ?: return
        val currentPlayer = room.players.find { it.isCurrentTurn }

        val isHumanTurn = currentPlayer?.id == humanPlayer?.id

        binding.tvCurrentPlayer.setTextColor(
            if (isHumanTurn) android.graphics.Color.GREEN
            else android.graphics.Color.WHITE
        )
    }

    private fun updatePlayerInfo() {
        val room = gameRoom ?: return
        val game = guandanGame ?: return
        val curr = room.players.find { it.isCurrentTurn }

        val fixedLevel = game.getFixedLevel()
        binding.tvCurrentPlayer.text = "当前打${fixedLevel}级(🔵${game.team0Level}🔴${game.team1Level}) | 出牌：${curr?.name ?: "无"}"

        room.players.forEach { player ->
            val teamColor = if (player.team == 0) "🔵" else "🔴"
            val teammateMark = if (player.team == 0) "(友)" else "(敌)"
            val nameText = "${teamColor}${player.name}${teammateMark}"
            val cardText = "剩${player.cards.size}张"

            when {
                player.isAI && room.players.indexOf(player) == 1 -> {
                    binding.tvAi1.text = "$nameText\n$cardText"
                }
                player.isAI && room.players.indexOf(player) == 2 -> {
                    binding.tvAi2.text = "$nameText\n$cardText"
                }
                player.isAI && room.players.indexOf(player) == 3 -> {
                    binding.tvAi3.text = "$nameText\n$cardText"
                }
                !player.isAI -> {
                    binding.tvPlayer.text = "$nameText\n$cardText"
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

        isGameFinished = true

        val oldTeam0Level = game.team0Level
        val oldTeam1Level = game.team1Level

        val winner = game.getWinner()
        if (winner == null) return

        savedTeam0Level = game.team0Level
        savedTeam1Level = game.team1Level

        val sortedPlayers = room.players.sortedBy { it.cards.size }
        val winnerRank = sortedPlayers.indexOfFirst { it.id == winner.id } + 1
        val teammate = sortedPlayers.find { it.team == winner.team && it.id != winner.id }
        val teammateRank = if (teammate != null) sortedPlayers.indexOfFirst { it.id == teammate.id } + 1 else 4

        val winnerTeam = winner.team
        val winnerOldLevel = if (winnerTeam == 0) oldTeam0Level else oldTeam1Level
        val winnerNewLevel = if (winnerTeam == 0) savedTeam0Level else savedTeam1Level
        val isOverA = winnerOldLevel == 14 && teammateRank <= 3
        val needRetryA = winnerOldLevel == 14 && teammateRank == 4

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
                    restartGameWithLevel(savedTeam0Level, savedTeam1Level)
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun getRankText(rank: Int): String {
        return when (rank) {
            1 -> "头游"
            2 -> "二游"
            3 -> "三游"
            4 -> "末游"
            else -> "未知"
        }
    }

    private fun restartGameWithLevel(team0Level: Int, team1Level: Int) {
        handler.removeCallbacksAndMessages(null)

        forceClearAllSelection("restartGameWithLevel")

        savedTeam0Level = team0Level
        savedTeam1Level = team1Level

        val touYouPosition = guandanGame?.lastTouYouPosition ?: 0
        println("重新开始游戏，头游位置=$touYouPosition")

        guandanGame?.resetUpgradeFlag()

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
        checkAndStartAIChain()
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.d("GameActivity", "onPause")
        handler.removeCallbacksAndMessages(null)
        isAIChainRunning = false
    }
}