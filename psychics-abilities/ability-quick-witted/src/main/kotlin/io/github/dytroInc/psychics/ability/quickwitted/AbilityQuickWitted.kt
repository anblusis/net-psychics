package io.github.dytroInc.psychics.ability.quickwitted

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.task.TickerTask
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.random.Random.Default.nextInt
import kotlin.random.Random.Default.nextDouble

@Name("quick-witted")
class AbilityConceptQuickWitted : AbilityConcept() {

    @Config
    val speedStartDifficulty = 1

    @Config
    val speedMaxAmplifier = 1

    @Config
    val speedAmplifierUpDifficultyInterval = 4

    @Config
    val resistanceStartDifficulty = 9

    @Config
    val resistanceMaxAmplifier = 1

    @Config
    val resistanceAmplifierUpDifficultyInterval = 3

    @Config
    val saturationStartDifficulty = 15

    @Config
    val answerTimeLimitTicks = 300L

    init {
        cooldownTime = 1000L
        durationTime = 30000L
        description = listOf(
            text("능력 사용 시 무작위 수학 문제가 나옵니다."),
            text("수학 문제를 연달아 맞힐수록 난이도가 올라갑니다."),
            text("난이도가 높은 문제를 풀수록 강한 버프를 받습니다.")
        )
        wand = ItemStack(Material.PAPER)
        displayName = "두뇌 회전"
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.header(
            text().color(NamedTextColor.DARK_AQUA).content("난이도 1~").decoration(TextDecoration.ITALIC, false)
                .decorate(
                    TextDecoration.BOLD
                )
                .append(
                    text().color(NamedTextColor.AQUA).content("속도")
                ).build()
        )
        tooltip.header(
            text().color(NamedTextColor.DARK_AQUA).content("난이도 9~").decoration(TextDecoration.ITALIC, false)
                .decorate(
                    TextDecoration.BOLD
                )
                .append(
                    text().color(NamedTextColor.AQUA).content("저항")
                ).build()
        )
        tooltip.header(
            text().color(NamedTextColor.DARK_AQUA).content("난이도 15~").decoration(TextDecoration.ITALIC, false).decorate(
                TextDecoration.BOLD
            )
                .append(
                    text().color(NamedTextColor.AQUA).content("포화")
                ).build()
        )
    }
}

class AbilityQuickWitted : ActiveAbility<AbilityConceptQuickWitted>(), Listener {
    companion object {
        fun createProblem(difficulty: Int): MathProblem {
            val expression = generateExpression(difficulty.coerceAtLeast(0), 0)
            return MathProblem(
                MathProblems.getByDifficulty(difficulty),
                "${expression.text} = ?",
                expression.value
            )
        }

        private fun generateExpression(difficulty: Int, depth: Int): GeneratedExpression {
            val baseTermCount = 2 + (difficulty / 6)
            val extraTerms = nextInt(0, ((difficulty+5) / 5).coerceAtMost(3))
            val termCount = (baseTermCount + extraTerms - depth).coerceAtLeast(2)
            val parenChance = ((difficulty * 0.02) - (extraTerms * 0.1)).coerceAtMost(0.5)
            val termMaxValue = (5 + difficulty).coerceAtMost(50)

            val terms = mutableListOf<GeneratedTerm>()
            repeat(termCount) {
                val useParen = depth < 2 && nextDouble() < parenChance
                if (useParen) {
                    val innerDifficulty = (difficulty / 2).coerceAtLeast(0)
                    val inner = generateExpression(innerDifficulty, depth + 1)
                    terms.add(GeneratedTerm("(${inner.text})", inner.value))
                } else {
                    val value = nextInt(1, termMaxValue + 1)
                    terms.add(GeneratedTerm(value.toString(), value))
                }
            }



            var expressionText = terms.first().text
            var totalValue = terms.first().value.toLong()
            var lastTerm = terms.first().value.toLong()
            for (i in 1 until terms.size) {
                val term = terms[i]
                val op = pickOperator(difficulty, totalValue, term.value)
                expressionText += " $op ${term.text}"
                when (op) {
                    "+" -> {
                        totalValue += term.value
                        lastTerm = term.value.toLong()
                    }
                    "-" -> {
                        totalValue -= term.value
                        lastTerm = -term.value.toLong()
                    }
                    else -> {
                        val multiplied = lastTerm * term.value
                        totalValue = totalValue - lastTerm + multiplied
                        lastTerm = multiplied
                    }
                }
            }

            return GeneratedExpression(
                expressionText,
                totalValue.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            )
        }

        private fun pickOperator(difficulty: Int, lhsValue: Long, rhsValue: Int): String {
            val lhsAbs = kotlin.math.abs(lhsValue)
            val rhsAbs = kotlin.math.abs(rhsValue.toLong())
            val magnitude = kotlin.math.max(lhsAbs, rhsAbs)

            val penalty = if (magnitude < 20) 0
                else (magnitude / 10).toInt() - 1

            val operators = mutableListOf("+")
            if (difficulty >= 4) operators.add("-")

            val baseMultiplyWeight = when {
                difficulty < 8 -> 0
                difficulty < 12 -> 1
                else -> difficulty / 6
            }

            val multiplyWeight = (baseMultiplyWeight - penalty).coerceAtLeast(0)
            repeat(multiplyWeight) { operators.add("*") }

            return operators[nextInt(operators.size)]
        }

        private data class GeneratedTerm(val text: String, val value: Int)
        private data class GeneratedExpression(val text: String, val value: Int)
    }

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    private var currentProblem: MathProblem? = null
    private var currentDifficulty = 0
    private var currentProblemTimeoutTask: TickerTask? = null

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        val player = event.player
        if (currentProblem != null) return player.sendActionBar(text("문제를 먼저 풀어야합니다.", NamedTextColor.RED))

