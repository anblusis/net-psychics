package io.github.anblus.psychics.ability.shockback

import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.fake.FakeEntity
import io.github.monun.tap.fake.Movement
import io.github.monun.tap.fake.Trail
import io.github.monun.tap.math.normalizeAndLength
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.ItemStack
import kotlin.math.PI
import kotlin.random.Random.Default.nextDouble

// 전방에 마나 산탄 발사 후 후퇴
@Name("shockback")
class AbilityConceptShockback : AbilityConcept() {

    @Config
    val pelletSpeed = 1.7

    @Config
    val pelletWiggle = 0.8

    @Config
    val pelletCount = 15

    @Config
    val selfKnockback = 0.9

    @Config
    val fallDamageMultiplier = 0.5
    
    init {
        displayName = "쇼크백"
        type = AbilityType.ACTIVE
        cost = 25.0
        range = 8.0
        knockback = 0.4
        cooldownTime = 5000L
        castingTime = 500L
        damage = Damage.of(DamageType.RANGED, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 0.3))
        description = listOf(
            text("전방에 마나 산탄을 발사하여 적에게 피해를 주고, 뒤로 밀려납니다."),
            text("능력 사용 후 떨어질 때 받는 피해가 감소합니다.")
        )
        wand = ItemStack(Material.AMETHYST_SHARD)
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(pelletCount) { NamedTextColor.GREEN to "탄 수" to "발" }
        tooltip.stats(fallDamageMultiplier) { NamedTextColor.RED to "낙하 피해량" to "배" }
    }
}

class AbilityShockback : ActiveAbility<AbilityConceptShockback>(), Listener {
    private var abilityFall = false

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onChannel(channel: Channel) {
        val location = esper.player.eyeLocation.clone().apply { y += 1.0 }

        location.world.spawnParticle(
            Particle.ELECTRIC_SPARK,
            location,
            4,
            0.5,
            0.0,
            0.5,
            0.03
        )
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        exhaust()

        val player = event.player
        val location = player.location.apply { y += 0.75 }.add(player.location.direction.multiply(0.5))
        val pelletList = mutableListOf<ManaPelletProjectile>()

        val count = concept.pelletCount.coerceAtLeast(1)
        for (i in 0 until count) {
            pelletList.add(ManaPelletProjectile().apply {
                pellet =
                    this@AbilityShockback.psychic.spawnFakeEntity(location.clone().apply { y -= 1.62 }, ArmorStand::class.java).apply {
                        updateMetadata {
                            isVisible = false
                            isMarker = true
                        }
                        updateEquipment {
                            helmet = ItemStack(Material.TUBE_CORAL)
                        }
                    }
            })
        }
        val wiggle = concept.pelletWiggle
        for (i in 0 until count) {
            psychic.launchProjectile(location, pelletList[i])
            pelletList[i].velocity = location.direction.apply {
                x += nextDouble(wiggle) - wiggle / 2.0
                y += nextDouble(wiggle) - wiggle / 2.0
                z += nextDouble(wiggle) - wiggle / 2.0
            }.multiply(concept.pelletSpeed)
        }

        val frontDirection = player.location.direction.clone().apply { y -= (0.2 + player.location.direction.y).coerceIn(0.0, 0.2) }
        player.velocity = frontDirection.clone().multiply(-concept.selfKnockback)
        psychic.runTask({
            player.velocity = frontDirection.clone().multiply(-concept.selfKnockback)
        }, 1L)

        val loc = player.location
        loc.world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.0F, 2.0F)

        abilityFall = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onFallDamage(e: EntityDamageEvent) {
        if (e.cause != EntityDamageEvent.DamageCause.FALL) return
        if (abilityFall) {
            e.damage *= concept.fallDamageMultiplier
            abilityFall = false
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerMove(e: PlayerMoveEvent) {
        if (!abilityFall) return
        val to = e.to
        val below = to.clone().add(0.0, -0.1, 0.0).block
        if (below.type.isSolid) {
            psychic.runTask({ abilityFall = false }, 10L)
        }
    }

    inner class ManaPelletProjectile : PsychicProjectile(1200, concept.range) {
        lateinit var pellet: FakeEntity<ArmorStand>
        private val randomYaw = (nextDouble() * 360.0).toFloat()
        private val randomPitch = (nextDouble() * 180.0).toFloat()

        override fun onMove(movement: Movement) {
            pellet.moveTo(movement.to.clone().apply { yaw = randomYaw; pitch = randomPitch;  y -= 1.62 })
        }

        override fun onTrail(trail: Trail) {
            trail.velocity?.let { velocity ->
                val from = trail.from
                val length = velocity.normalizeAndLength()
                val world = from.world

                world.rayTrace(
                    from,
                    velocity,
                    length,
                    FluidCollisionMode.NEVER,
                    true,
                    0.1,
                    TargetFilter(esper.player)
                )?.hitEntity?.let { entity ->
                    if (entity is LivingEntity) {
                        if (entity is Player && entity.isBlocking && velocity.angle(entity.eyeLocation.direction) > PI / 2.0) {
                            world.playSound(
                                entity.location,
                                Sound.ITEM_SHIELD_BLOCK,
                                SoundCategory.BLOCKS,
                                1.0F,
                                1.0F
                            )
                        } else {
                            entity.psychicDamage()
                            world.playSound(
                                entity.location,
                                Sound.BLOCK_AMETHYST_BLOCK_BREAK,
                                0.7F,
                                2.0F
                            )
                        }
                    }
                }
            }
        }

        override fun onRemove() {
            pellet.remove()
        }
    }
}
