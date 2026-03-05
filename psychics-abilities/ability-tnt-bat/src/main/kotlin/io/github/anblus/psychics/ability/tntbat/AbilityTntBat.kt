package io.github.anblus.psychics.ability.tntbat

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.damage.psychicDamage
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.tooltip.template
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Bat
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Name("tnt-bat")
class AbilityConceptTntBat : AbilityConcept() {
    @Config
    var spawnCount = 6

    @Config
    var spawnSpread = 1.8

    @Config
    var targetDelayTicks = 30

    @Config
    var searchRange = 9.0

    @Config
    var dashSpeed = 0.95

    @Config
    var maxLifetimeTicks = 120

    @Config
    var blastRange = 3.0

    init {
        displayName = "폭탄 박쥐떼"
        type = AbilityType.ACTIVE
        cost = 35.0
        cooldownTime = 14000L
        knockback = 1.2
        damage = Damage.of(DamageType.BLAST, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 3.5))
        description = listOf(
            text("자신의 주변에 폭탄 박쥐떼를 소환합니다."),
            text("박쥐는 잠시 표적을 찾지 않다가, 근처 적을 발견하면 돌진해 폭발합니다."),
            text("박쥐는 죽거나 수명이 끝나도 폭발합니다.")
        )
        wand = ItemStack(Material.TNT)
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(spawnCount) { NamedTextColor.GREEN to "소환 수" to "마리" }
        tooltip.stats(targetDelayTicks / 20.0) { NamedTextColor.YELLOW to "표적 탐지 지연" to "초" }
        tooltip.stats(blastRange) { NamedTextColor.RED to "폭발 범위" to "블록" }
        tooltip.stats(text("폭발"), damage ?: Damage.of(DamageType.BLAST, EsperAttribute.ATTACK_DAMAGE to 0.0)) {
            NamedTextColor.DARK_RED to "batDamage"
        }
        tooltip.template("batDamage", stats((damage ?: return).stats))
    }
}

class AbilityTntBat : ActiveAbility<AbilityConceptTntBat>(), Listener {
    private val bats = linkedMapOf<UUID, TntBat>()
    private var currentTick = 0

    override fun onEnable() {
        psychic.registerEvents(this)
        psychic.runTaskTimer(this::onTick, 0L, 1L)
    }

    override fun onDisable() {
        bats.values.toList().forEach { it.removeSilently() }
        bats.clear()
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        val player = esper.player
        val world = player.world

        cooldownTime = concept.cooldownTime
        psychic.consumeMana(concept.cost)

        repeat(concept.spawnCount.coerceAtLeast(1)) {
            val offsetX = (Random.nextDouble() - 0.5) * concept.spawnSpread
            val offsetZ = (Random.nextDouble() - 0.5) * concept.spawnSpread
            val spawn = player.location.clone().add(offsetX, 1.0, offsetZ)
            val bat = world.spawn(spawn, Bat::class.java).apply {
                isPersistent = false
                customName(text("${player.name}의 폭탄 박쥐"))
                isCustomNameVisible = true
                getAttribute(Attribute.MAX_HEALTH)?.baseValue = 1.0
                health = 1.0
            }

            runCatching {
                Bukkit.getScoreboardManager().mainScoreboard.getEntryTeam(player.name)?.addEntry(bat.uniqueId.toString())
            }

            val tntDisplay = world.spawn(spawn.clone().add(0.0, -0.5, 0.0), ItemDisplay::class.java).apply {
                isPersistent = false
                setItemStack(ItemStack(Material.TNT))
                transformation = Transformation(
                    Vector3f(),
                    Quaternionf(),
                    Vector3f(0.45f, 0.45f, 0.45f),
                    Quaternionf()
                )
            }

            bats[bat.uniqueId] = TntBat(
                bat = bat,
                tntDisplay = tntDisplay,
                owner = player,
                spawnedTick = currentTick,
                orbitCenter = player.location.clone()
            )
        }

        world.playSound(player.location, Sound.ENTITY_BAT_TAKEOFF, 1.3f, 1.1f)
    }

