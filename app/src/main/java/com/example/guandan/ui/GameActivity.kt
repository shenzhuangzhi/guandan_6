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

class GameActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGameBinding
    private var guandanGame: GuandanGame? = null
    private var gameRoom: GameRoom? = null
    private lateinit var cardAdapter: CardAdapter
    private val selectedCards = mutableListOf<Card>()
    private var humanPlayer: Player? = null

    private val handler = Handler(Looper.getMainLooper())
    private val AI_PLAY_DELAY = 1000L  // 【修改】改为1秒

    // 记录每个玩家上轮出的牌
    private val playerLastCards = mutableMapOf<String, List<Card>>()
    // 记录每个玩家是否出过牌（用于首次判断）
    private val playerHasPlayed = mutableMapOf<String, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityGameBinding.inflate(layoutInflater)
            setContentView(binding.root)

            val gameModeOrdinal = intent.getIntExtra("GAME_MODE_ORDINAL", 0)
            val gameMode = GameMode.values().getOrNull(gameModeOrdinal) ?: GameMode.SINGLE_PLAYER

            guandanGame = GuandanGame()
            gameRoom = guandanGame?.initGame(gameMode)
            humanPlayer = gameRoom?.players?.firstOrNull { !it.isAI }

            if (gameRoom == null || humanPlayer == null) {
                Toast.makeText(this, "游戏初始化失败", Toast.LENGTH_SHORT).show()
                finish()
                return
            }

            // 初始化记录
            gameRoom?.players?.forEach { player ->
                playerLastCards[player.id] = emptyList()
                playerHasPlayed[player.id] = false
            }

            initCardRecyclerView()
            updateAllUI()

            binding.btnPlayCards.setOnClickListener { playSelectedCards() }
            binding.btnPass.setOnClickListener { passTurn() }

            if (gameRoom?.players?.find { it.isCurrentTurn }?.isAI == true) {
                startAIAutoPlayChain()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "启动失败：${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initCardRecyclerView() {
        val playerCards = humanPlayer?.cards?.toMutableList() ?: mutableListOf()
        cardAdapter = CardAdapter(playerCards) { card ->
            if (card.isSelected) selectedCards.add(card)
            else selectedCards.remove(card)
        }

        // 水平排列，不反向
        binding.rvCards.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCards.adapter = cardAdapter

        // 允许子视图超出边界显示（为了重叠效果）
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
            // 记录玩家出牌
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
            startAIAutoPlayChain()
        } else {
            Toast.makeText(this, "出牌不合法", Toast.LENGTH_SHORT).show()
        }
    }

    private fun passTurn() {
        val player = humanPlayer ?: return
        humanPlayer?.id?.let { guandanGame?.passTurn(it) }

        // 记录过牌
        playerLastCards[player.id] = emptyList()
        playerHasPlayed[player.id] = true

        updateAllUI()
        startAIAutoPlayChain()
    }

    private fun startAIAutoPlayChain() {
        val room = gameRoom ?: return
        val game = guandanGame ?: return

        val curr = room.players.find { it.isCurrentTurn } ?: return
        if (!curr.isAI) return
        if (game.isGameOver()) return

        val playedCard = game.autoPlayOneCard(curr)
        val currentLastCards = game.lastPlayedCardsPublic
        val aiPlayedName = game.lastPlayerNamePublic

        val actuallyPlayed = playedCard != null && currentLastCards.isNotEmpty() && aiPlayedName == curr.name

        // 记录AI出牌
        playerLastCards[curr.id] = if (actuallyPlayed) currentLastCards.toList() else emptyList()
        playerHasPlayed[curr.id] = true

        // 【删除】取消Toast文字提示
        // if (actuallyPlayed) {
        //     Toast.makeText(this, "${curr.name} 出了：${getCardDesc(currentLastCards)}", Toast.LENGTH_SHORT).show()
        // } else {
        //     Toast.makeText(this, "${curr.name} 选择过牌", Toast.LENGTH_SHORT).show()
        // }

        updateAllUI()

        if (game.isGameOver()) {
            gameOver()
            return
        }

        handler.postDelayed({
            startAIAutoPlayChain()
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
    }

    private fun updatePlayerInfo() {
        val room = gameRoom ?: return
        val curr = room.players.find { it.isCurrentTurn }

        // 顶部当前回合
        binding.tvCurrentPlayer.text = "当前出牌：${curr?.name ?: "无"}"

        // 更新四个角玩家信息
        room.players.forEach { player ->
            val nameText = "${player.name}\n剩${player.cards.size}张"
            when {
                player.isAI && room.players.indexOf(player) == 1 -> { // AI1 (右上)
                    binding.tvAi1.text = nameText
                }
                player.isAI && room.players.indexOf(player) == 2 -> { // AI2 (左上)
                    binding.tvAi2.text = nameText
                }
                player.isAI && room.players.indexOf(player) == 3 -> { // AI3 (左下)
                    binding.tvAi3.text = nameText
                }
                !player.isAI -> { // 玩家 (右下)
                    binding.tvPlayer.text = nameText
                }
            }
        }
    }

    // 显示上轮出牌（首次前隐藏，过牌显示文字，出牌显示图片）
    private fun updateLastPlayedDisplay() {
        val room = gameRoom ?: return

        // AI1（右上）
        val ai1Id = room.players.getOrNull(1)?.id
        displayPlayerLastPlay(ai1Id, binding.layoutLastAi1, binding.tvPassAi1)

        // AI2（左上）
        val ai2Id = room.players.getOrNull(2)?.id
        displayPlayerLastPlay(ai2Id, binding.layoutLastAi2, binding.tvPassAi2)

        // AI3（左下）
        val ai3Id = room.players.getOrNull(3)?.id
        displayPlayerLastPlay(ai3Id, binding.layoutLastAi3, binding.tvPassAi3)

        // 玩家（右下）
        val playerId = humanPlayer?.id
        displayPlayerLastPlay(playerId, binding.layoutLastPlayer, binding.tvPassPlayer)
    }

    // 显示单个玩家的上轮出牌
    private fun displayPlayerLastPlay(playerId: String?, layout: LinearLayout, passText: android.widget.TextView) {
        if (playerId == null) return

        val hasPlayed = playerHasPlayed[playerId] ?: false
        val cards = playerLastCards[playerId] ?: emptyList()

        // 先清空并隐藏所有
        layout.removeAllViews()
        layout.visibility = View.GONE
        passText.visibility = View.GONE

        if (!hasPlayed) {
            // 首次出牌前，全部隐藏（已处理）
            return
        }

        if (cards.isEmpty()) {
            // 过牌，只显示"过牌"文字
            passText.visibility = View.VISIBLE
        } else {
            // 有出牌，显示图片
            layout.visibility = View.VISIBLE
            // 强制透明背景
            layout.setBackgroundColor(0x00000000)
            displayCardsInLayout(layout, cards)
        }
    }

    // 在指定布局中显示牌的图片
    private fun displayCardsInLayout(layout: LinearLayout, cards: List<Card>) {
        layout.removeAllViews()

        // 强制布局背景透明
        layout.setBackgroundColor(0x00000000)

        // dp转px（尺寸为手牌的2/3：47dp x 67dp）
        val density = resources.displayMetrics.density
        val cardWidth = (47 * density).toInt()
        val cardHeight = (67 * density).toInt()

        // 添加每张牌的图片
        cards.forEach { card ->
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(cardWidth, cardHeight).apply {
                    marginStart = (2 * density).toInt()
                    marginEnd = (2 * density).toInt()
                }
                scaleType = ImageView.ScaleType.FIT_XY
                // 设置透明背景
                setBackgroundColor(0x00000000)

                // 加载牌图片
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
    }
}