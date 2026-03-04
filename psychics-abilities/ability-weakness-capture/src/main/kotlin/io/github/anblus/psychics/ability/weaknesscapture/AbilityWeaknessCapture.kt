package io.github.anblus.psychics.ability.weaknesscapture

import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.damage.psychicDamage
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.math.abs

// 허~점 포착
@Name("weakness-capture")
class AbilityConceptWeaknessCapture : AbilityConcept() {

    @Config
    var captureAngleDegrees = 70.0

    @Config
    var blindnessDuration = 3500L

    @Config
    var captureWidth = 6.0

    @Config
    var captureHeight = 4.0

    @Config
    var weaknessMultiplier = 2.0

    init {
        displayName = "허점 포착"
        type = AbilityType.ACTIVE
        cost = 25.0
        range = 24.0
        cooldownTime = 8000L
        castingTime = 1500L
        damage = Damage.of(DamageType.RANGED, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 3.5))
        description = listOf(
            text("능력 사용 시 포착 상태에 들어섭니다."),
            text("포착 상태에서는 전방의 적들을 포착합니다."),
            text("포착 종료 시 포착된 적들에게 능력 피해를 입힙니다."),
            text("포착 동안 한 번도 플레이어를 보지 않은 적은 실명과 더 큰 피해를 받습니다.")
        )
        wand = ItemStack(Material.STICK)
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(weaknessMultiplier) { NamedTextColor.RED to "허점 배수" to "배" }
    }
}

class AbilityWeaknessCapture : ActiveAbility<AbilityConceptWeaknessCapture>(), Listener {
    private var captureDirection: Vector? = null
    private var captureLocation: Location? = null

    private var captureReadyTick = 0

    private var capturedEntities = mutableListOf<CapturedEntity>()

    private data class CapturedEntity(
        val entity: LivingEntity,
        var isInCapture : Boolean = true,
        var hasLookedAtPlayer: Boolean = false
    )

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onChannel(channel: Channel) {
        val player = esper.player

        if (captureDirection == null) {
            captureDirection = player.eyeLocation.direction
            captureLocation = player.eyeLocation
            captureReadyTick = 0
        }

        if (captureReadyTick-- <= 0) {
            captureReadyTick = (channel.remainingTime / 50 * 0.2).toInt().coerceAtLeast(2)
            captureEntities(
                captureLocation!!,
                captureDirection!!
            )
            makeCaptureEffect(
                captureLocation!!,
                captureDirection!!,
                true,
                channel.remainingTime
            )
        }

        player.addPotionEffect(
            PotionEffect(
                PotionEffectType.SLOWNESS, 2, 4, false, false, false
            )
        )

    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        val player = event.player
        if (!psychic.consumeMana(concept.cost)) return player.sendActionBar(TestResult.FailedCost.message(this))
        cooldownTime = concept.cooldownTime

        makeCaptureEffect(
            captureLocation!!,
            captureDirection!!,
            false
        )

        capturedEntities
            .filter { it.entity.isValid && !it.entity.isDead && it.isInCapture }
            .forEach { captured ->
                val damage = concept.damage
                val damageType = damage?.type ?: DamageType.RANGED
                val damageAmount = damage?.stats?.let { esper.getStatistic(it) } ?: 0.0

                captured.entity.psychicDamage(
                    this,
                    damageType,
                    damageAmount * (concept.weaknessMultiplier.takeIf { !captured.hasLookedAtPlayer } ?: 1.0),
                    player,
                    player.location,
                    concept.knockback
                )

                if (!captured.hasLookedAtPlayer) {
                    captured.entity.addPotionEffect(
                        PotionEffect(PotionEffectType.BLINDNESS, (concept.blindnessDuration / 50L).toInt(), 0)
                    )
                }
            }

        captureDirection = null
        captureLocation = null
        capturedEntities.clear()
    }