    private fun onTick() {
        currentTick++
        bats.values.toList().forEach { tntBat ->
            if (!tntBat.tick()) {
                bats.remove(tntBat.bat.uniqueId)
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val bat = event.entity as? Bat ?: return
        val tntBat = bats.remove(bat.uniqueId) ?: return
        event.drops.clear()
        tntBat.explode(bat.location)
    }

    private inner class TntBat(
        val bat: Bat,
        val tntDisplay: ItemDisplay,
        val owner: Player,
        val spawnedTick: Int,
        val orbitCenter: Location
    ) {
        private val targetFilter = TargetFilter(owner)
        private val orbitRadius = 1.4 + Random.nextDouble() * 1.1
        private var orbitAngle = Random.nextDouble(0.0, Math.PI * 2.0)
        private var dashDirection: Vector? = null
        private var exploded = false

        fun tick(): Boolean {
            if (exploded || !bat.isValid || !owner.isValid) {
                removeSilently()
                return false
            }

            val lifeTicks = currentTick - spawnedTick
            if (lifeTicks >= concept.maxLifetimeTicks) {
                explode(bat.location)
                return false
            }

            if (dashDirection == null && lifeTicks >= concept.targetDelayTicks) {
                findTargetDirection()?.let { direction ->
                    dashDirection = direction
                    faceDirection(direction)
                    bat.world.playSound(bat.location, Sound.ENTITY_BAT_LOOP, 0.9f, 0.5f)
                }
            }

            if (dashDirection == null) {
                updateOrbitMotion()
            } else {
                updateDashMotion(dashDirection!!)
            }

            updateDisplay()
            return true
        }

        private fun findTargetDirection(): Vector? {
            val world = bat.world
            return world.getNearbyEntities(bat.location, concept.searchRange, concept.searchRange, concept.searchRange)
                        .asSequence()
                        .filterIsInstance<Player>()
                        .filter { it.isValid && targetFilter.test(it) }
                        .minByOrNull { it.location.distanceSquared(bat.location) }?.eyeLocation?.toVector()?.subtract(bat.eyeLocation.toVector())
                ?.normalize()
        }

        private fun updateOrbitMotion() {
            orbitAngle += 0.14
            val yWave = 0.35 * sin((currentTick + spawnedTick) * 0.18)
            val target = orbitCenter.clone().add(cos(orbitAngle) * orbitRadius, 1.2 + yWave, sin(orbitAngle) * orbitRadius)
            val move = target.toVector().subtract(bat.location.toVector()).multiply(0.33)
            if (move.lengthSquared() > 0.0001) {
                val next = bat.location.clone().add(move)
                next.direction = move.clone().normalize()
                bat.teleport(next)
            }
        }

        private fun updateDashMotion(direction: Vector) {
            val world = bat.world
            val from = bat.eyeLocation
            val stepDistance = concept.dashSpeed + 0.4
            val rayResult = world.rayTrace(
                from,
                direction,
                stepDistance,
                org.bukkit.FluidCollisionMode.NEVER,
                true,
                0.35
            ) { entity ->
                entity is Player && entity.isValid && targetFilter.test(entity)
            }

            if (rayResult != null) {
                val hitLocation = rayResult.hitPosition.toLocation(world)
                explode(hitLocation)
                return
            }

            val next = bat.location.clone().add(direction.clone().multiply(concept.dashSpeed))
            next.direction = direction
            bat.teleport(next)
            faceDirection(direction)
        }

        private fun updateDisplay() {
            if (!tntDisplay.isValid) return
            val displayLoc = bat.location.clone().add(0.0, -0.45, 0.0)
            tntDisplay.teleport(displayLoc)
            bat.world.spawnParticle(Particle.FLAME, displayLoc.clone().add(0.0, 0.22, 0.0), 1, 0.03, 0.03, 0.03, 0.001)
            bat.world.spawnParticle(Particle.LARGE_SMOKE, displayLoc.clone().add(0.0, 0.24, 0.0), 1, 0.02, 0.02, 0.02, 0.001)
        }

        private fun faceDirection(direction: Vector) {
            val location = bat.location
            location.direction = direction
            bat.teleport(location)
        }

        fun explode(center: Location) {
            if (exploded) return
            exploded = true

            val world = center.world
            val damage = concept.damage ?: return
            val amount = esper.getStatistic(damage.stats)

            world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticle(Particle.LARGE_SMOKE, center, 22, 0.3, 0.25, 0.3, 0.02)
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.2f)

            world.getNearbyEntities(center, concept.blastRange, concept.blastRange, concept.blastRange)
                .asSequence()
                .filterIsInstance<LivingEntity>()
                .filter { targetFilter.test(it) }
                .forEach { victim ->
                    victim.psychicDamage(this@AbilityTntBat, damage.type, amount, owner, center, concept.knockback)
                }

            removeSilently()
        }

        fun removeSilently() {
            if (tntDisplay.isValid) tntDisplay.remove()
            if (bat.isValid) bat.remove()
        }
    }
}

