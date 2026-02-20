package com.example.guandan.logic

import com.example.guandan.model.Card
import com.example.guandan.model.CardRank
import com.example.guandan.model.CardSuit
import com.example.guandan.model.GameMode
import com.example.guandan.model.GameRoom
import com.example.guandan.model.Player
import com.example.guandan.utils.CardComparator
import java.util.UUID

class GuandanGame {
    lateinit var gameRoom: GameRoom
    private var lastPlayedCards: List<Card> = emptyList()
    private var lastPlayerId: String = ""
    private var lastPlayerName: String = ""
    private var passCount = 0
    var aiCurrentPlayedCards: List<Card> = emptyList()

    val lastPlayedCardsPublic: List<Card> get() = lastPlayedCards
    val lastPlayerNamePublic: String get() = lastPlayerName

    private val cardComparator = CardComparator()

    enum class CardType {
        SINGLE, PAIR, TRIPLE, BOMB,
        THREE_WITH_TWO, STRAIGHT, STRAIGHT_FLUSH,
        PLANK, STEEL_PLATE
    }

    fun initGame(gameMode: GameMode): GameRoom {
        val roomId = UUID.randomUUID().toString().substring(0, 8)
        val room = GameRoom(
            roomId = roomId,
            gameMode = gameMode,
            players = mutableListOf()
        )
        addPlayersByMode(room, gameMode)
        val allCards = createAllCards()
        shuffleCards(allCards)
        dealCards(allCards, room)

        if (room.players.isNotEmpty()) {
            room.currentPlayerId = room.players[0].id
            room.players[0].isCurrentTurn = true
        }
        room.isStarted = true
        this.gameRoom = room
        return room
    }

    private fun addPlayersByMode(room: GameRoom, gameMode: GameMode) {
        val humanPlayer = Player(
            id = UUID.randomUUID().toString(),
            name = "玩家1",
            isAI = false
        )
        room.players.add(humanPlayer)

        val aiCount = when (gameMode) {
            GameMode.SINGLE_PLAYER -> 3
            GameMode.TWO_PLAYER_VS_AI -> 2
            GameMode.THREE_PLAYER_VS_AI -> 1
            GameMode.ONLINE_4_PLAYERS -> 0
        }

        for (i in 1..aiCount) {
            val aiPlayer = Player(
                id = UUID.randomUUID().toString(),
                name = "AI$i",
                isAI = true
            )
            room.players.add(aiPlayer)
        }
    }

    private fun createAllCards(): MutableList<Card> {
        val cards = mutableListOf<Card>()
        repeat(2) {
            CardSuit.values().forEach { suit ->
                if (suit != CardSuit.JOKER) {
                    CardRank.values().forEach { rank ->
                        if (!rank.isJoker()) {
                            cards.add(Card(suit, rank))
                        }
                    }
                }
            }
            cards.add(Card(CardSuit.JOKER, CardRank.JOKER_SMALL))
            cards.add(Card(CardSuit.JOKER, CardRank.JOKER_BIG))
        }
        return cards
    }

    private fun shuffleCards(cards: MutableList<Card>) {
        cards.shuffle()
    }

    private fun dealCards(allCards: MutableList<Card>, room: GameRoom) {
        val playerCount = room.players.size
        if (playerCount == 0 || allCards.isEmpty()) return

        val totalCards = allCards.size
        val base = totalCards / playerCount
        var rem = totalCards % playerCount

        for (i in room.players.indices) {
            val start = i * base
            val end = start + base + if (rem > 0) 1 else 0
            if (rem > 0) rem--
            val safeEnd = if (end > allCards.size) allCards.size else end
            val list = allCards.subList(start, safeEnd).toMutableList()
            list.sortWith(cardComparator)
            room.players[i].cards = list
        }
    }

