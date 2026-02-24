package io.github.anblus.psychics.ability.burningmana

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.TestResult
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack

// 마나를 불로
@Name("burning-mana")
class AbilityConceptBurningMana : AbilityConcept() {

    @Config
    val fireSecondPerOneMana = 0.15

    @Config
    val minCost = 10.0

    init {
        displayName = "마나 태우기"
        type = AbilityType.ACTIVE
        cooldownTime = 3000L
        range = 156.0
        description = listOf(
            text("상대를 바라보고 능력을 사용 시 현재 마나의 양에 따라"),
            text("상대를 불태웁니다.")
        )
        wand = ItemStack(Material.FIRE_CHARGE)

    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(minCost) { NamedTextColor.DARK_AQUA to "최소 마나 소모량" to "" }
        tooltip.stats(fireSecondPerOneMana) { NamedTextColor.DARK_RED to "마나당 화염 시간" to "초" }
    }
}

class AbilityBurningMana : ActiveAbility<AbilityConceptBurningMana>(), Listener {
    override fun onInitialize() {
        targeter = {
            val player = esper.player
            val start = player.eyeLocation
            val world = start.world

            world.rayTrace(
                start,
                start.direction,
                concept.range,
                FluidCollisionMode.NEVER,
                true,
                0.5,
                player.hostileFilter()
            )?.hitEntity
        }
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        if (target !is LivingEntity) return

        if (psychic.mana < concept.minCost) {
            esper.player.sendMessage(TestResult.FailedCost.message(this))
            return
        }

        val mana = psychic.mana
        psychic.mana = 0.0
        target.fireTicks += 20 + ((mana * concept.fireSecondPerOneMana) * 20).toInt()
        cooldownTime = concept.cooldownTime
        target.world.spawnParticle(Particle.LAVA, target.location, 16, 0.4, 1.0, 0.4, 0.0)
        target.world.playSound(
            target.location,
            Sound.ITEM_FLINTANDSTEEL_USE,
            SoundCategory.PLAYERS,
            2.0F,
            0.1F
        )
    }

}






