package io.github.anblus.psychics.ability.heavenlystrike

import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.fake.FakeEntity
import io.github.monun.tap.task.TickerTask
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.math.cos
import kotlin.math.sin
import org.bukkit.util.Vector
import java.util.UUID

// UFO다!!
@Name("heavenly-strike")
class AbilityConceptHeavenlyStrike : AbilityConcept() {

    @Config
    val attackIntervalTick = 40

    @Config
    val maxRingRadius = 3.0

    @Config
    val firstRingRadius = 1.0

    @Config
    val ringInterval = 2.5

    @Config
    val knockbackStrength = 1.0

    init {
        displayName = "UFO"
        type = AbilityType.ACTIVE
        cooldownTime = 15000L
        range = 16.0
        cost = 12.0
        damage = Damage.of(DamageType.RANGED, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 4.0))
        description = listOf(
            text("능력 사용 시 중력이 사라지고 주기적으로 아래로 파동을 발사합니다."),
            text("파동에 닿은 적들은 피해와 함께 공중 부양 효과를 받습니다."),
            text("능력 발동 중 다시 아이템 클릭 시 발동이 취소됩니다.")
        )
        wand = ItemStack(Material.FEATHER)
    }
}

class AbilityHeavenlyStrike : ActiveAbility<AbilityConceptHeavenlyStrike>(), Listener {
    private var isActive = false
    private lateinit var task: TickerTask
    private val floors: ArrayList<FakeEntity<ArmorStand>> = ArrayList()
    private val victims: ArrayList<UUID> = ArrayList()
    private var locationOffset: Vector = Vector(0, 0, 0)

    override fun onEnable() {
        psychic.registerEvents(this)

        psychic.runTaskTimer({
            if (isActive) {
                val player = esper.player

                locationOffset = player.location.subtract(locationOffset).toVector()

                floors.forEach { floor ->
                    floor.moveTo(player.location.clone().add(locationOffset.clone().multiply(3)).add(floor.location.direction.clone().multiply(0.4)).apply {
                        y -= 1.2
                        yaw = floor.location.yaw
                        pitch = 0f
                    })
                }
                locationOffset = player.location.toVector()
            }
        }, 0L, 1L)
    }

    override fun onDisable() {
        cancel(false)
    }

    private fun cancel(withEffect: Boolean) {
        val player = esper.player

        if (withEffect) {
            val world = player.world
            world.playSound(player.location, Sound.ENTITY_DOLPHIN_ATTACK, 2.0f, 2.0f)
            world.spawnParticle(Particle.WAX_ON, player.location, 8, 0.5, 1.0, 0.5, 0.2)
        }

        isActive = false
        task.cancel()
        floors.forEach { it.remove() }
        floors.clear()
        cooldownTime = concept.cooldownTime
        player.setGravity(true)
    }

    private fun launchWave() {
        val player = esper.player
        val baseLocation = player.location
        val world = player.world
        val radiusIncrement = (concept.maxRingRadius - concept.firstRingRadius) / (concept.range - 1.0)
        var ringInterval = concept.ringInterval

        world.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 0.7f, 2.0f)

        victims.clear()

        for (yOffset in 1..concept.range.toInt()) {
            ringInterval ++
            if (ringInterval < concept.ringInterval) continue
            ringInterval -= concept.ringInterval
            psychic.runTask({
                val currentRadius = concept.firstRingRadius + radiusIncrement * (yOffset - 1)
                val location = baseLocation.clone().apply { y -= yOffset }

                for (theta in 0..360 step 10) {
                    val radians = Math.toRadians(theta.toDouble())
                    val x = cos(radians)
                    val z = sin(radians)

                    world.spawnParticle(
                        Particle.END_ROD,
                        location.clone().add(x * currentRadius, 0.0, z * currentRadius),
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                    )
                }

                val entities = world.getNearbyEntities(
                    location,
                    currentRadius,
                    concept.ringInterval,
                    currentRadius
                )

                for (entity in entities) {
                    if (entity is LivingEntity && player.hostileFilter().test(entity) && !victims.contains(entity.uniqueId)) {
                        entity.addPotionEffect(PotionEffect(PotionEffectType.LEVITATION, concept.attackIntervalTick * 2, 0))
                        entity.psychicDamage()
                        entity.velocity.add(Vector(0.0, concept.knockbackStrength, 0.0))
                        victims.add(entity.uniqueId)
                    }
                }
            }, yOffset.toLong())
        }
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        if (isActive) {
            cancel(true)
            return
        }

        val player = esper.player
        val world = player.world
        locationOffset = player.location.toVector()
        isActive = true

        task = psychic.runTaskTimer({
            if (!psychic.consumeMana(concept.cost))  {
                cancel(true)
                return@runTaskTimer
            }
            launchWave()
        }, 0L, concept.attackIntervalTick.toLong())

        floors.clear()
        repeat(5) { i ->
            floors.add(
                psychic.spawnFakeEntity(player.location.clone().apply { yaw = (360 / 5 * i).toFloat(); pitch = 0f }, ArmorStand::class.java).apply {
                    updateMetadata {
                        isVisible = false
                    }
                    updateEquipment {
                        helmet = ItemStack(Material.IRON_BLOCK)
                    }
                }
            )
        }

        player.sendMessage(text("UFO 탑승!").color(NamedTextColor.GOLD))
        player.setGravity(false)

        world.playSound(player.location, Sound.BLOCK_BREWING_STAND_BREW, 2.0f, 2.0f)
    }
}