    fun playCards(playerId: String, selectedCards: List<Card>): Boolean {
        if (!this::gameRoom.isInitialized || gameRoom.players.isEmpty()) return false
        val currentPlayer = gameRoom.players.find { it.id == playerId } ?: return false
        if (!currentPlayer.isCurrentTurn) return false
        if (selectedCards.isEmpty() || !currentPlayer.cards.containsAll(selectedCards)) return false

        val cardType = getCardType(selectedCards)
        if (cardType == null) return false

        if (lastPlayedCards.isNotEmpty()) {
            if (!canBeatLastCards(selectedCards, cardType)) return false
        }

        currentPlayer.cards.removeAll(selectedCards)
        lastPlayedCards = selectedCards.toList()
        lastPlayerId = playerId
        lastPlayerName = currentPlayer.name
        passCount = 0

        switchToNextPlayer()
        return true
    }

    private fun getCardType(cards: List<Card>): CardType? {
        return when (cards.size) {
            1 -> CardType.SINGLE
            2 -> if (isAllSameRank(cards)) CardType.PAIR else null
            3 -> if (isAllSameRank(cards)) CardType.TRIPLE else null
            4 -> if (isAllSameRank(cards)) CardType.BOMB else null
            5 -> {
                when {
                    isThreeWithTwo(cards) -> CardType.THREE_WITH_TWO
                    isStraightFlush(cards) -> CardType.STRAIGHT_FLUSH
                    isStraight(cards) -> CardType.STRAIGHT
                    isAllSameRank(cards) -> CardType.BOMB
                    else -> null
                }
            }

            6 -> {
                when {
                    isPlank(cards) -> CardType.PLANK      // 3对相连
                    isSteelPlate(cards) -> CardType.STEEL_PLATE  // 2个顺序三张
                    isAllSameRank(cards) -> CardType.BOMB
                    else -> null
                }
            }

            in 7..8 -> {
                when {
                    isAllSameRank(cards) -> CardType.BOMB
                    else -> null
                }
            }

            else -> null
        }
    }

    private fun isAllSameRank(cards: List<Card>): Boolean {
        if (cards.isEmpty()) return false
        val firstRank = cards[0].rank
        return cards.all { it.rank == firstRank }
    }

    private fun isThreeWithTwo(cards: List<Card>): Boolean {
        if (cards.size != 5) return false
        val rankGroups = cards.groupBy { it.rank }
        return rankGroups.size == 2 && rankGroups.values.any { it.size == 3 } && rankGroups.values.any { it.size == 2 }
    }

    private fun isStraight(cards: List<Card>): Boolean {
        if (cards.size != 5) return false
        if (cards.any { it.rank.value >= 15 }) return false
        val sortedValues = cards.map { it.rank.value }.sorted()
        for (i in 1 until sortedValues.size) {
            if (sortedValues[i] != sortedValues[i - 1] + 1) return false
        }
        return true
    }

    private fun isStraightFlush(cards: List<Card>): Boolean {
        if (cards.size != 5) return false
        if (cards.any { it.rank.value >= 15 }) return false
        val sortedValues = cards.map { it.rank.value }.sorted()
        for (i in 1 until sortedValues.size) {
            if (sortedValues[i] != sortedValues[i - 1] + 1) return false
        }
        val firstSuit = cards[0].suit
        return cards.all { it.suit == firstSuit }
    }

    // 木板：只能是3对相连（6张）
    private fun isPlank(cards: List<Card>): Boolean {
        if (cards.size != 6) return false
        // 按点数分组，必须是3组，每组2张
        val rankGroups = cards.groupBy { it.rank }.toList().sortedBy { it.first.value }
        if (rankGroups.size != 3) return false
        if (rankGroups.any { it.second.size != 2 }) return false
        // 检查是否连续
        for (i in 1 until rankGroups.size) {
            if (rankGroups[i].first.value != rankGroups[i - 1].first.value + 1) return false
        }
        return true
    }

