package io.github.godlegendshot.psychics.ability.reviveattack

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

//부활 시 강인한 사신의 힘으로 초월적으로 공격합니다
@Name("revive-attack")
class AbilityConceptReviveAttack : AbilityConcept() {

    @Config
    var timer = 60000L

    @Config
    var strengthAmplifier = 10

    @Config
    var speedAmplifier = 4

    @Config
    var witherAmplifier = 4

    init {
        type = AbilityType.ACTIVE
        displayName = "불사의 공격"
        wand = ItemStack(Material.STICK)
        cost = 40.0
        durationTime = 10000L
        cooldownTime = 95000L
        description = listOf(
            text("복수하고 싶다는 일념으로"),
            text("일시적으로 부활할 수 있습니다."),
            text("상대에게 복수를 선사하세요!"),
            text("※주의※ 해당 능력은 시전 중 데미지를 입습니다.").color(NamedTextColor.RED)
        )
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.header(
            text(
                "부활 후 버프 지속 시간 "
            ).color(
                NamedTextColor.AQUA
            ).decoration(
                TextDecoration.ITALIC, false
            ).decoration(
                TextDecoration.BOLD, true
            ).append(
                text(
                    (
                        timer/100L
                            ).toInt().toString()).decoration(
                    TextDecoration.BOLD, false
                ).color(
                    NamedTextColor.WHITE
                )
            ).append(
                text().content(
                    "초"
                ).decoration(
                    TextDecoration.BOLD, false
                ).color(
                    NamedTextColor.WHITE
                )
            )
        )
        tooltip.header(
            text(
                "부활 시 힘 "
            ).color(
                NamedTextColor.DARK_RED
            ).decoration(
                TextDecoration.ITALIC, false
            ).decoration(
                TextDecoration.BOLD, true
            ).append(
                text(
                    (
                        strengthAmplifier + 1
                            ).toString()
                ).decoration(
                    TextDecoration.BOLD, false
                ).color(
                    NamedTextColor.WHITE
                )
            )
        )

        tooltip.header(
            text(
                "부활 시 위더 "
            ).color(
                NamedTextColor.BLACK
            ).decoration(
                TextDecoration.ITALIC, false
            ).decoration(
                TextDecoration.BOLD, true
            ).append(
                text(
                        (
                            witherAmplifier + 1
                                ).toString()
                ).decoration(
                    TextDecoration.BOLD, false
                ).color(
                    NamedTextColor.WHITE
                )
            )
        )

        tooltip.header(
            text(
                "부활 시 신속 "
            ).color(
                NamedTextColor.WHITE
            ).decoration(
                TextDecoration.ITALIC, false
            ).decoration(
                TextDecoration.BOLD, true
            ).append(
                text(
                    (
                        speedAmplifier + 1
                            ).toString()
                ).decoration(
                    TextDecoration.BOLD, false
                ).color(
                    NamedTextColor.WHITE
                )
            )
        )
    }

}

class AbilityReviveAttack : ActiveAbility<AbilityConceptReviveAttack>(), Listener {
    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?){
        val player = esper.player
        val location = player.location
        player.playSound(location,Sound.ENTITY_ENDER_DRAGON_GROWL,1.0F,1.0F)
        val concept = concept
        durationTime = concept.durationTime
        cooldownTime = concept.cooldownTime
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val ticks = (concept.timer / 50L).toInt()
        val player = esper.player
        val location = player.location
        if (durationTime > 0L) {
            event.isCancelled = true
            val potion1 = PotionEffect (
                PotionEffectType.INCREASE_DAMAGE,
                ticks,
                concept.strengthAmplifier,
                true,
                true,
                true
            )
            val potion2 = PotionEffect (
                PotionEffectType.SPEED,
                ticks, concept.speedAmplifier,
                true,
                true,
                true
            )
            val potion3 = PotionEffect(
                PotionEffectType.WITHER,
                2147483647,
                concept.witherAmplifier,
                true,
                true,
                true
            )
            durationTime = 0L
            player.addPotionEffect(potion1)
            player.addPotionEffect(potion2)
            player.addPotionEffect(potion3)
            player.sendActionBar(text("내 모든것을 불태우겠다!!").color(NamedTextColor.RED))
            player.playSound(location,Sound.ENTITY_ENDER_DRAGON_GROWL,1.0F,1.0F)
        }

    }
}
