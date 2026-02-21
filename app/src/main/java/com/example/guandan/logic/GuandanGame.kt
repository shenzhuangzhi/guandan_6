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
                    isPlank(cards) -> CardType.PLANK
                    isSteelPlate(cards) -> CardType.STEEL_PLATE
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
     * 【修改】牌大小比较，使用有效值，并增加队友判断
     */
    private fun canBeatLastCards(cards: List<Card>, currentType: CardType): Boolean {
        val lastType = getCardType(lastPlayedCards) ?: return false

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
            CardType.SINGLE -> getEffectiveValue(cards[0].rank) > getEffectiveValue(lastPlayedCards[0].rank)
            CardType.PAIR -> getEffectiveValue(cards[0].rank) > getEffectiveValue(lastPlayedCards[0].rank)
            CardType.TRIPLE -> getEffectiveValue(cards[0].rank) > getEffectiveValue(lastPlayedCards[0].rank)
            CardType.BOMB -> compareBomb(cards, lastPlayedCards)
            CardType.THREE_WITH_TWO -> {
                val currentThreeRank = cards.groupBy { it.rank }.filter { it.value.size == 3 }.keys.first()
                val lastThreeRank = lastPlayedCards.groupBy { it.rank }.filter { it.value.size == 3 }.keys.first()
                getEffectiveValue(currentThreeRank) > getEffectiveValue(lastThreeRank)
            }
            CardType.STRAIGHT, CardType.STRAIGHT_FLUSH -> {
                val currentMax = cards.maxOf { getEffectiveValue(it.rank) }
                val lastMax = lastPlayedCards.maxOf { getEffectiveValue(it.rank) }
                currentMax > lastMax
            }
            CardType.PLANK -> {
                val currentMax = cards.maxOf { getEffectiveValue(it.rank) }
                val lastMax = lastPlayedCards.maxOf { getEffectiveValue(it.rank) }
                currentMax > lastMax
            }
            CardType.STEEL_PLATE -> compareSteelPlate(cards, lastPlayedCards)
        }
    }


    /**
     * 【修改】炸弹比较使用有效值
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
     * 【修改】钢板比较使用有效值
     */
    private fun compareSteelPlate(current: List<Card>, last: List<Card>): Boolean {
        val currentRanks = current.groupBy { it.rank }.keys
        val lastRanks = last.groupBy { it.rank }.keys
        val currentMax = currentRanks.maxOf { getEffectiveValue(it) }
        val lastMax = lastRanks.maxOf { getEffectiveValue(it) }
        return currentMax > lastMax
    }

    /**
     * 【核心修改】AI自动出牌 - 增加敌方快赢时的防守策略
     */
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

        // 【新增】检查是否有敌方剩余1-2张牌
        val enemyAlmostWin = gameRoom.players.any {
            it.team != aiPlayer.team && (it.cards.size == 1 || it.cards.size == 2)
        }

        // 【新增】获取敌方剩余牌数（用于判断危险程度）
        val minEnemyCards = gameRoom.players
            .filter { it.team != aiPlayer.team }
            .minOfOrNull { it.cards.size } ?: 27

        if (lastPlayedCards.isNotEmpty()) {
            val lastType = getCardType(lastPlayedCards) ?: return null

            val lastPlayer = gameRoom.players.find { it.id == lastPlayerId }

            val isTeammate = lastPlayer != null && lastPlayer.team == aiPlayer.team && lastPlayer.id != aiPlayer.id

            if (isTeammate && isTeammateBigPlay(lastPlayedCards, lastType)) {
                passTurn(aiPlayer.id)
                return null
            }

            playedCards = when (lastType) {
                CardType.SINGLE -> {
                    // 【新增】敌方快赢时，尽量不出单张让敌方跑，除非能必胜
                    if (enemyAlmostWin) {
                        // 尝试找其他牌型压，如果找不到就过牌
                        val alternative = findAnyPair(cards).takeIf { it.isNotEmpty() }
                            ?: findAnyTriple(cards).takeIf { it.isNotEmpty() }
                            ?: findAnyBomb(cards).takeIf { it.isNotEmpty() }
                            ?: emptyList()
                        if (alternative.isNotEmpty()) {
                            val altType = getCardType(alternative)
                            if (altType != null && canBeatLastCards(alternative, altType)) {
                                alternative
                            } else {
                                // 必须用单张压时，从大到小出
                                findMaxSingleToBeat(cards, lastPlayedCards[0])
                            }
                        } else {
                            // 必须用单张压时，从大到小出
                            findMaxSingleToBeat(cards, lastPlayedCards[0])
                        }
                    } else {
                        findMinSingleToBeat(cards, lastPlayedCards[0])
                    }
                }
                CardType.PAIR -> {
                    // 【新增】敌方快赢时，尽量不出对子让敌方跑
                    if (enemyAlmostWin) {
                        val alternative = findAnyTriple(cards).takeIf { it.isNotEmpty() }
                            ?: findAnyBomb(cards).takeIf { it.isNotEmpty() }
                            ?: emptyList()
                        if (alternative.isNotEmpty()) {
                            val altType = getCardType(alternative)
                            if (altType != null && canBeatLastCards(alternative, altType)) {
                                alternative
                            } else {
                                // 必须用对子压时，从大到小出
                                findMaxPairToBeat(cards, lastPlayedCards[0].rank)
                            }
                        } else {
                            // 必须用对子压时，从大到小出
                            findMaxPairToBeat(cards, lastPlayedCards[0].rank)
                        }
                    } else {
                        findMinPairToBeat(cards, lastPlayedCards[0].rank)
                    }
                }
                CardType.TRIPLE -> findMinTripleToBeat(cards, lastPlayedCards[0].rank)
                CardType.BOMB -> {
                    if (isTeammate) emptyList() else findMinBombToBeat(
                        cards,
                        lastPlayedCards[0].rank,
                        lastPlayedCards.size
                    )
                }

                CardType.THREE_WITH_TWO -> findMinThreeWithTwoToBeat(cards, lastPlayedCards)
                CardType.STRAIGHT -> findMinStraightToBeat(cards, lastPlayedCards)
                CardType.PLANK -> findMinPlankToBeat(cards, lastPlayedCards)
                CardType.STEEL_PLATE -> findMinSteelPlateToBeat(cards, lastPlayedCards)
                else -> emptyList()
            }

            if (playedCards.isEmpty() && !isTeammate) {
                playedCards = findAnyBomb(cards)
            }
        } else {
            // 【修改】首轮出牌策略：敌方快赢时优先出多牌型，避免出单张/对子
            playedCards = when {
                // 敌方剩余1-2张时，优先出复杂牌型，避免给敌方跑牌机会
                minEnemyCards <= 2 -> {
                    when {
                        findAnySteelPlate(cards).isNotEmpty() -> findAnySteelPlate(cards)
                        findAnyPlank(cards).isNotEmpty() -> findAnyPlank(cards)
                        findAnyStraight(cards).isNotEmpty() -> findAnyStraight(cards)
                        findAnyThreeWithTwo(cards).isNotEmpty() -> findAnyThreeWithTwo(cards)
                        findAnyTriple(cards).isNotEmpty() -> findAnyTriple(cards)
                        // 必须出对子时，出最大对子
                        findAnyPair(cards).isNotEmpty() -> findMaxPair(cards)
                        // 必须出单张时，出最大单张
                        else -> findMaxSingle(cards)
                    }
                }
                // 正常情况下，优先出复杂牌型
                findAnySteelPlate(cards).isNotEmpty() -> findAnySteelPlate(cards)
                findAnyPlank(cards).isNotEmpty() -> findAnyPlank(cards)
                findAnyStraight(cards).isNotEmpty() -> findAnyStraight(cards)
                findAnyThreeWithTwo(cards).isNotEmpty() -> findAnyThreeWithTwo(cards)
                findAnyTriple(cards).isNotEmpty() -> findAnyTriple(cards)
                findAnyPair(cards).isNotEmpty() -> findAnyPair(cards)
                else -> {
                    val single = cards.minWithOrNull(Comparator { c1, c2 ->
                        getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
                    })
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
    /**
     * 【新增】从大到小找单张压牌（敌方快赢时使用）
     */
    private fun findMaxSingleToBeat(cards: List<Card>, target: Card): List<Card> {
        val targetEffectiveValue = getEffectiveValue(target.rank)
        return cards.filter { getEffectiveValue(it.rank) > targetEffectiveValue }
            .maxWithOrNull(Comparator { c1, c2 ->
                getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
            })?.let { listOf(it) } ?: emptyList()
    }

    /**
     * 【新增】从大到小找对子压牌（敌方快赢时使用）
     */
    private fun findMaxPairToBeat(cards: List<Card>, targetRank: CardRank): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val targetEffectiveValue = getEffectiveValue(targetRank)
        val validPairs = rankMap.filter {
            getEffectiveValue(it.key) > targetEffectiveValue && it.value.size >= 2
        }.keys.sortedByDescending { getEffectiveValue(it) }
        return if (validPairs.isNotEmpty()) rankMap[validPairs.first()]!!.take(2) else emptyList()
    }

    /**
     * 【新增】找最大对子（敌方快赢时首轮出）
     */
    private fun findMaxPair(cards: List<Card>): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val validPairs = rankMap.filter { it.value.size >= 2 }
            .keys.sortedByDescending { getEffectiveValue(it) }
        return if (validPairs.isNotEmpty()) rankMap[validPairs.first()]!!.take(2) else emptyList()
    }

    /**
     * 【新增】找最大单张（敌方快赢时首轮出）
     */
    private fun findMaxSingle(cards: List<Card>): List<Card> {
        return cards.maxWithOrNull(Comparator { c1, c2 ->
            getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
        })?.let { listOf(it) } ?: emptyList()
    }


    /**
     * 【修改】判断队友出的牌是否算"大牌"，使用有效值
     */
    private fun isTeammateBigPlay(cards: List<Card>, cardType: CardType): Boolean {
        return when (cardType) {
            CardType.SINGLE -> getEffectiveValue(cards[0].rank) >= 12
            CardType.PAIR -> getEffectiveValue(cards[0].rank) >= 12
            CardType.TRIPLE -> getEffectiveValue(cards[0].rank) >= 12
            CardType.THREE_WITH_TWO -> {
                val threeRank = cards.groupBy { it.rank }.filter { it.value.size == 3 }.keys.first()
                getEffectiveValue(threeRank) >= 12
            }
            CardType.STRAIGHT -> cards.maxOf { getEffectiveValue(it.rank) } >= 10
            CardType.PLANK -> cards.maxOf { getEffectiveValue(it.rank) } >= 12
            CardType.STEEL_PLATE -> {
                val maxRank = cards.groupBy { it.rank }.keys.maxOf { getEffectiveValue(it) }
                maxRank >= 12
            }
            CardType.BOMB -> true
            CardType.STRAIGHT_FLUSH -> true
        }
    }

    /**
     * 【修改】找最小单张压牌，使用有效值
     */
    private fun findMinSingleToBeat(cards: List<Card>, target: Card): List<Card> {
        val targetEffectiveValue = getEffectiveValue(target.rank)
        return cards.filter { getEffectiveValue(it.rank) > targetEffectiveValue }
            .minWithOrNull(Comparator { c1, c2 ->
                getEffectiveValue(c1.rank) - getEffectiveValue(c2.rank)
            })?.let { listOf(it) } ?: emptyList()
    }

    /**
     * 【修改】找最小对子压牌，使用有效值
     */
    private fun findMinPairToBeat(cards: List<Card>, targetRank: CardRank): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val targetEffectiveValue = getEffectiveValue(targetRank)
        val validPairs = rankMap.filter {
            getEffectiveValue(it.key) > targetEffectiveValue && it.value.size >= 2
        }.keys.sortedBy { getEffectiveValue(it) }
        return if (validPairs.isNotEmpty()) rankMap[validPairs.first()]!!.take(2) else emptyList()
    }

    /**
     * 【修改】找最小三张压牌，使用有效值
     */
    private fun findMinTripleToBeat(cards: List<Card>, targetRank: CardRank): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val targetEffectiveValue = getEffectiveValue(targetRank)
        val validTriples = rankMap.filter {
            getEffectiveValue(it.key) > targetEffectiveValue && it.value.size >= 3
        }.keys.sortedBy { getEffectiveValue(it) }
        return if (validTriples.isNotEmpty()) rankMap[validTriples.first()]!!.take(3) else emptyList()
    }

    /**
     * 【修改】找最小炸弹压牌，使用有效值
     */
    private fun findMinBombToBeat(
        cards: List<Card>,
        targetRank: CardRank,
        targetCount: Int
    ): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val targetEffectiveValue = getEffectiveValue(targetRank)
        val sameCount = rankMap.filter {
            it.value.size >= targetCount && getEffectiveValue(it.key) > targetEffectiveValue
        }.keys.sortedBy { getEffectiveValue(it) }
        if (sameCount.isNotEmpty()) {
            return rankMap[sameCount.first()]!!.take(targetCount)
        }
        val biggerCount = rankMap.filter { it.value.size > targetCount }
            .keys.sortedBy { getEffectiveValue(it) }
        return if (biggerCount.isNotEmpty()) {
            val rank = biggerCount.first()
            rankMap[rank]!!.take(minOf(rankMap[rank]!!.size, 8))
        } else emptyList()
    }

    /**
     * 【修改】找最小三带二压牌，使用有效值
     */
    private fun findMinThreeWithTwoToBeat(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val lastThreeRank = lastCards.groupBy { it.rank }.filter { it.value.size == 3 }.keys.first()
        val lastEffectiveValue = getEffectiveValue(lastThreeRank)
        val rankMap = cards.groupBy { it.rank }

        val validTriples = rankMap.filter {
            getEffectiveValue(it.key) > lastEffectiveValue && it.value.size >= 3
        }.keys.sortedBy { getEffectiveValue(it) }

        if (validTriples.isEmpty()) return emptyList()

        val threeRank = validTriples.first()
        val threeCards = rankMap[threeRank]!!.take(3)

        val validPairs = rankMap.filter {
            it.key != threeRank && it.value.size >= 2
        }.keys.sortedBy { getEffectiveValue(it) }

        if (validPairs.isEmpty()) return emptyList()

        val pairCards = rankMap[validPairs.first()]!!.take(2)
        return threeCards + pairCards
    }

    /**
     * 【修改】找最小顺子压牌，使用有效值
     */
    private fun findMinStraightToBeat(cards: List<Card>, lastCards: List<Card>): List<Card> {
        val lastMaxEffective = lastCards.maxOf { getEffectiveValue(it.rank) }
        val size = lastCards.size
        val validCards = cards.filter {
            it.rank != CardRank.TWO && !it.rank.isJoker() &&
                    getEffectiveValue(it.rank) > lastMaxEffective - size + 1
        }.groupBy { it.rank }.map { it.value.first() }.sortedBy { getEffectiveValue(it.rank) }

        if (validCards.size < size) return emptyList()

        for (i in 0..validCards.size - size) {
            val straight = validCards.subList(i, i + size)
            if (isStraight(straight) && straight.maxOf { getEffectiveValue(it.rank) } > lastMaxEffective) {
                return straight.flatMap { card ->
                    cards.filter { it.rank == card.rank }.take(1)
                }
            }
        }
        return emptyList()
    }

    /**
     * 【修改】找最小木板压牌，使用有效值
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
     * 【修改】找最小钢板压牌，使用有效值
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
     * 【修改】找任意钢板，使用有效值排序
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
     * 【修改】找任意木板，使用有效值排序
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
     * 【修改】找任意顺子，使用有效值排序
     */
    private fun findAnyStraight(cards: List<Card>): List<Card> {
        val validCards = cards.filter { it.rank != CardRank.TWO && !it.rank.isJoker() }
            .groupBy { it.rank }.map { it.value.first() }.sortedBy { getEffectiveValue(it.rank) }

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

    /**
     * 【修改】找任意三带二，使用有效值排序
     */
    private fun findAnyThreeWithTwo(cards: List<Card>): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val tripleRank = rankMap.filter { it.value.size >= 3 }.keys.minByOrNull { getEffectiveValue(it) }
            ?: return emptyList()
        val pairRank =
            rankMap.filter { it.key != tripleRank && it.value.size >= 2 }.keys.minByOrNull { getEffectiveValue(it) }
                ?: return emptyList()

        return rankMap[tripleRank]!!.take(3) + rankMap[pairRank]!!.take(2)
    }

    /**
     * 【修改】找任意三张，使用有效值排序
     */
    private fun findAnyTriple(cards: List<Card>): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        return rankMap.filter { it.value.size >= 3 }.values.minByOrNull { getEffectiveValue(it[0].rank) }
            ?.take(3) ?: emptyList()
    }

    /**
     * 【修改】找任意对子，使用有效值排序
     */
    private fun findAnyPair(cards: List<Card>): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        return rankMap.filter { it.value.size >= 2 }.values.minByOrNull { getEffectiveValue(it[0].rank) }
            ?.take(2) ?: emptyList()
    }

    /**
     * 【修改】找任意炸弹，使用有效值排序
     */
    private fun findAnyBomb(cards: List<Card>): List<Card> {
        val rankMap = cards.groupBy { it.rank }
        val bombRanks = rankMap.filter { it.value.size >= 4 }.keys.sortedBy { getEffectiveValue(it) }
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

    /**
     * 切换玩家时不再改变级牌（级牌固定）
     */
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

    /**
     * 获取赢家，同时计算升级并保存头游位置
     */
    fun getWinner(): Player? {
        if (!this::gameRoom.isInitialized) return null

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