    // 钢板：只能是2个顺序的三张（6张）
    private fun isSteelPlate(cards: List<Card>): Boolean {
        if (cards.size != 6) return false
        // 按点数分组，必须是2组，每组3张
        val rankGroups = cards.groupBy { it.rank }.toList().sortedBy { it.first.value }
        if (rankGroups.size != 2) return false
        if (rankGroups.any { it.second.size != 3 }) return false
        // 检查是否连续
        return rankGroups[1].first.value == rankGroups[0].first.value + 1
    }

    private fun canBeatLastCards(cards: List<Card>, currentType: CardType): Boolean {
        val lastType = getCardType(lastPlayedCards) ?: return false
        if (currentType != lastType) {
            if (currentType == CardType.STRAIGHT_FLUSH) {
                if (lastType == CardType.BOMB) return compareStraightFlushWithBomb(
                    cards,
                    lastPlayedCards
                )
                return true
            }
            if (currentType == CardType.BOMB) {
                if (lastType == CardType.STRAIGHT_FLUSH) return compareBombWithStraightFlush(
                    cards,
                    lastPlayedCards
                )
                return true
            }
            return false
        }
        return when (currentType) {
            CardType.SINGLE -> cards[0].rank.value > lastPlayedCards[0].rank.value
            CardType.PAIR -> cards[0].rank.value > lastPlayedCards[0].rank.value
            CardType.TRIPLE -> cards[0].rank.value > lastPlayedCards[0].rank.value
            CardType.BOMB -> compareBomb(cards, lastPlayedCards)
            CardType.THREE_WITH_TWO -> cards[0].rank.value > lastPlayedCards[0].rank.value
            CardType.STRAIGHT, CardType.STRAIGHT_FLUSH -> cards.maxOf { it.rank.value } > lastPlayedCards.maxOf { it.rank.value }
            CardType.PLANK -> cards.maxOf { it.rank.value } > lastPlayedCards.maxOf { it.rank.value }
            CardType.STEEL_PLATE -> compareSteelPlate(cards, lastPlayedCards)
        }
    }

    private fun compareBomb(current: List<Card>, last: List<Card>): Boolean {
        if (current.size != last.size) return current.size > last.size
        return current[0].rank.value > last[0].rank.value
    }

    private fun compareBombWithStraightFlush(current: List<Card>, last: List<Card>): Boolean {
        if (current.size != last.size) return current.size > last.size
        return false
    }

    private fun compareStraightFlushWithBomb(current: List<Card>, last: List<Card>): Boolean {
        return current.size >= last.size
    }

    private fun compareSteelPlate(current: List<Card>, last: List<Card>): Boolean {
        val currentMax = current.groupBy { it.rank }.keys.maxOf { it.value }
        val lastMax = last.groupBy { it.rank }.keys.maxOf { it.value }
        return currentMax > lastMax
    }