        exhaust()

        val answerTimeLimitTime = concept.answerTimeLimitTicks / 20 + currentDifficulty / 3

        createProblem(currentDifficulty).let {
            currentProblem = it
            player.sendMessage(
                text().color(NamedTextColor.GOLD).content("문제: ").decorate(TextDecoration.BOLD)
                    .append(
                        text().color(NamedTextColor.WHITE).content(it.question).decoration(TextDecoration.BOLD, false)
                    )
                    .append(
                        text().color(NamedTextColor.GRAY)
                            .content(" (제한 시간: ${answerTimeLimitTime}s, 난이도: $currentDifficulty)")
                            .decoration(TextDecoration.BOLD, false)
                    )
                    .build()
            )
        }
        currentProblemTimeoutTask?.cancel()
        currentProblemTimeoutTask = psychic.runTask({
            if (currentProblem != null) {
                player.sendMessage(text("시간 초과! 난이도가 초기화됩니다.", NamedTextColor.RED))
                currentProblem = null
                currentDifficulty = 0
            }
        }, answerTimeLimitTime * 20)
    }

    @EventHandler
    fun onAnswer(event: AsyncChatEvent) {
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        message.toIntOrNull()?.let { answer ->
            currentProblem?.let { problem ->
                event.isCancelled = true

                val player = event.player
                currentProblemTimeoutTask?.cancel()
                currentProblemTimeoutTask = null
                if (problem.answer == answer) {
                    currentDifficulty += 1
                    player.sendMessage(text("정답을 맞췄습니다! (현재 난이도: $currentDifficulty)", NamedTextColor.GREEN))
                    psychic.runTask({
                        val effects = buildEffectsForDifficulty(currentDifficulty)
                        effects.forEach { effect -> player.addPotionEffect(effect) }
                    }, 0)
                } else {
                    player.sendMessage(text("틀렸습니다! 난이도가 초기화됩니다.", NamedTextColor.RED))
                    currentDifficulty = 0
                }
                currentProblem = null
            }
        }
    }

    private fun buildEffectsForDifficulty(difficulty: Int): List<PotionEffect> {
        val duration = (concept.durationTime / 50.0).toInt()
        val effects = mutableListOf<PotionEffect>()

        if (difficulty >= concept.speedStartDifficulty) {
            val speedAmplifier = ((difficulty - concept.speedStartDifficulty) / concept.speedAmplifierUpDifficultyInterval).coerceAtMost(concept.speedMaxAmplifier)
            effects.add(PotionEffect(PotionEffectType.SPEED, duration, speedAmplifier))
        }

        if (difficulty >= concept.resistanceStartDifficulty) {
            val resistanceAmplifier = ((difficulty - concept.resistanceStartDifficulty) / concept.resistanceAmplifierUpDifficultyInterval).coerceAtMost(concept.resistanceMaxAmplifier)
            effects.add(PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, duration, resistanceAmplifier))
        }

        if (difficulty >= concept.saturationStartDifficulty) {
            effects.add(PotionEffect(PotionEffectType.SATURATION, duration, 0))
        }

        return effects
    }

    enum class MathProblems(val effectType: PotionEffectType) {
        BASIC(PotionEffectType.SPEED),
        INTERMEDIATE(PotionEffectType.DAMAGE_RESISTANCE),
        ADVANCED(PotionEffectType.SATURATION);

        companion object {
            fun getByDifficulty(difficulty: Int) = when {
                difficulty > 8 -> ADVANCED
                difficulty > 5 -> INTERMEDIATE
                else -> BASIC
            }
        }
    }

    data class MathProblem(val problem: MathProblems, val question: String, val answer: Int)
}