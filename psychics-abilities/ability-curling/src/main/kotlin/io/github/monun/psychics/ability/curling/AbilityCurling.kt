package io.github.monun.psychics.ability.curling

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.PsychicProjectile
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.config.RangeDouble
import io.github.monun.tap.fake.Movement
import io.github.monun.tap.fake.Trail
import net.kyori.adventure.text.Component.text
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.util.UUID

@Name("curling")
class AbilityConceptCurling : AbilityConcept() {
    @Config
    var dropSpeed = 0.9

    @Config
    var slideSpeed = 0.95

    @Config
    var gravity = 0.05

    @Config
    @RangeDouble(0.0, 1.0)
    var groundFrictionMultiplier = 1.0

    @Config
    var bounceMultiplier = 0.85

    @Config
    var stoneRadius = 0.45

    @Config
    var damageRange = 2.0

    @Config
    var stoneDurationTicks = 200

    @Config
    var hitStackDurationTicks = 40

    init {
        displayName = "컬링"
        type = AbilityType.ACTIVE
        cooldownTime = 3000L
        cost = 30.0
        range = 20.0
        knockback = 3.0
        damage = Damage.of(DamageType.RANGED, EsperAttribute.ATTACK_DAMAGE to 5.0)
        description = listOf(
            text("전방에 컬링 스톤을 떨어뜨려 미끄러뜨립니다."),
            text("스톤이 바닥, 벽, 적, 다른 스톤에 부딪히면 주변 적을 타격합니다."),
            text("짧은 시간 안에 3회 타격된 대상은 빙결됩니다.")
        )
        wand = ItemStack(Material.STONE)
    }
}

class AbilityCurling : ActiveAbility<AbilityConceptCurling>(), Listener {
    private data class HittedEntity(
        val uuid: UUID,
        var remainingTick: Int,
        var hitCount: Int
    )

    private val activeStones = hashSetOf<StoneProjectile>()
    private val hittedEntities = arrayListOf<HittedEntity>()

    init {
        targeter = {
            val eye = esper.player.eyeLocation.clone().apply { pitch = 0.0f }
            val world = eye.world
            val forward = eye.direction.clone().setY(0.0).normalize()
            val front = eye.add(forward.multiply(2.0))

            if (front.block.type.isSolid) {
                null
            } else {
                world.rayTraceBlocks(
                    front,
                    Vector(0.0, 1.0, 0.0),
                    5.0,
                    FluidCollisionMode.NEVER,
                    true
                )?.hitPosition?.toLocation(world) ?: front
            }
        }
    }

    override fun onEnable() {
        psychic.registerEvents(this)
        psychic.runTaskTimer(this::tickHittedEntities, 1L, 1L)
    }

    override fun onDisable() {
        activeStones.toList().forEach { it.remove() }
        activeStones.clear()
        hittedEntities.clear()
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        if (target !is Location) return

        exhaust()

        val player = esper.player
        val forward = player.eyeLocation.clone().apply { pitch = 0.0f }.direction.clone().setY(0.0).normalize()
        val spawnLocation = target.clone().add(0.0, 2.0, 0.0)

        val stone = StoneProjectile(forward)
        activeStones += stone

        psychic.launchProjectile(spawnLocation, stone)
        stone.velocity = Vector(0.0, -concept.dropSpeed, 0.0)

        player.world.playSound(player.location, Sound.BLOCK_STONE_PLACE, 1.0f, 1.2f)
    }

    private fun tickHittedEntities() {
        val iterator = hittedEntities.iterator()
        while (iterator.hasNext()) {
            val data = iterator.next()
            data.remainingTick -= 1
            if (data.remainingTick <= 0) iterator.remove()
        }
    }

    private fun impact(center: Location) {
        val world = center.world
        val radius = concept.damageRange

        spawnImpactCircle(center, radius)
        world.playSound(center, Sound.BLOCK_STONE_HIT, 1.0f, 0.8f)

        world.getNearbyEntities(center, radius, radius, radius, TargetFilter(esper.player)).forEach { entity ->
            if (entity is LivingEntity) {
                entity.psychicDamage(knockbackLocation = center, knockback = concept.knockback)
                updateHittedEntity(entity)
            }
        }
    }

    private fun updateHittedEntity(entity: LivingEntity) {
        val found = hittedEntities.firstOrNull { it.uuid == entity.uniqueId }
        if (found == null) {
            hittedEntities += HittedEntity(entity.uniqueId, concept.hitStackDurationTicks, 1)
            return
        }

        found.remainingTick = concept.hitStackDurationTicks
        found.hitCount += 1

        if (found.hitCount >= 3) {
            entity.freezeTicks = entity.maxFreezeTicks
            found.hitCount = 0
            entity.world.playSound(entity.location, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.9f, 1.0f)
            entity.world.spawnParticle(Particle.SNOWFLAKE, entity.location.add(0.0, 1.0, 0.0), 14, 0.35, 0.45, 0.35, 0.0)
        }
    }