    // 【核心修复】AI自动出牌 - 优化返回值和状态处理
    fun autoPlayOneCard(aiPlayer: Player): Card? {
        if (!this::gameRoom.isInitialized || !aiPlayer.isCurrentTurn || aiPlayer.cards.isEmpty()) {
            // 状态异常时直接pass，但确保回合切换
            if (aiPlayer.isCurrentTurn) {
                passTurn(aiPlayer.id)
            }
            return null
        }

        val cards = aiPlayer.cards
        var playedCards: List<Card> = emptyList()

        if (lastPlayedCards.isNotEmpty()) {
            val lastType = getCardType(lastPlayedCards) ?: return null

            playedCards = when (lastType) {
                CardType.SINGLE -> findMinSingleToBeat(cards, lastPlayedCards[0])
                CardType.PAIR -> findMinPairToBeat(cards, lastPlayedCards[0].rank)
                CardType.TRIPLE -> findMinTripleToBeat(cards, lastPlayedCards[0].rank)
                CardType.BOMB -> findMinBombToBeat(
                    cards,
                    lastPlayedCards[0].rank,
                    lastPlayedCards.size
                )

                CardType.THREE_WITH_TWO -> findMinThreeWithTwoToBeat(cards, lastPlayedCards)
                CardType.STRAIGHT -> findMinStraightToBeat(cards, lastPlayedCards)
                CardType.PLANK -> findMinPlankToBeat(cards, lastPlayedCards)
                CardType.STEEL_PLATE -> findMinSteelPlateToBeat(cards, lastPlayedCards)
                else -> emptyList()
            }

            // 无同牌型，找炸弹通吃
            if (playedCards.isEmpty()) {
                playedCards = findAnyBomb(cards)
            }
        } else {
            // 首轮：优先出复杂牌型
            playedCards = when {
                findAnySteelPlate(cards).isNotEmpty() -> findAnySteelPlate(cards)
                findAnyPlank(cards).isNotEmpty() -> findAnyPlank(cards)
                findAnyStraight(cards).isNotEmpty() -> findAnyStraight(cards)
                findAnyThreeWithTwo(cards).isNotEmpty() -> findAnyThreeWithTwo(cards)
                findAnyTriple(cards).isNotEmpty() -> findAnyTriple(cards)
                findAnyPair(cards).isNotEmpty() -> findAnyPair(cards)
                else -> {
                    val single = cards.minWithOrNull(cardComparator)
                    if (single != null) listOf(single) else emptyList()
                }
            }
        }

        return if (playedCards.isNotEmpty()) {
            val success = playCards(aiPlayer.id, playedCards)
            if (success) {
                playedCards[0]
            } else {
                // 出牌失败（异常情况），强制pass
                passTurn(aiPlayer.id)
                null
            }
        } else {
            // 无牌可出，pass
            passTurn(aiPlayer.id)
            null
        }
    }

    // ========== AI找牌方法 - 跟牌 ==========

    private fun findMinSingleToBeat(cards: List<Card>, target: Card): List<Card> {
        return cards.filter { it.rank.value > target.rank.value }
            .minWithOrNull(cardComparator)?.let { listOf(it) } ?: emptyList()
    }

