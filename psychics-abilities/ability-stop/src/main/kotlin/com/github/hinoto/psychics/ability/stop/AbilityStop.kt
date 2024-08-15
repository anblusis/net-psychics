package com.github.hinoto.psychics.ability.stop

import io.github.monun.psychics.Ability
import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.TestResult
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import org.bukkit.Bukkit
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

@Name("stop")
class AbilityConceptStop : AbilityConcept() {
    @Config
    var ticks : Int = 20*3

    init {
        displayName = "멈춰!"
        type = AbilityType.PASSIVE
        description = listOf(
            text("상대가 폭력을 행사할 경우, '멈춰!'를 외치고"),
            text("상대에게 폭력의 대가로 디버프를 겁니다.")
        )
        cooldownTime = 60000L
    }
}

class AbilityStop : Ability<AbilityConceptStop>(), Listener {
    override fun onEnable() {
        psychic.registerEvents(StopListener())
    }

    inner class StopListener : Listener{
        @EventHandler
        fun onPlayerDamaged(event: EntityDamageByEntityEvent) {
            val result = test()
            if (result == TestResult.Success) {
                if (event.damager.type == EntityType.PLAYER &&
                    !event.damager.isDead) {
                    val damager = event.damager as LivingEntity
                    esper.player.sendActionBar(text("멈춰!"))
                    damager.sendActionBar(text("${esper.player}: 멈춰!"))
                    damager.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, concept.ticks, 4))
                    damager.addPotionEffect(PotionEffect(PotionEffectType.SLOW, concept.ticks, 4))
                    damager.addPotionEffect(PotionEffect(PotionEffectType.SLOW_DIGGING, concept.ticks, 4))
                    damager.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, concept.ticks, 4))
                    damager.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, concept.ticks, 4))
                    cooldownTime = concept.cooldownTime
                }
            }
        }
    }
}