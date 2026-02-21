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

    private var cardComparator = CardComparator(currentLevelRank)

    // 记录上一局头游的位置索引（0=玩家, 1=AI1, 2=AI2, 3=AI3）
    var lastTouYouPosition: Int = 0
        private set

    // 防止重复升级的标志
    private var hasUpgradedThisRound: Boolean = false

    // 每组玩家保存自己的等级（0队和1队分别升级）
    var team0Level: Int = 2
        internal set
    var team1Level: Int = 2
        internal set

    // 当前局固定的级牌（由头游所在队伍决定，整局不变）
    private var fixedLevelRank: CardRank = CardRank.TWO

    // 当前级牌点数（整局固定，不随出牌玩家变化）
    val currentLevelRank: CardRank get() = fixedLevelRank


    enum class CardType {
        SINGLE, PAIR, TRIPLE, BOMB,
        THREE_WITH_TWO, STRAIGHT, STRAIGHT_FLUSH,
        PLANK, STEEL_PLATE
    }

    /**
     * 获取当前局固定的级牌值
     */
    fun getFixedLevel(): Int {
        return when (fixedLevelRank) {
            CardRank.TWO -> 2
            CardRank.THREE -> 3
            CardRank.FOUR -> 4
            CardRank.FIVE -> 5
            CardRank.SIX -> 6
            CardRank.SEVEN -> 7
            CardRank.EIGHT -> 8
            CardRank.NINE -> 9
            CardRank.TEN -> 10
            CardRank.JACK -> 11
            CardRank.QUEEN -> 12
            CardRank.KING -> 13
            CardRank.ACE -> 14
            else -> 2
        }
    }

    /**
     * 设置当前局固定的级牌（由头游所在队伍决定）
     */
    private fun setFixedLevelByTeam(team: Int) {
        fixedLevelRank = if (team == 0) intToRank(team0Level) else intToRank(team1Level)
        cardComparator = CardComparator(fixedLevelRank)
        println("设置固定级牌：${team}队打${getFixedLevel()}，级牌=$fixedLevelRank")
    }

    /**
     * 将整数转换为牌点数（2=15, 3=3...A=14）
     */
    private fun intToRank(value: Int): CardRank {
        return when (value) {
            2 -> CardRank.TWO
            3 -> CardRank.THREE
            4 -> CardRank.FOUR
            5 -> CardRank.FIVE
            6 -> CardRank.SIX
            7 -> CardRank.SEVEN
            8 -> CardRank.EIGHT
            9 -> CardRank.NINE
            10 -> CardRank.TEN
            11 -> CardRank.JACK
            12 -> CardRank.QUEEN
            13 -> CardRank.KING
            14 -> CardRank.ACE
            else -> CardRank.TWO
        }
    }

    /**
     * 【新增】获取牌的有效比较值（与CardComparator一致）
     * - 2（非级牌）：2（最小）
     * - 3-A：3-14
     * - 级牌：15（比A大，比小王小）
     * - 小王：16
     * - 大王：17
     */
    private fun getEffectiveValue(rank: CardRank): Int {
        return when (rank) {
            CardRank.JOKER_SMALL -> 16
            CardRank.JOKER_BIG -> 17
            CardRank.TWO -> if (fixedLevelRank == CardRank.TWO) 15 else 2
            else -> if (rank == fixedLevelRank) 15 else rank.value
        }
    }

    /**
     * 【新增】判断是否是逢人配（红桃级牌）
     */
    private fun isFengRenPei(card: Card): Boolean {
        return card.suit == CardSuit.HEART && card.rank == fixedLevelRank
    }

    /**
     * 【新增】从手牌中分离出逢人配和普通牌
     */
    private fun separateFengRenPei(cards: List<Card>): Pair<List<Card>, List<Card>> {
        val fengRenPeiList = cards.filter { isFengRenPei(it) }
        val normalCards = cards.filter { !isFengRenPei(it) }
        return Pair(fengRenPeiList, normalCards)
    }

    /**
     * 获取下一个级牌值（用于升级）
     */
    fun getNextLevel(current: Int, upgradeLevels: Int): Int {
        val levels = listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)
        val currentIndex = levels.indexOf(current)
        if (currentIndex == -1) return 2
        val nextIndex = (currentIndex + upgradeLevels).coerceAtMost(levels.size - 1)
        return levels[nextIndex]
    }

    /**
     * 设置两队等级（用于重新开牌时保留级牌）
     */
    fun setTeamLevels(team0: Int, team1: Int) {
        if (team0 in 2..14) {
            team0Level = team0
        }
        if (team1 in 2..14) {
            team1Level = team1
        }
        println("设置两队等级：0队=$team0Level, 1队=$team1Level")
    }

    /**
     * 重置游戏状态（用于重新开牌）
     */
    fun resetGameState() {
        lastPlayedCards = emptyList()
        lastPlayerId = ""
        lastPlayerName = ""
        passCount = 0
        aiCurrentPlayedCards = emptyList()
    }

    /**
     * 初始化游戏 - 使用位置索引确定谁先出牌
     */
    fun initGame(gameMode: GameMode, firstPlayerPosition: Int = 0): GameRoom {
        val roomId = UUID.randomUUID().toString().substring(0, 8)
        val room = GameRoom(
            roomId = roomId,
            gameMode = gameMode,
            players = mutableListOf()
        )
        addPlayersByMode(room, gameMode)
        val allCards = createAllCards()
        shuffleCards(allCards)

        // 先赋值 gameRoom
        this.gameRoom = room

        // 使用位置索引确定谁先出牌（0=玩家, 1=AI1, 2=AI2, 3=AI3）
        val startPosition = firstPlayerPosition.coerceIn(0, room.players.size - 1)
        val startPlayer = room.players.getOrNull(startPosition)

        if (startPlayer != null) {
            room.currentPlayerId = startPlayer.id
            startPlayer.isCurrentTurn = true
            println("本局先出牌：${startPlayer.name} (位置=$startPosition, 队伍=${startPlayer.team})")
        } else if (room.players.isNotEmpty()) {
            room.currentPlayerId = room.players[0].id
            room.players[0].isCurrentTurn = true
            println("本局先出牌：${room.players[0].name} (默认)")
        }

        // 设置固定级牌（由先出牌的玩家所在队伍决定，整局不变）
        val startPlayerTeam = room.players.find { it.isCurrentTurn }?.team ?: 0
        setFixedLevelByTeam(startPlayerTeam)

        dealCards(allCards, room)

        room.isStarted = true
        return room
    }

    /**
     * 添加玩家时分配队伍（0队和1队，对家同队）
     */
    private fun addPlayersByMode(room: GameRoom, gameMode: GameMode) {
        val humanPlayer = Player(
            id = UUID.randomUUID().toString(),
            name = "玩家1",
            isAI = false,
            team = 0
        )
        room.players.add(humanPlayer)

        val aiCount = when (gameMode) {
            GameMode.SINGLE_PLAYER -> 3
            GameMode.TWO_PLAYER_VS_AI -> 2
            GameMode.THREE_PLAYER_VS_AI -> 1
            GameMode.ONLINE_4_PLAYERS -> 0
        }

        for (i in 1..aiCount) {
            val team = if (i == 2) 0 else 1
            val aiPlayer = Player(
                id = UUID.randomUUID().toString(),
                name = "AI$i",
                isAI = true,
                team = team
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

    /**
     * 发牌时使用固定级牌
     */
    private fun dealCards(allCards: MutableList<Card>, room: GameRoom) {
        val playerCount = room.players.size
        if (playerCount == 0 || allCards.isEmpty()) return

        val totalCards = allCards.size
        val base = totalCards / playerCount
        var rem = totalCards % playerCount

        val comparatorWithLevel = CardComparator(fixedLevelRank)

        for (i in room.players.indices) {
            val start = i * base
            val end = start + base + if (rem > 0) 1 else 0
            if (rem > 0) rem--
            val safeEnd = if (end > allCards.size) allCards.size else end
            val list = allCards.subList(start, safeEnd).toMutableList()
            list.sortWith(comparatorWithLevel)
            room.players[i].cards = list
        }
    }

    /**
     * 【核心修改】出牌时支持逢人配
     */
    fun playCards(playerId: String, selectedCards: List<Card>): Boolean {
        if (!this::gameRoom.isInitialized || gameRoom.players.isEmpty()) return false
        val currentPlayer = gameRoom.players.find { it.id == playerId } ?: return false
        if (!currentPlayer.isCurrentTurn) return false
        if (selectedCards.isEmpty() || !currentPlayer.cards.containsAll(selectedCards)) return false

        // 【修改】使用支持逢人配的牌型判断
        val cardType = getCardTypeWithFengRenPei(selectedCards)
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

    /**
     * 【核心新增】支持逢人配的牌型判断
     */
    private fun getCardTypeWithFengRenPei(cards: List<Card>): CardType? {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val fengRenPeiCount = fengRenPeiList.size

        // 全是逢人配：只能当单张出（或根据规则决定）
        if (normalCards.isEmpty()) {
            return if (fengRenPeiCount == 1) CardType.SINGLE else null
        }

        // 没有逢人配：用原来的判断
        if (fengRenPeiCount == 0) {
            return getCardTypeNormal(cards)
        }

        val totalSize = cards.size

        // 尝试各种牌型组合
        return when (totalSize) {
            1 -> CardType.SINGLE
            2 -> {
                // 对子：1张逢人配+1张普通牌，或2张相同普通牌
                if (fengRenPeiCount == 1 && normalCards.size == 1) {
                    CardType.PAIR
                } else if (isAllSameRankNormal(normalCards) && fengRenPeiCount == 0) {
                    CardType.PAIR
                } else null
            }
            3 -> {
                // 三张：2配+1牌 或 1配+2张相同牌 或 3张相同
                if (fengRenPeiCount == 2) CardType.TRIPLE
                else if (fengRenPeiCount == 1 && isAllSameRankNormal(normalCards)) CardType.TRIPLE
                else if (isAllSameRankNormal(normalCards)) CardType.TRIPLE
                else null
            }
            4 -> {
                // 炸弹：3配+1牌 或 2配+2张相同 或 1配+3张相同 或 4张相同
                if (fengRenPeiCount >= 1) CardType.BOMB
                else if (isAllSameRankNormal(normalCards)) CardType.BOMB
                else null
            }
            5 -> {
                when {
                    // 三带二判断
                    isThreeWithTwoWithFengRenPei(normalCards, fengRenPeiCount) -> CardType.THREE_WITH_TWO
                    // 同花顺判断（不能有配）
                    fengRenPeiCount == 0 && isStraightFlush(cards) -> CardType.STRAIGHT_FLUSH
                    // 顺子判断（配可以填补）
                    isStraightWithFengRenPei(normalCards, fengRenPeiCount) -> CardType.STRAIGHT
                    // 炸弹
                    fengRenPeiCount >= 1 -> CardType.BOMB
                    else -> null
                }
            }
            6 -> {
                when {
                    // 木板判断
                    isPlankWithFengRenPei(normalCards, fengRenPeiCount) -> CardType.PLANK
                    // 钢板判断
                    isSteelPlateWithFengRenPei(normalCards, fengRenPeiCount) -> CardType.STEEL_PLATE
                    // 炸弹
                    isAllSameRankWithFengRenPei(normalCards, fengRenPeiCount) -> CardType.BOMB
                    else -> null
                }
            }
            in 7..8 -> {
                // 大炸弹
                if (isAllSameRankWithFengRenPei(normalCards, fengRenPeiCount)) CardType.BOMB
                else null
            }
            else -> null
        }
    }

    /**
     * 【新增】判断三带二（支持逢人配）
     */
    private fun isThreeWithTwoWithFengRenPei(normalCards: List<Card>, fengRenPeiCount: Int): Boolean {
        if (normalCards.size + fengRenPeiCount != 5) return false

        val rankGroups = normalCards.groupBy { it.rank }

        // 情况1: 3张相同 + 1对（无配）
        if (fengRenPeiCount == 0) {
            return rankGroups.size == 2 && rankGroups.values.any { it.size == 3 } && rankGroups.values.any { it.size == 2 }
        }

        // 情况2: 有配的情况
        // 2配 + 3张相同 = 三带二
        if (fengRenPeiCount == 2 && rankGroups.size == 1 && rankGroups.values.first().size == 3) return true

        // 1配 + 2张相同 + 2张相同 = 三带二（配凑成3张）
        if (fengRenPeiCount == 1 && rankGroups.size == 2 && rankGroups.values.all { it.size == 2 }) return true

        // 1配 + 3张相同 + 1张 = 需要再1配才能成对，不满足

        return false
    }

    /**
     * 【新增】判断顺子（支持逢人配填补空缺）
     */
    private fun isStraightWithFengRenPei(normalCards: List<Card>, fengRenPeiCount: Int): Boolean {
        if (normalCards.size + fengRenPeiCount != 5) return false
        // 顺子不能包含2和王（即使是普通牌）
        if (normalCards.any { it.rank == CardRank.TWO || it.rank.isJoker() }) return false

        val uniqueRanks = normalCards.map { it.rank.value }.distinct().sorted()

        // 检查是否有重复（除了可以用配填补的）
        if (uniqueRanks.size != normalCards.size) return false

        if (uniqueRanks.isEmpty()) return fengRenPeiCount == 5 // 全是配

        val minRank = uniqueRanks.first()
        val maxRank = uniqueRanks.last()
        val range = maxRank - minRank + 1

        // 需要的配数量 = 总范围 - 已有牌数
        val neededFengRenPei = range - uniqueRanks.size

        // 还需要填补两端的空缺
        val totalNeeded = neededFengRenPei + (5 - range)

        return fengRenPeiCount >= totalNeeded && fengRenPeiCount <= 5
    }

    /**
     * 【新增】判断木板（支持逢人配）
     */
    private fun isPlankWithFengRenPei(normalCards: List<Card>, fengRenPeiCount: Int): Boolean {
        if (normalCards.size + fengRenPeiCount != 6) return false
        if (normalCards.any { it.rank == CardRank.TWO || it.rank.isJoker() }) return false

        val rankGroups = normalCards.groupBy { it.rank }

        // 统计各点数数量
        val rankCounts = rankGroups.mapValues { it.value.size }.toMutableMap()

        // 需要形成3个连续点数，每个2张
        val sortedRanks = rankGroups.keys.sortedBy { it.value }

        // 简单情况：尝试用配填补
        // 2-2-2 分布（3个点数各2张）
        if (fengRenPeiCount == 0) {
            return isPlank(normalCards)
        }

        // 有配的情况较复杂，简化处理：能组成3个连续点数的对子即可
        // 实际游戏中AI会具体计算
        return false // 简化：有配时不判断木板，避免复杂性
    }

    /**
     * 【新增】判断钢板（支持逢人配）
     */
    private fun isSteelPlateWithFengRenPei(normalCards: List<Card>, fengRenPeiCount: Int): Boolean {
        if (normalCards.size + fengRenPeiCount != 6) return false
        if (normalCards.any { it.rank == CardRank.TWO || it.rank.isJoker() }) return false

        if (fengRenPeiCount == 0) {
            return isSteelPlate(normalCards)
        }

        // 有配的情况简化处理
        return false
    }

    /**
     * 【新增】判断所有牌点数相同（支持逢人配）
     */
    private fun isAllSameRankWithFengRenPei(normalCards: List<Card>, fengRenPeiCount: Int): Boolean {
        if (normalCards.isEmpty()) return true // 全是配，可以当任意炸弹
        val firstRank = normalCards[0].rank
        return normalCards.all { it.rank == firstRank }
    }

    /**
     * 【保留】普通牌型判断（无逢人配）
     */
    private fun getCardTypeNormal(cards: List<Card>): CardType? {
        return when (cards.size) {
            1 -> CardType.SINGLE
            2 -> if (isAllSameRankNormal(cards)) CardType.PAIR else null
            3 -> if (isAllSameRankNormal(cards)) CardType.TRIPLE else null
            4 -> if (isAllSameRankNormal(cards)) CardType.BOMB else null
            5 -> {
                when {
                    isThreeWithTwoNormal(cards) -> CardType.THREE_WITH_TWO
                    isStraightFlush(cards) -> CardType.STRAIGHT_FLUSH
                    isStraight(cards) -> CardType.STRAIGHT
                    isAllSameRankNormal(cards) -> CardType.BOMB
                    else -> null
                }
            }
            6 -> {
                when {
                    isPlank(cards) -> CardType.PLANK
                    isSteelPlate(cards) -> CardType.STEEL_PLATE
                    isAllSameRankNormal(cards) -> CardType.BOMB
                    else -> null
                }
            }
            in 7..8 -> {
                if (isAllSameRankNormal(cards)) CardType.BOMB else null
            }
            else -> null
        }
    }

    private fun isAllSameRankNormal(cards: List<Card>): Boolean {
        if (cards.isEmpty()) return false
        val firstRank = cards[0].rank
        return cards.all { it.rank == firstRank }
    }

    private fun isThreeWithTwoNormal(cards: List<Card>): Boolean {
        if (cards.size != 5) return false
        val rankGroups = cards.groupBy { it.rank }
        return rankGroups.size == 2 && rankGroups.values.any { it.size == 3 } && rankGroups.values.any { it.size == 2 }
    }

    private fun isStraight(cards: List<Card>): Boolean {
        if (cards.size != 5) return false
        // 【修改】顺子不能包含2和王
        if (cards.any { it.rank == CardRank.TWO || it.rank.isJoker() }) return false
        val sortedValues = cards.map { it.rank.value }.sorted()
        for (i in 1 until sortedValues.size) {
            if (sortedValues[i] != sortedValues[i - 1] + 1) return false
        }
        return true
    }

    private fun isStraightFlush(cards: List<Card>): Boolean {
        if (cards.size != 5) return false
        if (cards.any { it.rank == CardRank.TWO || it.rank.isJoker() }) return false
        val sortedValues = cards.map { it.rank.value }.sorted()
        for (i in 1 until sortedValues.size) {
            if (sortedValues[i] != sortedValues[i - 1] + 1) return false
        }
        val firstSuit = cards[0].suit
        return cards.all { it.suit == firstSuit }
    }

    private fun isPlank(cards: List<Card>): Boolean {
        if (cards.size != 6) return false
        // 【修改】木板不能包含2和王
        if (cards.any { it.rank == CardRank.TWO || it.rank.isJoker() }) return false
        val rankGroups = cards.groupBy { it.rank }.toList().sortedBy { it.first.value }
        if (rankGroups.size != 3) return false
        if (rankGroups.any { it.second.size != 2 }) return false
        for (i in 1 until rankGroups.size) {
            if (rankGroups[i].first.value != rankGroups[i - 1].first.value + 1) return false
        }
        return true
    }

    private fun isSteelPlate(cards: List<Card>): Boolean {
        if (cards.size != 6) return false
        // 【修改】钢板不能包含2和王
        if (cards.any { it.rank == CardRank.TWO || it.rank.isJoker() }) return false
        val rankGroups = cards.groupBy { it.rank }.toList().sortedBy { it.first.value }
        if (rankGroups.size != 2) return false
        if (rankGroups.any { it.second.size != 3 }) return false
        return rankGroups[1].first.value == rankGroups[0].first.value + 1
    }

    /**
     * 【修改】牌大小比较，使用有效值，并增加队友判断，支持逢人配
     */
    private fun canBeatLastCards(cards: List<Card>, currentType: CardType): Boolean {
        val lastType = getCardTypeWithFengRenPei(lastPlayedCards) ?: return false

        // 【新增】获取当前玩家和上家
        val currentPlayer = gameRoom.players.find { it.isCurrentTurn } ?: return false
        val lastPlayer = gameRoom.players.find { it.id == lastPlayerId }

        // 【新增】如果是队友，不能用炸弹压队友
        if (lastPlayer != null && lastPlayer.team == currentPlayer.team && lastPlayer.id != currentPlayer.id) {
            // 队友出的牌，不能用炸弹压
            if (currentType == CardType.BOMB) {
                return false
            }
        }

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
            CardType.SINGLE -> compareSingleWithFengRenPei(cards, lastPlayedCards)
            CardType.PAIR -> comparePairWithFengRenPei(cards, lastPlayedCards)
            CardType.TRIPLE -> compareTripleWithFengRenPei(cards, lastPlayedCards)
            CardType.BOMB -> compareBombWithFengRenPei(cards, lastPlayedCards)
            CardType.THREE_WITH_TWO -> compareThreeWithTwoWithFengRenPei(cards, lastPlayedCards)
            CardType.STRAIGHT, CardType.STRAIGHT_FLUSH -> {
                val currentMax = getMaxEffectiveValueWithFengRenPei(cards)
                val lastMax = getMaxEffectiveValueWithFengRenPei(lastPlayedCards)
                currentMax > lastMax
            }
            CardType.PLANK -> {
                val currentMax = getMaxEffectiveValueWithFengRenPei(cards)
                val lastMax = getMaxEffectiveValueWithFengRenPei(lastPlayedCards)
                currentMax > lastMax
            }
            CardType.STEEL_PLATE -> compareSteelPlateWithFengRenPei(cards, lastPlayedCards)
        }
    }

    /**
     * 【新增】获取牌组的最大有效值（逢人配按级牌算）
     */
    private fun getMaxEffectiveValueWithFengRenPei(cards: List<Card>): Int {
        return cards.maxOf {
            if (isFengRenPei(it)) getEffectiveValue(fixedLevelRank) else getEffectiveValue(it.rank)
        }
    }

    /**
     * 【新增】单张比较（支持逢人配）
     */
    private fun compareSingleWithFengRenPei(current: List<Card>, last: List<Card>): Boolean {
        val currentVal = if (isFengRenPei(current[0])) getEffectiveValue(fixedLevelRank) else getEffectiveValue(current[0].rank)
        val lastVal = if (isFengRenPei(last[0])) getEffectiveValue(fixedLevelRank) else getEffectiveValue(last[0].rank)
        return currentVal > lastVal
    }

    /**
     * 【新增】对子比较（支持逢人配）
     */
    private fun comparePairWithFengRenPei(current: List<Card>, last: List<Card>): Boolean {
        val currentVal = getPairValueWithFengRenPei(current)
        val lastVal = getPairValueWithFengRenPei(last)
        return currentVal > lastVal
    }

    private fun getPairValueWithFengRenPei(cards: List<Card>): Int {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        return if (normalCards.isNotEmpty()) {
            getEffectiveValue(normalCards[0].rank)
        } else {
            getEffectiveValue(fixedLevelRank) // 全是配
        }
    }

    /**
     * 【新增】三张比较（支持逢人配）
     */
    private fun compareTripleWithFengRenPei(current: List<Card>, last: List<Card>): Boolean {
        return getTripleValueWithFengRenPei(current) > getTripleValueWithFengRenPei(last)
    }

    private fun getTripleValueWithFengRenPei(cards: List<Card>): Int {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        return if (normalCards.isNotEmpty()) {
            getEffectiveValue(normalCards[0].rank)
        } else {
            getEffectiveValue(fixedLevelRank)
        }
    }

    /**
     * 【新增】炸弹比较（支持逢人配）
     */
    private fun compareBombWithFengRenPei(current: List<Card>, last: List<Card>): Boolean {
        if (current.size != last.size) return current.size > last.size
        return getBombValueWithFengRenPei(current) > getBombValueWithFengRenPei(last)
    }

    private fun getBombValueWithFengRenPei(cards: List<Card>): Int {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        return if (normalCards.isNotEmpty()) {
            getEffectiveValue(normalCards[0].rank)
        } else {
            getEffectiveValue(fixedLevelRank)
        }
    }

    /**
     * 【新增】三带二比较（支持逢人配）
     */
    private fun compareThreeWithTwoWithFengRenPei(current: List<Card>, last: List<Card>): Boolean {
        return getThreeWithTwoValueWithFengRenPei(current) > getThreeWithTwoValueWithFengRenPei(last)
    }

    private fun getThreeWithTwoValueWithFengRenPei(cards: List<Card>): Int {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val rankGroups = normalCards.groupBy { it.rank }

        // 找三张的部分
        val threePart = rankGroups.entries.find { it.value.size + fengRenPeiList.size >= 3 }
            ?: rankGroups.entries.maxByOrNull { it.value.size }

        return threePart?.let { getEffectiveValue(it.key) } ?: getEffectiveValue(fixedLevelRank)
    }

    /**
     * 【新增】钢板比较（支持逢人配）
     */
    private fun compareSteelPlateWithFengRenPei(current: List<Card>, last: List<Card>): Boolean {
        return getSteelPlateValueWithFengRenPei(current) > getSteelPlateValueWithFengRenPei(last)
    }

    private fun getSteelPlateValueWithFengRenPei(cards: List<Card>): Int {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val ranks = normalCards.map { it.rank }.distinct()
        return ranks.maxOfOrNull { getEffectiveValue(it) } ?: getEffectiveValue(fixedLevelRank)
    }


    /**
     * 【保留】炸弹比较（无逢人配，用于同花顺vs炸弹）
     */
    private fun compareBomb(current: List<Card>, last: List<Card>): Boolean {
        if (current.size != last.size) return current.size > last.size
        return getEffectiveValue(current[0].rank) > getEffectiveValue(last[0].rank)
    }

    private fun compareBombWithStraightFlush(current: List<Card>, last: List<Card>): Boolean {
        if (current.size != last.size) return current.size > last.size
        return false
    }

    private fun compareStraightFlushWithBomb(current: List<Card>, last: List<Card>): Boolean {
        return current.size >= last.size
    }

    /**
     * 【保留】钢板比较（无逢人配）
     */
    private fun compareSteelPlate(current: List<Card>, last: List<Card>): Boolean {
        val currentRanks = current.groupBy { it.rank }.keys
        val lastRanks = last.groupBy { it.rank }.keys
        val currentMax = currentRanks.maxOf { getEffectiveValue(it) }
        val lastMax = lastRanks.maxOf { getEffectiveValue(it) }
        return currentMax > lastMax
    }

    /**
     * 【核心修改】AI自动出牌 - 增加逢人配支持
     */
    fun autoPlayOneCard(aiPlayer: Player): Card? {
        if (!this::gameRoom.isInitialized || !aiPlayer.isCurrentTurn || aiPlayer.cards.isEmpty()) {
            if (aiPlayer.isCurrentTurn) {
                passTurn(aiPlayer.id)
            }
            return null
        }

        val cards = aiPlayer.cards
        var playedCards: List<Card> = emptyList()

        // 分离逢人配
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val hasFengRenPei = fengRenPeiList.isNotEmpty()

        // 【新增】检查是否有敌方剩余1-2张牌
        val enemyAlmostWin = gameRoom.players.any {
            it.team != aiPlayer.team && (it.cards.size == 1 || it.cards.size == 2)
        }

        // 【新增】获取敌方剩余牌数（用于判断危险程度）
        val minEnemyCards = gameRoom.players
            .filter { it.team != aiPlayer.team }
            .minOfOrNull { it.cards.size } ?: 27

        if (lastPlayedCards.isNotEmpty()) {
            val lastType = getCardTypeWithFengRenPei(lastPlayedCards) ?: return null

            val lastPlayer = gameRoom.players.find { it.id == lastPlayerId }

            val isTeammate = lastPlayer != null && lastPlayer.team == aiPlayer.team && lastPlayer.id != aiPlayer.id

            if (isTeammate && isTeammateBigPlay(lastPlayedCards, lastType)) {
                passTurn(aiPlayer.id)
                return null
            }

            playedCards = when (lastType) {
                CardType.SINGLE -> {
                    if (enemyAlmostWin) {
                        val alternative = findAnyPairWithFengRenPei(cards).takeIf { it.isNotEmpty() }
                            ?: findAnyTripleWithFengRenPei(cards).takeIf { it.isNotEmpty() }
                            ?: findAnyBombWithFengRenPei(cards).takeIf { it.isNotEmpty() }
                            ?: emptyList()
                        if (alternative.isNotEmpty()) {
                            val altType = getCardTypeWithFengRenPei(alternative)
                            if (altType != null && canBeatLastCards(alternative, altType)) {
                                alternative
                            } else {
                                findMaxSingleToBeatWithFengRenPei(cards, lastPlayedCards[0])
                            }
                        } else {
                            findMaxSingleToBeatWithFengRenPei(cards, lastPlayedCards[0])
                        }
                    } else {
                        findMinSingleToBeatWithFengRenPei(cards, lastPlayedCards[0])
                    }
                }
                CardType.PAIR -> {
                    if (enemyAlmostWin) {
                        val alternative = findAnyTripleWithFengRenPei(cards).takeIf { it.isNotEmpty() }
                            ?: findAnyBombWithFengRenPei(cards).takeIf { it.isNotEmpty() }
                            ?: emptyList()
                        if (alternative.isNotEmpty()) {
                            val altType = getCardTypeWithFengRenPei(alternative)
                            if (altType != null && canBeatLastCards(alternative, altType)) {
                                alternative
                            } else {
                                findMaxPairToBeatWithFengRenPei(cards, lastPlayedCards[0].rank)
                            }
                        } else {
                            findMaxPairToBeatWithFengRenPei(cards, lastPlayedCards[0].rank)
                        }
                    } else {
                        findMinPairToBeatWithFengRenPei(cards, lastPlayedCards[0].rank)
                    }
                }
                CardType.TRIPLE -> findMinTripleToBeatWithFengRenPei(cards, lastPlayedCards[0].rank)
                CardType.BOMB -> {
                    if (isTeammate) emptyList() else findMinBombToBeatWithFengRenPei(
                        cards,
                        lastPlayedCards[0].rank,
                        lastPlayedCards.size
                    )
                }

                CardType.THREE_WITH_TWO -> findMinThreeWithTwoToBeatWithFengRenPei(cards, lastPlayedCards)
                CardType.STRAIGHT -> findMinStraightToBeatWithFengRenPei(cards, lastPlayedCards)
                CardType.PLANK -> findMinPlankToBeatWithFengRenPei(cards, lastPlayedCards)
                CardType.STEEL_PLATE -> findMinSteelPlateToBeatWithFengRenPei(cards, lastPlayedCards)
                else -> emptyList()
            }

            if (playedCards.isEmpty() && !isTeammate) {
                playedCards = findAnyBombWithFengRenPei(cards)
            }
        } else {
            // 首轮出牌策略：优先出复杂牌型
            playedCards = when {
                minEnemyCards <= 2 -> {
                    when {
                        findAnySteelPlateWithFengRenPei(cards).isNotEmpty() -> findAnySteelPlateWithFengRenPei(cards)
                        findAnyPlankWithFengRenPei(cards).isNotEmpty() -> findAnyPlankWithFengRenPei(cards)
                        findAnyStraightWithFengRenPei(cards).isNotEmpty() -> findAnyStraightWithFengRenPei(cards)
                        findAnyThreeWithTwoWithFengRenPei(cards).isNotEmpty() -> findAnyThreeWithTwoWithFengRenPei(cards)
                        findAnyTripleWithFengRenPei(cards).isNotEmpty() -> findAnyTripleWithFengRenPei(cards)
                        findAnyPairWithFengRenPei(cards).isNotEmpty() -> findMaxPairWithFengRenPei(cards)
                        else -> findMaxSingleWithFengRenPei(cards)
                    }
                }
                findAnySteelPlateWithFengRenPei(cards).isNotEmpty() -> findAnySteelPlateWithFengRenPei(cards)
                findAnyPlankWithFengRenPei(cards).isNotEmpty() -> findAnyPlankWithFengRenPei(cards)
                findAnyStraightWithFengRenPei(cards).isNotEmpty() -> findAnyStraightWithFengRenPei(cards)
                findAnyThreeWithTwoWithFengRenPei(cards).isNotEmpty() -> findAnyThreeWithTwoWithFengRenPei(cards)
                findAnyTripleWithFengRenPei(cards).isNotEmpty() -> findAnyTripleWithFengRenPei(cards)
                findAnyPairWithFengRenPei(cards).isNotEmpty() -> findAnyPairWithFengRenPei(cards)
                else -> findMinSingleWithFengRenPei(cards)
            }
        }

        return if (playedCards.isNotEmpty()) {
            val success = playCards(aiPlayer.id, playedCards)
            if (success) {
                playedCards[0]
            } else {
                passTurn(aiPlayer.id)
                null
            }
        } else {
            passTurn(aiPlayer.id)
            null
        }
    }

    // ==================== 以下所有找牌方法都支持逢人配 ====================

    /**
     * 【新增】找最小单张压牌（支持逢人配）
     */
    private fun findMinSingleToBeatWithFengRenPei(cards: List<Card>, target: Card): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val targetEffectiveValue = if (isFengRenPei(target)) getEffectiveValue(fixedLevelRank) else getEffectiveValue(target.rank)

        // 优先用普通牌
        val minNormal = normalCards.filter { getEffectiveValue(it.rank) > targetEffectiveValue }
            .minWithOrNull(Comparator { c1, c2 ->
                getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
            })

        if (minNormal != null) return listOf(minNormal)

        // 没有普通牌可用，用逢人配
        if (fengRenPeiList.isNotEmpty()) return listOf(fengRenPeiList[0])

        return emptyList()
    }

    /**
     * 【新增】找最大单张压牌（支持逢人配）
     */
    private fun findMaxSingleToBeatWithFengRenPei(cards: List<Card>, target: Card): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val targetEffectiveValue = if (isFengRenPei(target)) getEffectiveValue(fixedLevelRank) else getEffectiveValue(target.rank)

        // 优先用普通牌（从大到小）
        val maxNormal = normalCards.filter { getEffectiveValue(it.rank) > targetEffectiveValue }
            .maxWithOrNull(Comparator { c1, c2 ->
                getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
            })

        if (maxNormal != null) return listOf(maxNormal)

        // 用逢人配
        if (fengRenPeiList.isNotEmpty()) return listOf(fengRenPeiList[0])

        return emptyList()
    }

    /**
     * 【新增】找最小对子压牌（支持逢人配）
     */
    private fun findMinPairToBeatWithFengRenPei(cards: List<Card>, targetRank: CardRank): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val targetEffectiveValue = getEffectiveValue(targetRank)

        val rankMap = normalCards.groupBy { it.rank }

        // 找普通对子
        val validPairs = rankMap.filter {
            getEffectiveValue(it.key) > targetEffectiveValue && it.value.size >= 2
        }.keys.sortedBy { getEffectiveValue(it) }

        if (validPairs.isNotEmpty()) {
            return rankMap[validPairs.first()]!!.take(2)
        }

        // 用1张配+1张普通牌组成对子
        if (fengRenPeiList.isNotEmpty()) {
            val minCard = normalCards.filter { getEffectiveValue(it.rank) > targetEffectiveValue }
                .minWithOrNull(Comparator { c1, c2 ->
                    getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
                })
            if (minCard != null) {
                return listOf(fengRenPeiList[0], minCard)
            }
        }

        return emptyList()
    }

    /**
     * 【新增】找最大对子压牌（支持逢人配）
     */
    private fun findMaxPairToBeatWithFengRenPei(cards: List<Card>, targetRank: CardRank): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val targetEffectiveValue = getEffectiveValue(targetRank)

        val rankMap = normalCards.groupBy { it.rank }

        // 找普通对子（从大到小）
        val validPairs = rankMap.filter {
            getEffectiveValue(it.key) > targetEffectiveValue && it.value.size >= 2
        }.keys.sortedByDescending { getEffectiveValue(it) }

        if (validPairs.isNotEmpty()) {
            return rankMap[validPairs.first()]!!.take(2)
        }

        // 用配组成对子
        if (fengRenPeiList.isNotEmpty()) {
            val maxCard = normalCards.filter { getEffectiveValue(it.rank) > targetEffectiveValue }
                .maxWithOrNull(Comparator { c1, c2 ->
                    getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
                })
            if (maxCard != null) {
                return listOf(fengRenPeiList[0], maxCard)
            }
        }

        return emptyList()
    }

    /**
     * 【新增】找最小三张压牌（支持逢人配）
     */
    private fun findMinTripleToBeatWithFengRenPei(cards: List<Card>, targetRank: CardRank): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val targetEffectiveValue = getEffectiveValue(targetRank)
        val fengRenPeiCount = fengRenPeiList.size

        val rankMap = normalCards.groupBy { it.rank }

        // 找普通三张
        val validTriples = rankMap.filter {
            getEffectiveValue(it.key) > targetEffectiveValue && it.value.size >= 3
        }.keys.sortedBy { getEffectiveValue(it) }

        if (validTriples.isNotEmpty()) {
            return rankMap[validTriples.first()]!!.take(3)
        }

        // 用配凑三张：2配+1张 或 1配+2张相同
        if (fengRenPeiCount >= 2) {
            val minCard = normalCards.filter { getEffectiveValue(it.rank) > targetEffectiveValue }
                .minWithOrNull(Comparator { c1, c2 ->
                    getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
                })
            if (minCard != null) {
                return listOf(fengRenPeiList[0], fengRenPeiList[1], minCard)
            }
        }

        if (fengRenPeiCount >= 1) {
            val pairRank = rankMap.filter {
                getEffectiveValue(it.key) > targetEffectiveValue && it.value.size >= 2
            }.keys.sortedBy { getEffectiveValue(it) }.firstOrNull()

            if (pairRank != null) {
                return rankMap[pairRank]!!.take(2) + fengRenPeiList[0]
            }
        }

        return emptyList()
    }

    /**
     * 【新增】找最小炸弹压牌（支持逢人配）
     */
    private fun findMinBombToBeatWithFengRenPei(
        cards: List<Card>,
        targetRank: CardRank,
        targetCount: Int
    ): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val fengRenPeiCount = fengRenPeiList.size
        val targetEffectiveValue = getEffectiveValue(targetRank)

        val rankMap = normalCards.groupBy { it.rank }

        // 找相同数量且更大的普通炸弹
        val sameCount = rankMap.filter {
            it.value.size >= targetCount && getEffectiveValue(it.key) > targetEffectiveValue
        }.keys.sortedBy { getEffectiveValue(it) }

        if (sameCount.isNotEmpty()) {
            return rankMap[sameCount.first()]!!.take(targetCount)
        }

        // 找数量更多的炸弹（可以用配）
        for (count in (targetCount + 1)..8) {
            // 普通牌数量+配数量 >= count
            val possibleRanks = rankMap.filter { entry ->
                entry.value.size + fengRenPeiCount >= count
            }.keys.sortedBy { getEffectiveValue(it) }

            for (rank in possibleRanks) {
                val normalCount = rankMap[rank]!!.size
                val needFengRenPei = count - normalCount
                if (needFengRenPei <= fengRenPeiCount) {
                    return rankMap[rank]!!.take(normalCount) + fengRenPeiList.take(needFengRenPei)
                }
            }
        }

        return emptyList()
    }

    /**
     * 【新增】找最小三带二压牌（支持逢人配）
     */
    private fun findMinThreeWithTwoToBeatWithFengRenPei(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val fengRenPeiCount = fengRenPeiList.size

        val lastThreeRank = lastCards.groupBy { it.rank }.filter { it.value.size == 3 }.keys.firstOrNull()
            ?: lastCards[0].rank
        val lastEffectiveValue = if (isFengRenPei(lastCards[0])) getEffectiveValue(fixedLevelRank) else getEffectiveValue(lastThreeRank)

        val rankMap = normalCards.groupBy { it.rank }

        // 找三张部分
        var threeCards: List<Card>? = null
        var usedFengRenPeiForThree = 0

        // 普通三张
        val validTriples = rankMap.filter {
            getEffectiveValue(it.key) > lastEffectiveValue && it.value.size >= 3
        }.keys.sortedBy { getEffectiveValue(it) }

        if (validTriples.isNotEmpty()) {
            threeCards = rankMap[validTriples.first()]!!.take(3)
        } else if (fengRenPeiCount >= 2) {
            // 2配+1张
            val minCard = normalCards.filter { getEffectiveValue(it.rank) > lastEffectiveValue }
                .minWithOrNull(Comparator { c1, c2 ->
                    getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
                })
            if (minCard != null) {
                threeCards = listOf(fengRenPeiList[0], fengRenPeiList[1], minCard)
                usedFengRenPeiForThree = 2
            }
        } else if (fengRenPeiCount >= 1) {
            // 1配+2张相同
            val pairRank = rankMap.filter {
                getEffectiveValue(it.key) > lastEffectiveValue && it.value.size >= 2
            }.keys.sortedBy { getEffectiveValue(it) }.firstOrNull()

            if (pairRank != null) {
                threeCards = rankMap[pairRank]!!.take(2) + fengRenPeiList[0]
                usedFengRenPeiForThree = 1
            }
        }

        if (threeCards == null) return emptyList()

        // 找对子部分（不能用三张的点数）
        val threeRank = threeCards.filter { !isFengRenPei(it) }.map { it.rank }.distinct().firstOrNull()
        val remainingFengRenPei = fengRenPeiCount - usedFengRenPeiForThree
        val remainingNormal = normalCards.filter { it.rank != threeRank }

        val pairCards: List<Card> = when {
            // 普通对子
            remainingNormal.groupBy { it.rank }.values.any { it.size >= 2 } -> {
                remainingNormal.groupBy { it.rank }.values.first { it.size >= 2 }.take(2)
            }
            // 1配+1张
            remainingFengRenPei >= 1 && remainingNormal.isNotEmpty() -> {
                listOf(fengRenPeiList[usedFengRenPeiForThree], remainingNormal[0])
            }
            // 2配（当对子）
            remainingFengRenPei >= 2 -> {
                listOf(fengRenPeiList[usedFengRenPeiForThree], fengRenPeiList[usedFengRenPeiForThree + 1])
            }
            else -> return emptyList()
        }

        return threeCards + pairCards
    }

    /**
     * 【新增】找最小顺子压牌（支持逢人配）
     */
    private fun findMinStraightToBeatWithFengRenPei(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val fengRenPeiCount = fengRenPeiList.size

        val lastMaxEffective = getMaxEffectiveValueWithFengRenPei(lastCards)
        val size = lastCards.size

        // 过滤有效牌（非2、非王、非配）
        val validNormalCards = normalCards.filter {
            it.rank != CardRank.TWO && !it.rank.isJoker()
        }

        // 尝试组成顺子
        val uniqueRanks = validNormalCards.map { it.rank.value }.distinct().sorted()

        for (start in 3..(lastMaxEffective - size + 1)) {
            val end = start + size - 1
            if (end > 14) continue // A是14

            val neededRanks = (start..end).toList()
            val haveRanks = uniqueRanks.filter { it in neededRanks }
            val missingCount = neededRanks.size - haveRanks.size

            if (missingCount <= fengRenPeiCount) {
                // 可以组成顺子
                val resultCards = mutableListOf<Card>()
                val usedFengRenPei = mutableListOf<Card>()

                for (rankValue in neededRanks) {
                    val card = validNormalCards.find { it.rank.value == rankValue }
                    if (card != null) {
                        resultCards.add(card)
                    } else {
                        // 用配填补
                        val fengRenPei = fengRenPeiList.find { it !in usedFengRenPei }
                        if (fengRenPei != null) {
                            usedFengRenPei.add(fengRenPei)
                            resultCards.add(fengRenPei)
                        }
                    }
                }

                if (resultCards.size == size) {
                    return resultCards
                }
            }
        }

        return emptyList()
    }

    /**
     * 【新增】找最小木板压牌（支持逢人配）- 简化版
     */
    private fun findMinPlankToBeatWithFengRenPei(cards: List<Card>, lastCards: List<Card>): List<Card> {
        // 简化处理：有配时不找木板，避免复杂性
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        if (fengRenPeiList.isNotEmpty()) return emptyList()
        return findMinPlankToBeat(cards, lastCards)
    }

    /**
     * 【保留】找最小木板压牌（无逢人配）
     */
    private fun findMinPlankToBeat(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val lastMaxEffective = lastCards.maxOf { getEffectiveValue(it.rank) }
        val rankMap = cards.filter { it.rank != CardRank.TWO && !it.rank.isJoker() }.groupBy { it.rank }

        val validRanks = rankMap.filter {
            it.value.size >= 2 && getEffectiveValue(it.key) > lastMaxEffective - 2
        }.keys.sortedBy { getEffectiveValue(it) }

        if (validRanks.size < 3) return emptyList()

        for (i in 0..validRanks.size - 3) {
            val ranks = validRanks.subList(i, i + 3)
            if (getEffectiveValue(ranks[2]) > lastMaxEffective &&
                ranks[1].value == ranks[0].value + 1 &&
                ranks[2].value == ranks[1].value + 1
            ) {
                return ranks.flatMap { rankMap[it]!!.take(2) }
            }
        }
        return emptyList()
    }

    /**
     * 【新增】找最小钢板压牌（支持逢人配）- 简化版
     */
    private fun findMinSteelPlateToBeatWithFengRenPei(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        if (fengRenPeiList.isNotEmpty()) return emptyList() // 简化：有配时不处理钢板
        return findMinSteelPlateToBeat(cards, lastCards)
    }

    /**
     * 【保留】找最小钢板压牌（无逢人配）
     */
    private fun findMinSteelPlateToBeat(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val lastMaxEffective = lastCards.groupBy { it.rank }.keys.maxOf { getEffectiveValue(it) }
        val rankMap = cards.filter { it.rank != CardRank.TWO && !it.rank.isJoker() }.groupBy { it.rank }

        val validRanks = rankMap.filter {
            it.value.size >= 3 && getEffectiveValue(it.key) > lastMaxEffective - 1
        }.keys.sortedBy { getEffectiveValue(it) }

        if (validRanks.size < 2) return emptyList()

        for (i in 0..validRanks.size - 2) {
            val ranks = validRanks.subList(i, i + 2)
            if (getEffectiveValue(ranks[1]) > lastMaxEffective &&
                ranks[1].value == ranks[0].value + 1
            ) {
                return ranks.flatMap { rankMap[it]!!.take(3) }
            }
        }
        return emptyList()
    }

    /**
     * 【新增】找任意钢板（支持逢人配）- 简化版
     */
    private fun findAnySteelPlateWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        if (fengRenPeiList.isNotEmpty()) return emptyList()
        return findAnySteelPlate(cards)
    }

    /**
     * 【保留】找任意钢板（无逢人配）
     */
    private fun findAnySteelPlate(cards: List<Card>): List<Card> {
        val rankMap = cards.filter { it.rank != CardRank.TWO && !it.rank.isJoker() }.groupBy { it.rank }
        val tripleRanks = rankMap.filter { it.value.size >= 3 }.keys.sortedBy { getEffectiveValue(it) }

        if (tripleRanks.size < 2) return emptyList()

        for (i in 0..tripleRanks.size - 2) {
            val ranks = tripleRanks.subList(i, i + 2)
            if (ranks[1].value == ranks[0].value + 1) {
                return ranks.flatMap { rankMap[it]!!.take(3) }
            }
        }
        return emptyList()
    }

    /**
     * 【新增】找任意木板（支持逢人配）- 简化版
     */
    private fun findAnyPlankWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        if (fengRenPeiList.isNotEmpty()) return emptyList()
        return findAnyPlank(cards)
    }

    /**
     * 【保留】找任意木板（无逢人配）
     */
    private fun findAnyPlank(cards: List<Card>): List<Card> {
        val rankMap = cards.filter { it.rank != CardRank.TWO && !it.rank.isJoker() }.groupBy { it.rank }
        val pairRanks = rankMap.filter { it.value.size >= 2 }.keys.sortedBy { getEffectiveValue(it) }

        if (pairRanks.size < 3) return emptyList()

        for (i in 0..pairRanks.size - 3) {
            val ranks = pairRanks.subList(i, i + 3)
            if (ranks[1].value == ranks[0].value + 1 && ranks[2].value == ranks[1].value + 1) {
                return ranks.flatMap { rankMap[it]!!.take(2) }
            }
        }
        return emptyList()
    }

    /**
     * 【新增】找任意顺子（支持逢人配）
     */
    private fun findAnyStraightWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val fengRenPeiCount = fengRenPeiList.size

        val validCards = normalCards.filter { it.rank != CardRank.TWO && !it.rank.isJoker() }
            .groupBy { it.rank }.map { it.value.first() }.sortedBy { getEffectiveValue(it.rank) }

        for (size in minOf(validCards.size + fengRenPeiCount, 10) downTo 5) {
            for (i in 0..validCards.size - 1) {
                // 尝试从每个位置开始组成顺子
                val startRank = validCards[i].rank.value
                val endRank = startRank + size - 1

                if (endRank > 14) continue // 不能超过A

                val neededRanks = (startRank..endRank).toList()
                val haveRanks = validCards.map { it.rank.value }.filter { it in neededRanks }
                val missingCount = neededRanks.size - haveRanks.size

                if (missingCount <= fengRenPeiCount) {
                    // 可以组成
                    val resultCards = mutableListOf<Card>()
                    val usedFengRenPei = mutableListOf<Card>()

                    for (rankValue in neededRanks) {
                        val card = validCards.find { it.rank.value == rankValue }
                        if (card != null) {
                            resultCards.add(card)
                        } else {
                            val fengRenPei = fengRenPeiList.find { it !in usedFengRenPei }
                            if (fengRenPei != null) {
                                usedFengRenPei.add(fengRenPei)
                                resultCards.add(fengRenPei)
                            }
                        }
                    }

                    if (resultCards.size == size) {
                        return resultCards
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * 【新增】找任意三带二（支持逢人配）
     */
    private fun findAnyThreeWithTwoWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val fengRenPeiCount = fengRenPeiList.size

        val rankMap = normalCards.groupBy { it.rank }

        // 找三张部分
        var threeCards: List<Card>? = null
        var usedFengRenPeiForThree = 0

        // 普通三张
        val tripleRank = rankMap.filter { it.value.size >= 3 }.keys.minByOrNull { getEffectiveValue(it) }
        if (tripleRank != null) {
            threeCards = rankMap[tripleRank]!!.take(3)
        } else if (fengRenPeiCount >= 2) {
            // 2配+1张
            val minCard = normalCards.minWithOrNull(Comparator { c1, c2 ->
                getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
            })
            if (minCard != null) {
                threeCards = listOf(fengRenPeiList[0], fengRenPeiList[1], minCard)
                usedFengRenPeiForThree = 2
            }
        } else if (fengRenPeiCount >= 1) {
            // 1配+2张相同
            val pairRank = rankMap.filter { it.value.size >= 2 }.keys.minByOrNull { getEffectiveValue(it) }
            if (pairRank != null) {
                threeCards = rankMap[pairRank]!!.take(2) + fengRenPeiList[0]
                usedFengRenPeiForThree = 1
            }
        }

        if (threeCards == null) return emptyList()

        // 找对子部分
        val threeRank = threeCards.filter { !isFengRenPei(it) }.map { it.rank }.distinct().firstOrNull()
        val remainingFengRenPei = fengRenPeiCount - usedFengRenPeiForThree
        val remainingNormal = normalCards.filter { it.rank != threeRank }

        val pairCards: List<Card> = when {
            remainingNormal.groupBy { it.rank }.values.any { it.size >= 2 } -> {
                remainingNormal.groupBy { it.rank }.values.first { it.size >= 2 }.take(2)
            }
            remainingFengRenPei >= 1 && remainingNormal.isNotEmpty() -> {
                listOf(fengRenPeiList[usedFengRenPeiForThree], remainingNormal[0])
            }
            remainingFengRenPei >= 2 -> {
                listOf(fengRenPeiList[usedFengRenPeiForThree], fengRenPeiList[usedFengRenPeiForThree + 1])
            }
            else -> return emptyList()
        }

        return threeCards + pairCards
    }

    /**
     * 【新增】找任意三张（支持逢人配）
     */
    private fun findAnyTripleWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val fengRenPeiCount = fengRenPeiList.size

        val rankMap = normalCards.groupBy { it.rank }

        // 普通三张
        val triple = rankMap.filter { it.value.size >= 3 }.values.minByOrNull { getEffectiveValue(it[0].rank) }
        if (triple != null) return triple.take(3)

        // 2配+1张
        if (fengRenPeiCount >= 2 && normalCards.isNotEmpty()) {
            val minCard = normalCards.minWithOrNull(Comparator { c1, c2 ->
                getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
            })
            if (minCard != null) {
                return listOf(fengRenPeiList[0], fengRenPeiList[1], minCard)
            }
        }

        // 1配+2张相同
        if (fengRenPeiCount >= 1) {
            val pair = rankMap.filter { it.value.size >= 2 }.values.minByOrNull { getEffectiveValue(it[0].rank) }
            if (pair != null) {
                return pair.take(2) + fengRenPeiList[0]
            }
        }

        return emptyList()
    }

    /**
     * 【新增】找任意对子（支持逢人配）
     */
    private fun findAnyPairWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)

        // 普通对子
        val rankMap = normalCards.groupBy { it.rank }
        val pair = rankMap.filter { it.value.size >= 2 }.values.minByOrNull { getEffectiveValue(it[0].rank) }
        if (pair != null) return pair.take(2)

        // 1配+1张
        if (fengRenPeiList.isNotEmpty() && normalCards.isNotEmpty()) {
            val minCard = normalCards.minWithOrNull(Comparator { c1, c2 ->
                getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
            })
            if (minCard != null) {
                return listOf(fengRenPeiList[0], minCard)
            }
        }

        return emptyList()
    }

    /**
     * 【新增】找最大对子（支持逢人配）
     */
    private fun findMaxPairWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)

        val rankMap = normalCards.groupBy { it.rank }
        val pair = rankMap.filter { it.value.size >= 2 }.values.maxByOrNull { getEffectiveValue(it[0].rank) }
        if (pair != null) return pair.take(2)

        if (fengRenPeiList.isNotEmpty() && normalCards.isNotEmpty()) {
            val maxCard = normalCards.maxWithOrNull(Comparator { c1, c2 ->
                getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
            })
            if (maxCard != null) {
                return listOf(fengRenPeiList[0], maxCard)
            }
        }

        return emptyList()
    }

    /**
     * 【新增】找任意炸弹（支持逢人配）
     */
    private fun findAnyBombWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)
        val fengRenPeiCount = fengRenPeiList.size

        val rankMap = normalCards.groupBy { it.rank }

        // 优先找更大的炸弹（8张>6张>5张>4张）
        for (count in 8 downTo 4) {
            // 普通炸弹
            val normalBomb = rankMap.filter { it.value.size >= count }.values.minByOrNull { getEffectiveValue(it[0].rank) }
            if (normalBomb != null) return normalBomb.take(count)

            // 用配凑炸弹
            if (fengRenPeiCount > 0) {
                for ((rank, cardList) in rankMap) {
                    if (cardList.size + fengRenPeiCount >= count) {
                        val needFengRenPei = count - cardList.size
                        if (needFengRenPei <= fengRenPeiCount) {
                            return cardList + fengRenPeiList.take(needFengRenPei)
                        }
                    }
                }
            }
        }

        return emptyList()
    }

    /**
     * 【新增】找最大单张（支持逢人配）
     */
    private fun findMaxSingleWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)

        // 优先出普通牌（从小到大）
        val maxNormal = normalCards.maxWithOrNull(Comparator { c1, c2 ->
            getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
        })

        if (maxNormal != null) return listOf(maxNormal)

        // 出逢人配
        if (fengRenPeiList.isNotEmpty()) return listOf(fengRenPeiList[0])

        return emptyList()
    }

    /**
     * 【新增】找最小单张（支持逢人配）
     */
    private fun findMinSingleWithFengRenPei(cards: List<Card>): List<Card> {
        val (fengRenPeiList, normalCards) = separateFengRenPei(cards)

        val minNormal = normalCards.minWithOrNull(Comparator { c1, c2 ->
            getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
        })

        if (minNormal != null) return listOf(minNormal)

        if (fengRenPeiList.isNotEmpty()) return listOf(fengRenPeiList[0])

        return emptyList()
    }

    /**
     * 【修改】判断队友出的牌是否算"大牌"，使用有效值（支持逢人配）
     */
    private fun isTeammateBigPlay(cards: List<Card>, cardType: CardType): Boolean {
        // 将逢人配视为级牌值
        val effectiveValues = cards.map {
            if (isFengRenPei(it)) getEffectiveValue(fixedLevelRank) else getEffectiveValue(it.rank)
        }

        return when (cardType) {
            CardType.SINGLE -> effectiveValues.maxOrNull() ?: 0 >= 12
            CardType.PAIR -> effectiveValues.maxOrNull() ?: 0 >= 12
            CardType.TRIPLE -> effectiveValues.maxOrNull() ?: 0 >= 12
            CardType.THREE_WITH_TWO -> effectiveValues.maxOrNull() ?: 0 >= 12
            CardType.STRAIGHT -> effectiveValues.maxOrNull() ?: 0 >= 10
            CardType.PLANK -> effectiveValues.maxOrNull() ?: 0 >= 12
            CardType.STEEL_PLATE -> effectiveValues.maxOrNull() ?: 0 >= 12
            CardType.BOMB -> true
            CardType.STRAIGHT_FLUSH -> true
        }
    }

    fun passTurn(playerId: String) {
        if (!this::gameRoom.isInitialized) return

        // 【修改】移除或放宽这个检查，确保AI能正常过牌
        // 原代码会阻止非当前回合玩家过牌，但AI链中可能状态不同步
        val currentPlayer = gameRoom.players.find { it.isCurrentTurn }

        // 【修改】增加日志，并允许当前玩家ID匹配或明确是AI调用
        if (currentPlayer?.id != playerId) {
            android.util.Log.w("GuandanGame", "passTurn: 玩家$playerId 不是当前玩家 ${currentPlayer?.id}，但继续执行")
            // 【关键】不要直接返回，尝试找到该玩家并允许其过牌（防止状态不同步导致卡住）
            val requestingPlayer = gameRoom.players.find { it.id == playerId }
            if (requestingPlayer == null) {
                android.util.Log.e("GuandanGame", "passTurn: 找不到玩家$playerId")
                return
            }
            // 如果确实是该玩家的回合（状态标记可能延迟），允许过牌
            if (!requestingPlayer.isCurrentTurn) {
                android.util.Log.d("GuandanGame", "passTurn: 玩家${requestingPlayer.name}标记不是当前回合，但强制过牌")
            }
        }

        passCount++
        android.util.Log.d("GuandanGame", "玩家${currentPlayer?.name ?: playerId} 过牌，passCount=$passCount")

        if (passCount >= gameRoom.players.size - 1) {
            // 一圈都过了，重置
            lastPlayedCards = emptyList()
            lastPlayerId = ""
            lastPlayerName = ""
            passCount = 0
            android.util.Log.d("GuandanGame", "一圈过牌，重置出牌状态")
        }

        switchToNextPlayer()
    }

    /**
     * 切换玩家时不再改变级牌（级牌固定）
     */
    fun switchToNextPlayer() {
        if (!this::gameRoom.isInitialized || gameRoom.players.isEmpty()) return

        gameRoom.players.forEach { it.isCurrentTurn = false }

        val currIndex = gameRoom.players.indexOfFirst { it.id == gameRoom.currentPlayerId }
        val nextIndex = if (currIndex == -1) 0 else (currIndex + 1) % gameRoom.players.size

        val nextPlayer = gameRoom.players[nextIndex]
        gameRoom.currentPlayerId = nextPlayer.id
        nextPlayer.isCurrentTurn = true

        android.util.Log.d("GuandanGame", "切换到玩家: ${nextPlayer.name}, isAI=${nextPlayer.isAI}")
    }

    fun isGameOver(): Boolean {
        return if (this::gameRoom.isInitialized) gameRoom.players.any { it.cards.isEmpty() } else false
    }

    /**
     * 获取赢家，同时计算升级并保存头游位置
     */
    fun getWinner(): Player? {
        if (!this::gameRoom.isInitialized) return null

        val oldTeam0Level = team0Level
        val oldTeam1Level = team1Level

        val winner = gameRoom.players.find { it.cards.isEmpty() }
        if (winner != null && !hasUpgradedThisRound) {
            hasUpgradedThisRound = true
            saveTouYouPosition()
            calculateRanksAndUpgrade()
        }
        return winner
    }

    /**
     * 保存头游位置（0=玩家, 1=AI1, 2=AI2, 3=AI3）
     */
    private fun saveTouYouPosition() {
        val sortedPlayers = gameRoom.players.sortedBy { it.cards.size }
        val touYou = sortedPlayers.firstOrNull()
        if (touYou != null) {
            lastTouYouPosition = gameRoom.players.indexOfFirst { it.id == touYou.id }
            println("保存头游位置：${touYou.name}，位置=$lastTouYouPosition")
        }
    }

    /**
     * 重新排序所有玩家的手牌（使用固定级牌）
     */
    fun resortAllCards() {
        if (!this::gameRoom.isInitialized) return
        val comparatorWithLevel = CardComparator(fixedLevelRank)
        gameRoom.players.forEach { player ->
            player.cards.sortWith(comparatorWithLevel)
        }
    }

    /**
     * 计算玩家排名并执行升级
     */
    private fun calculateRanksAndUpgrade() {
        val sortedPlayers = gameRoom.players.sortedBy { it.cards.size }

        val playerRanks = sortedPlayers.mapIndexed { index, player ->
            player.id to (index + 1)
        }

        val touYouPlayer = sortedPlayers.firstOrNull()
        if (touYouPlayer != null) {
            val winnerTeam = touYouPlayer.team

            val winnerTeammate = sortedPlayers.find { it.team == winnerTeam && it.id != touYouPlayer.id }
            val teammateRank = winnerTeammate?.let { player ->
                sortedPlayers.indexOfFirst { it.id == player.id } + 1
            } ?: 4

            println("头游: ${touYouPlayer.name}, 队伍=$winnerTeam, 队友排名: $teammateRank")

            upgradeLevel(winnerTeam, playerRanks)
        }
    }

    /**
     * 升级函数 - 分别升级两队等级
     */
    fun upgradeLevel(winnerTeam: Int, playerRanks: List<Pair<String, Int>>) {
        val touYouId = playerRanks.find { it.second == 1 }?.first ?: return
        val touYouPlayer = gameRoom.players.find { it.id == touYouId } ?: return

        if (touYouPlayer.team != winnerTeam) return

        val teammatePair = playerRanks.find { (playerId, rank) ->
            val player = gameRoom.players.find { it.id == playerId }
            player?.team == winnerTeam && playerId != touYouId
        }

        val teammateRank = teammatePair?.second ?: 4

        val upgradeLevels = when (teammateRank) {
            2 -> 3
            3 -> 2
            4 -> 1
            else -> 1
        }

        println("升级计算：赢家队伍=$winnerTeam, 队友排名=$teammateRank, 升级级数=$upgradeLevels")

        if (winnerTeam == 0) {
            team0Level = getNextLevel(team0Level, upgradeLevels)
            println("0队升级：$team0Level 级")
        } else {
            team1Level = getNextLevel(team1Level, upgradeLevels)
            println("1队升级：$team1Level 级")
        }
    }

    /**
     * 重置升级标志（用于重新开牌）
     */
    fun resetUpgradeFlag() {
        hasUpgradedThisRound = false
    }
}