    private fun findMinPairToBeat(cards: List<Card>, targetRank: CardRank): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val validPairs = rankMap.filter {
            it.key.value > targetRank.value && it.value.size >= 2
        }.keys.sortedBy { it.value }
        return if (validPairs.isNotEmpty()) rankMap[validPairs.first()]!!.take(2) else emptyList()
    }

    private fun findMinTripleToBeat(cards: List<Card>, targetRank: CardRank): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val validTriples = rankMap.filter {
            it.key.value > targetRank.value && it.value.size >= 3
        }.keys.sortedBy { it.value }
        return if (validTriples.isNotEmpty()) rankMap[validTriples.first()]!!.take(3) else emptyList()
    }

    private fun findMinBombToBeat(
        cards: List<Card>,
        targetRank: CardRank,
        targetCount: Int
    ): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val sameCount = rankMap.filter {
            it.value.size >= targetCount && it.key.value > targetRank.value
        }.keys.sortedBy { it.value }
        if (sameCount.isNotEmpty()) {
            return rankMap[sameCount.first()]!!.take(targetCount)
        }
        val biggerCount = rankMap.filter { it.value.size > targetCount }
            .keys.sortedBy { it.value }
        return if (biggerCount.isNotEmpty()) {
            val rank = biggerCount.first()
            rankMap[rank]!!.take(minOf(rankMap[rank]!!.size, 8))
        } else emptyList()
    }

    private fun findMinThreeWithTwoToBeat(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val lastThreeRank = lastCards.groupBy { it.rank }.filter { it.value.size == 3 }.keys.first()
        val rankMap = cards.groupBy { it.rank }

        val validTriples = rankMap.filter {
            it.key.value > lastThreeRank.value && it.value.size >= 3
        }.keys.sortedBy { it.value }

        if (validTriples.isEmpty()) return emptyList()

        val threeRank = validTriples.first()
        val threeCards = rankMap[threeRank]!!.take(3)

        val validPairs = rankMap.filter {
            it.key != threeRank && it.value.size >= 2
        }.keys.sortedBy { it.value }

        if (validPairs.isEmpty()) return emptyList()

        val pairCards = rankMap[validPairs.first()]!!.take(2)
        return threeCards + pairCards
    }

    private fun findMinStraightToBeat(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val lastMax = lastCards.maxOf { it.rank.value }
        val size = lastCards.size
        val validCards = cards.filter { it.rank.value < 15 && it.rank.value > lastMax - size + 1 }
            .groupBy { it.rank }.map { it.value.first() }.sortedBy { it.rank.value }

        if (validCards.size < size) return emptyList()

        for (i in 0..validCards.size - size) {
            val straight = validCards.subList(i, i + size)
            if (isStraight(straight) && straight.maxOf { it.rank.value } > lastMax) {
                return straight.flatMap { card ->
                    cards.filter { it.rank == card.rank }.take(1)
                }
            }
        }
        return emptyList()
    }

    // 找木板（3对相连）
    private fun findMinPlankToBeat(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val lastMax = lastCards.maxOf { it.rank.value }
        val rankMap = cards.filter { it.rank.value < 15 }.groupBy { it.rank }

        // 找有对子的点数，且大于lastMax-2（因为3对连续）
        val validRanks = rankMap.filter {
            it.value.size >= 2 && it.key.value > lastMax - 2
        }.keys.sortedBy { it.value }

        if (validRanks.size < 3) return emptyList()

        // 找3个连续的对子
        for (i in 0..validRanks.size - 3) {
            val ranks = validRanks.subList(i, i + 3)
            if (ranks[2].value > lastMax &&
                ranks[1].value == ranks[0].value + 1 &&
                ranks[2].value == ranks[1].value + 1
            ) {
                return ranks.flatMap { rankMap[it]!!.take(2) }
            }
        }
        return emptyList()
    }

    // 找钢板（2个顺序三张）
    private fun findMinSteelPlateToBeat(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val lastMax = lastCards.groupBy { it.rank }.keys.maxOf { it.value }
        val rankMap = cards.filter { it.rank.value < 15 }.groupBy { it.rank }

        // 找有三张的点数，且大于lastMax-1
        val validRanks = rankMap.filter {
            it.value.size >= 3 && it.key.value > lastMax - 1
        }.keys.sortedBy { it.value }

        if (validRanks.size < 2) return emptyList()

        // 找2个连续的三张
        for (i in 0..validRanks.size - 2) {
            val ranks = validRanks.subList(i, i + 2)
            if (ranks[1].value > lastMax && ranks[1].value == ranks[0].value + 1) {
                return ranks.flatMap { rankMap[it]!!.take(3) }
            }
        }
        return emptyList()
    }

    // ========== AI找牌方法 - 主动出牌 ==========

    // 找钢板（2个顺序三张，6张）
    private fun findAnySteelPlate(cards: List<Card>): List<Card> {
        val rankMap = cards.filter { it.rank.value < 15 }.groupBy { it.rank }
        val tripleRanks = rankMap.filter { it.value.size >= 3 }.keys.sortedBy { it.value }

        if (tripleRanks.size < 2) return emptyList()

        // 找最小的2个连续三张
        for (i in 0..tripleRanks.size - 2) {
            val ranks = tripleRanks.subList(i, i + 2)
            if (ranks[1].value == ranks[0].value + 1) {
                return ranks.flatMap { rankMap[it]!!.take(3) }
            }
        }
        return emptyList()
    }

    // 找木板（3对相连，6张）
    private fun findAnyPlank(cards: List<Card>): List<Card> {
        val rankMap = cards.filter { it.rank.value < 15 }.groupBy { it.rank }
        val pairRanks = rankMap.filter { it.value.size >= 2 }.keys.sortedBy { it.value }

        if (pairRanks.size < 3) return emptyList()

        // 找最小的3个连续对子
        for (i in 0..pairRanks.size - 3) {
            val ranks = pairRanks.subList(i, i + 3)
            if (ranks[1].value == ranks[0].value + 1 && ranks[2].value == ranks[1].value + 1) {
                return ranks.flatMap { rankMap[it]!!.take(2) }
            }
        }
        return emptyList()
    }

    private fun findAnyStraight(cards: List<Card>): List<Card> {
        val validCards = cards.filter { it.rank.value < 15 }
            .groupBy { it.rank }.map { it.value.first() }.sortedBy { it.rank.value }

        for (size in minOf(validCards.size, 10) downTo 5) {
            for (i in 0..validCards.size - size) {
                val straight = validCards.subList(i, i + size)
                if (isStraight(straight)) {
                    return straight.flatMap { card ->
                        cards.filter { it.rank == card.rank }.take(1)
                    }
                }
            }
        }
        return emptyList()
    }

    private fun findAnyThreeWithTwo(cards: List<Card>): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val tripleRank = rankMap.filter { it.value.size >= 3 }.keys.minByOrNull { it.value }
            ?: return emptyList()
        val pairRank =
            rankMap.filter { it.key != tripleRank && it.value.size >= 2 }.keys.minByOrNull { it.value }
                ?: return emptyList()

        return rankMap[tripleRank]!!.take(3) + rankMap[pairRank]!!.take(2)
    }

    private fun findAnyTriple(cards: List<Card>): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        return rankMap.filter { it.value.size >= 3 }.values.minByOrNull { it[0].rank.value }
            ?.take(3) ?: emptyList()
    }

    private fun findAnyPair(cards: List<Card>): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        return rankMap.filter { it.value.size >= 2 }.values.minByOrNull { it[0].rank.value }
            ?.take(2) ?: emptyList()
    }

    private fun findAnyBomb(cards: List<Card>): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val bombRanks = rankMap.filter { it.value.size >= 4 }.keys.sortedBy { it.value }
        return if (bombRanks.isNotEmpty()) {
            val rank = bombRanks.first()
            rankMap[rank]!!.take(minOf(rankMap[rank]!!.size, 8))
        } else emptyList()
    }

    fun passTurn(playerId: String) {
        if (!this::gameRoom.isInitialized) return

        // 验证是否是当前玩家
        val currentPlayer = gameRoom.players.find { it.isCurrentTurn }
        if (currentPlayer?.id != playerId) {
            android.util.Log.w("GuandanGame", "passTurn: 不是当前玩家，忽略")
            return
        }

        passCount++
        if (passCount >= gameRoom.players.size - 1) {
            lastPlayedCards = emptyList()
            lastPlayerId = ""
            lastPlayerName = ""
            passCount = 0
        }
        switchToNextPlayer()
    }

    // 【修复】优化玩家切换逻辑，增加健壮性
    fun switchToNextPlayer() {
        if (!this::gameRoom.isInitialized || gameRoom.players.isEmpty()) return

        // 清除当前玩家标记
        gameRoom.players.forEach { it.isCurrentTurn = false }

        // 找到当前玩家索引
        val currIndex = gameRoom.players.indexOfFirst { it.id == gameRoom.currentPlayerId }
        val nextIndex = if (currIndex == -1) 0 else (currIndex + 1) % gameRoom.players.size

        // 设置下一个玩家
        val nextPlayer = gameRoom.players[nextIndex]
        gameRoom.currentPlayerId = nextPlayer.id
        nextPlayer.isCurrentTurn = true

        android.util.Log.d("GuandanGame", "切换到玩家: ${nextPlayer.name}, isAI=${nextPlayer.isAI}")
    }

    fun isGameOver(): Boolean {
        return if (this::gameRoom.isInitialized) gameRoom.players.any { it.cards.isEmpty() } else false
    }

    fun getWinner(): Player? {
        return if (this::gameRoom.isInitialized) gameRoom.players.find { it.cards.isEmpty() } else null
    }
}