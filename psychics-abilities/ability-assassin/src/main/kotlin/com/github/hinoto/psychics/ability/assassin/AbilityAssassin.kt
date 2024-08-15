package com.github.hinoto.psychics.ability.assassin

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.item.isPsychicbound
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

@Name("assassin")
class AbilityConceptAssassin : AbilityConcept() {

    val wandItem = ItemStack(Material.IRON_SWORD).apply {
        val meta = itemMeta
        meta.isUnbreakable = true
        meta.isPsychicbound = true
        itemMeta = meta
    }

    init {
        displayName = "어쌔신"
        description = listOf(
            Component.text("상대의 뒤로 이동하며"),
            Component.text("일시적으로 고정하며 피해를 줍니다.")
        )
        cooldownTime = 10000L
        range = 10.0
        cost = 50.0
        durationTime = 3000
        damage = Damage.of(DamageType.MELEE, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 2.0))
        knockback = 0.0
        wand = wandItem
        supplyItems = listOf(
            wandItem
        )
    }
}

class AbilityAssassin : ActiveAbility<AbilityConceptAssassin>(), Listener {
    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        val world = event.player.world
        val loc = event.player.eyeLocation

        world.rayTrace(loc, loc.direction, concept.range, FluidCollisionMode.NEVER,
        true, 0.1, TargetFilter(esper.player))?.let { result ->
            result.hitEntity?.let { entity ->
                if (entity is LivingEntity) {
                    entity.psychicDamage()
                    entity.addPotionEffects(listOf(
                            PotionEffect(PotionEffectType.SLOW,
                                (concept.durationTime/1000*20).toInt(), 10)
                        )
                    )
                    world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_FLAP, 1f, 2f)
                    world.spawnParticle(Particle.DAMAGE_INDICATOR, entity.location, 10)
                    event.player.teleport(entity.location.subtract(entity.location.direction))
                    cooldownTime = concept.cooldownTime
                    psychic.consumeMana(concept.cost)
                }
            }
        }
    }
}