    private fun spawnImpactCircle(center: Location, radius: Double) {
        val world = center.world
        val points = 24

        repeat(points) { i ->
            val radian = Math.PI * 2.0 * i / points
            val x = center.x + kotlin.math.cos(radian) * radius
            val z = center.z + kotlin.math.sin(radian) * radius
            world.spawnParticle(Particle.BLOCK, x, center.y + 0.1, z, 1, 0.0, 0.0, 0.0, 0.0, Material.STONE.createBlockData())
        }
    }

    inner class StoneProjectile(
        private val forward: Vector
    ) : PsychicProjectile(concept.stoneDurationTicks, 9999.0) {
        private val displays = arrayListOf<BlockDisplay>()
        private var grounded = false
        private var pendingNormal: Vector? = null
        private var impactCooldown = 0

        override fun onPreUpdate() {
            if (impactCooldown > 0) impactCooldown -= 1

            velocity = if (grounded) {
                val block = location.clone().add(0.0, -0.5, 0.0).block
                val slipperiness = when(block.type.slipperiness) {
                    0.4f -> 0.9f
                    0.6f -> 0.95f
                    else -> block.type.slipperiness
                }
                esper.player.sendMessage(text("Current slipperiness: $slipperiness"))
                val friction = concept.groundFrictionMultiplier * slipperiness

                velocity.multiply(friction).apply {
                    y = (y - concept.gravity).coerceAtLeast(-0.6)
                }
            } else {
                velocity.apply { y -= concept.gravity }
            }
        }

        override fun onMove(movement: Movement) {
            if (displays.isEmpty()) {
                spawnDisplays(movement.to)
            }

            updateDisplays(movement.to)
            collideWithOtherStone(movement.to)
        }

        override fun onTrail(trail: Trail) {
            val velocity = trail.velocity ?: return
            val world = trail.from.world
            val length = velocity.length()
            if (length <= 0.0) return

            world.rayTraceEntities(
                trail.from,
                velocity,
                length,
                concept.stoneRadius,
                TargetFilter(esper.player)
            )?.hitEntity?.let { hitEntity ->
                if (hitEntity is LivingEntity) {
                    queueImpact(hitEntity.location.clone().add(0.0, 0.5, 0.0))
                    val normal = location.toVector().subtract(hitEntity.location.toVector()).setY(0.0)
                    if (normal.lengthSquared() > 0.0001) {
                        pendingNormal = normal.normalize()
                    }
                    return
                }
            }

            val blockResult = world.rayTraceBlocks(
                trail.from,
                velocity,
                length + concept.stoneRadius,
                FluidCollisionMode.NEVER,
                true
            ) ?: return

            val face = blockResult.hitBlockFace ?: return
            val hitLocation = blockResult.hitPosition.toLocation(world)

            if (face == BlockFace.UP) {
                if (!grounded) {
                    grounded = true
                    this.velocity = forward.clone().multiply(concept.slideSpeed)
                }
                queueImpact(hitLocation)
            } else {
                pendingNormal = face.direction
                queueImpact(hitLocation)
            }
        }

        override fun onPostUpdate() {
            pendingNormal?.let { normal ->
                val reflected = velocity.clone().reflect(normal.normalize()).multiply(concept.bounceMultiplier)
                reflected.y = reflected.y.coerceAtMost(0.0)
                velocity = reflected
                pendingNormal = null
            }
        }

        override fun onRemove() {
            displays.forEach { if (it.isValid) it.remove() }
            displays.clear()
            activeStones.remove(this)
        }

        private fun spawnDisplays(base: Location) {
            val world = base.world
            val bottom = world.spawn(base, BlockDisplay::class.java) { display ->
                display.block = Material.SMOOTH_STONE_SLAB.createBlockData()
                display.interpolationDuration = 1
                display.interpolationDelay = 0
                val transform = display.transformation
                transform.scale.set(0.6f, 0.3f, 0.6f)
                display.transformation = transform
            }

            val top = world.spawn(base.clone().add(0.0, 0.28, 0.0), BlockDisplay::class.java) { display ->
                display.block = Material.STONE.createBlockData()
                display.interpolationDuration = 1
                display.interpolationDelay = 0
                val transform = display.transformation
                transform.scale.set(0.45f, 0.3f, 0.45f)
                display.transformation = transform
            }

            displays += bottom
            displays += top
        }

        private fun updateDisplays(base: Location) {
            if (displays.size < 2) return

            displays[0].teleport(base.clone().add(0.0, 0.05, 0.0))
            displays[1].teleport(base.clone().add(0.0, 0.28, 0.0))
        }

        private fun collideWithOtherStone(current: Location) {
            val other = activeStones.firstOrNull {
                it !== this && it.location.world == current.world &&
                    it.location.distanceSquared(current) <= (concept.stoneRadius * 2.0) * (concept.stoneRadius * 2.0)
            } ?: return

            val normal = current.toVector().subtract(other.location.toVector()).setY(0.0)
            pendingNormal = if (normal.lengthSquared() > 0.0001) {
                normal.normalize()
            } else {
                forward.clone().multiply(-1.0)
            }

            queueImpact(current)
        }

        private fun queueImpact(center: Location) {
            if (impactCooldown > 0) return
            impactCooldown = 2
            impact(center)
        }
    }

    private fun Vector.reflect(normal: Vector): Vector {
        val n = normal.clone().normalize()
        return this.subtract(n.multiply(2.0 * this.dot(n)))
    }
}
