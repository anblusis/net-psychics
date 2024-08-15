package io.github.hou.psychics.ability.poison

import io.github.monun.psychics.Ability
import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.event.EntityProvider
import io.github.monun.tap.event.TargetEntity
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.random.Random.Default.nextDouble

// 독을걸래요
@Name("poison")
class AbilityConceptPoison : AbilityConcept() {

    @Config
    var givedamage = 0.5

    @Config
    var poisonchance = 0.2

    @Config
    var maxpoisonlevel = 5

    init {
        displayName = "중독"
        type = AbilityType.PASSIVE
        cost = 0.0
        cooldownTime = 1000L
        description = listOf(
            text("체력이 증가하지만 공격력이 감소합니다."),
            text("적을 공격하거나, 적에게 공격 받으면 일정 확률로"),
            text("상대에게 독 효과를 부여합니다."),
            text("이미 독이 걸린 상대에게 또 다시 능력이 발동하면"),
            text("독의 효과와 지속시간이 증가합니다.")
        )

    }
    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(poisonchance * 100) { NamedTextColor.GREEN to "중독 확률" to "%" }
        tooltip.stats(maxpoisonlevel) { NamedTextColor.DARK_GREEN to "최대 중첩" to "레벨" }
    }

}

class AbilityPoison : Ability<AbilityConceptPoison>(), Listener {

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (event.damager is LivingEntity) {
            if (nextDouble() < concept.poisonchance) {
                if (cooldownTime <= 0L) {
                    val location = event.damager.location.apply { y += 0.5 }
                    event.damager.world.spawnParticle(
                        Particle.DUST_COLOR_TRANSITION,
                        location.x,
                        location.y,
                        location.z,
                        32,
                        0.6,
                        1.0,
                        0.6,
                        Particle.DustTransition(Color.fromRGB(0, 194, 52), Color.fromRGB(0, 184, 49), 1.0f)
                    )
                    event.damager.world.playSound(
                        event.damager.location,
                        Sound.ENTITY_SPIDER_DEATH,
                        SoundCategory.MASTER,
                        2.0F,
                        0.8F
                    )
                    val potion = (event.damager as LivingEntity).getPotionEffect(PotionEffectType.POISON)

                    if (potion == null) {
                        (event.damager as LivingEntity).addPotionEffect(
                            PotionEffect(
                                PotionEffectType.POISON, 160, 0, false, true
                            )
                        )
                    } else {
                        val potion2 = potion.amplifier

                        if (potion2 < concept.maxpoisonlevel - 1) {
                            (event.damager as LivingEntity).addPotionEffect(
                                PotionEffect(
                                    PotionEffectType.POISON, potion.duration + 80, potion.amplifier + 1, false, true
                                )
                            )
                        } else {
                            (event.damager as LivingEntity).addPotionEffect(
                                PotionEffect(
                                    PotionEffectType.POISON, potion.duration + 80, potion.amplifier, false, true
                                )
                            )
                        }
                    }
                    cooldownTime = concept.cooldownTime
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    @TargetEntity(EntityProvider.EntityDamageByEntity.Damager::class)
    fun onAttack(event: EntityDamageByEntityEvent) {
        if (event.entity is LivingEntity) {
            event.damage *= (concept.givedamage)
            if (nextDouble() < concept.poisonchance) {
                if (cooldownTime <= 0L) {
                    val location = event.entity.location.apply { y += 0.5 }
                    event.entity.world.spawnParticle(
                        Particle.DUST_COLOR_TRANSITION,
                        location.x,
                        location.y,
                        location.z,
                        32,
                        0.6,
                        1.0,
                        0.6,
                        Particle.DustTransition(Color.fromRGB(0, 194, 52), Color.fromRGB(0, 184, 49), 1.0f)
                    )
                    event.entity.world.playSound(
                        event.entity.location,
                        Sound.ENTITY_SPIDER_DEATH,
                        SoundCategory.MASTER,
                        2.0F,
                        0.8F
                    )
                    val potion = (event.entity as LivingEntity).getPotionEffect(PotionEffectType.POISON)

                    if (potion == null) {
                        (event.entity as LivingEntity).addPotionEffect(
                            PotionEffect(
                                PotionEffectType.POISON, 200, 0, false, true
                            )
                        )
                    } else {
                        val potion2 = potion.amplifier

                        if (potion2 < concept.maxpoisonlevel) {
                            (event.entity as LivingEntity).addPotionEffect(
                                PotionEffect(
                                    PotionEffectType.POISON, potion.duration + 50, potion.amplifier + 1, false, true
                                )
                            )
                        } else {
                            (event.entity as LivingEntity).addPotionEffect(
                                PotionEffect(
                                    PotionEffectType.POISON, potion.duration + 50, potion.amplifier, false, true
                                )
                            )
                        }
                    }
                    cooldownTime = concept.cooldownTime
                }
            }
        }
    }
}