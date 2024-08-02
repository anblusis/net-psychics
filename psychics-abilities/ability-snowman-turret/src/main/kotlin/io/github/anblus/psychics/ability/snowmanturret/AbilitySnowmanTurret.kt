package io.github.monun.psychics.ability.tntblast
/*
import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.fake.FakeEntity
import io.github.monun.tap.fake.Movement
import io.github.monun.tap.fake.Trail
import io.github.monun.tap.math.normalizeAndLength
import net.kyori.adventure.text.Component.text
import org.bukkit.*
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector

@Name("tnt-blast")
class AbilityConceptTNTBlast : AbilityConcept() {
    @Config
    var tntSpeed = 2.0

    @Config
    var manaPerSecond = 10.0

    init {
        type = AbilityType.ACTIVE
        range = 64.0
        cooldownTime = 100L
        damage = Damage.of(DamageType.BLAST, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 1.0))
        wand = ItemStack(Material.STICK)

        listOf(
            text("TNT를 발사하여 피해를 입힙니다.")
        )
    }
}

class AbilityTNTBlast : Ability<AbilityConceptTNTBlast>(), Listener {
    private var charging = false
    private var chargedMana = 0.0

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = event.player

        if (event.action == Action.LEFT_CLICK_AIR || event.action == Action.LEFT_CLICK_BLOCK) {
            event.item?.let { item ->
                if (item.type == Material.STICK) {
                    if (!charging) {
                        charging = true
                        chargedMana = 0.0
                        player.addPotionEffect(PotionEffect(PotionEffectType.SLOW, Int.MAX_VALUE, 5, false, false, false))
                        psychic.runTaskTimer({
                            if (charging) {
                                if (psychic.consumeMana(concept.manaPerSecond)) {
                                    chargedMana += concept.manaPerSecond
                                } else {
                                    releaseTNT(player)
                                }
                            }
                        }, 0L, 20L)
                    } else {
                        releaseTNT(player)
                    }
                }
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        if (event.player == esper.player) {
            releaseTNT(event.player)
        }
    }

    private fun releaseTNT(player: Player) {
        if (charging) {
            charging = false
            player.removePotionEffect(PotionEffectType.SLOW)

            val location = player.eyeLocation
            val projectile = TNTProjectile(chargedMana).apply {
                tnt = this@AbilityTNTBlast.psychic.spawnFakeEntity(location, ArmorStand::class.java).apply {
                    updateMetadata<ArmorStand> {
                        isVisible = false
                        isMarker = true
                    }
                    updateEquipment {
                        helmet = ItemStack(Material.TNT)
                    }
                }
            }

            psychic.launchProjectile(location, projectile)
            projectile.velocity = location.direction.multiply(concept.tntSpeed)

            val loc = player.location
            loc.world.playSound(loc, Sound.ENTITY_TNT_PRIMED, 1.0F, 1.0F)
        }
    }

    inner class TNTProjectile(private val mana: Double) : PsychicProjectile(1200, concept.range) {
        lateinit var tnt: FakeEntity

        override fun onMove(movement: Movement) {
            tnt.moveTo(movement.to.clone().apply { y -= 1.62 })
        }

        override fun onTrail(trail: Trail) {
            trail.velocity?.let { v ->
                val length = v.normalizeAndLength()

                if (length > 0.0) {
                    val start = trail.from
                    val world = start.world

                    world.rayTrace(
                        start, v, length, FluidCollisionMode.NEVER, true, 0.5,
                        TargetFilter(esper.player)
                    )?.let { result ->
                        remove()

                        val hitLocation = result.hitPosition.toLocation(world)
                        val explosionPower = mana / concept.manaPerSecond
                        world.createExplosion(hitLocation, explosionPower.toFloat(), false, false)

                        result.hitEntity?.let { entity ->
                            if (entity is LivingEntity) {
                                entity.psychicDamage(knockback = 0.0)
                            }
                        }

                        world.playSound(hitLocation, Sound.ENTITY_GENERIC_EXPLODE, 2.0F, 1.0F)
                    }
                }
            }
        }

        override fun onRemove() {
            tnt.remove()
        }
    }
}
 */