    private fun makeCaptureEffect(start: Location, direction: Vector, isChannel: Boolean, channelTime: Long = 0L) {
        val player = esper.player
        val world = start.world
        val (forward, right, up) = buildBasis(direction)

        val halfW = concept.captureWidth / 2.0
        val halfH = concept.captureHeight / 2.0
        val depthStep = 4.0
        val edgeStep = 0.5

        val playParticle: (Location) -> Unit = { loc ->
            if (isChannel) {
                player.spawnParticle(Particle.INSTANT_EFFECT, loc, 1, 0.0, 0.0, 0.0, 0.0, Particle.Spell(Color.WHITE, 1.0f))
            } else {
                world.spawnParticle(Particle.END_ROD, loc, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }

       val offsets = mutableListOf<Vector>()

       // 한 번만 엣지 오프셋 계산
       var w = -halfW
       while (w <= halfW) {
           offsets.add(right.clone().multiply(w).add(up.clone().multiply(halfH)))
           offsets.add(right.clone().multiply(w).add(up.clone().multiply(-halfH)))
           w += edgeStep
       }

       var h = -halfH
       while (h <= halfH) {
           offsets.add(right.clone().multiply(halfW).add(up.clone().multiply(h)))
           offsets.add(right.clone().multiply(-halfW).add(up.clone().multiply(h)))
           h += edgeStep
       }

       var depth = concept.range % depthStep
       while (depth <= concept.range) {
           val center = start.clone().add(forward.clone().multiply(depth))
           offsets.forEach { off ->
               playParticle(center.clone().add(off))
           }
           depth += depthStep
       }

        if (isChannel) {
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6F, 1.8F - (0.8 * channelTime / concept.castingTime).toFloat())
        } else {
            world.playSound(player.location, Sound.BLOCK_WOODEN_DOOR_OPEN, 2.0F, 2.0F)
        }

        if (!isChannel) return

        capturedEntities.forEach { captured ->
            if (!captured.isInCapture) return@forEach
            val head = captured.entity.eyeLocation.clone().add(0.0, 0.5, 0.0)

            val option = if (captured.hasLookedAtPlayer)
                Particle.DustOptions(Color.fromRGB(80, 255, 80), 2.5f)
            else
                Particle.DustOptions(Color.fromRGB(255, 80, 80), 2.5f)

            player.spawnParticle(Particle.DUST, head, 1, 0.2, 0.2, 0.2, 0.1, option)
        }
    }

    private fun captureEntities(start: Location, direction: Vector) {
        val player = esper.player
        val world = start.world
        val (forward, right, up) = buildBasis(direction)

        val halfW = concept.captureWidth / 2.0
        val halfH = concept.captureHeight / 2.0
        val range = concept.range

        val existing = capturedEntities.associateBy { it.entity.uniqueId }

        val foundIds = mutableSetOf<java.util.UUID>()

        world.getNearbyEntities(start, range, range, range) { entity ->
            entity is LivingEntity && player.hostileFilter().test(entity)
        }.forEach { entity ->
            val living = entity as LivingEntity
            val rel = living.boundingBox.center.subtract(start.toVector())
            val forwardDist = rel.dot(forward)
            if (forwardDist <= 0.0 || forwardDist > range) return@forEach
            val rightDist = abs(rel.dot(right))
            val upDist = abs(rel.dot(up))
            if (rightDist > halfW || upDist > halfH) return@forEach

            val existingEntry = existing[living.uniqueId]
            if (existingEntry != null) {
                existingEntry.isInCapture = true
                foundIds.add(living.uniqueId)
                return@forEach
            }

            capturedEntities.add(CapturedEntity(living))
            foundIds.add(living.uniqueId)
        }

        existing.forEach { (uuid, entry) ->
            if (!foundIds.contains(uuid) && entry.isInCapture) {
                entry.isInCapture = false
            }
        }

        capturedEntities.forEach { entry ->
            val living = entry.entity
            if (!entry.isInCapture || entry.hasLookedAtPlayer) return@forEach

            val toPlayer = player.eyeLocation.toVector().subtract(living.eyeLocation.toVector()).normalize()
            val entityDir = living.eyeLocation.direction.normalize()
            val angle = entityDir.angle(toPlayer)
            val threshold = Math.toRadians(concept.captureAngleDegrees / 2.0)
            if (angle <= threshold) entry.hasLookedAtPlayer = true
        }
    }

    private fun buildBasis(forward: Vector): Triple<Vector, Vector, Vector> {
        var right = Vector(0.0, 1.0, 0.0).crossProduct(forward)
        if (right.lengthSquared() == 0.0) {
            right = Vector(0.0, 0.0, 1.0).crossProduct(forward)
        }
        right.normalize()

        val up = forward.clone().crossProduct(right).normalize()
        return Triple(forward, right, up)
    }
}
