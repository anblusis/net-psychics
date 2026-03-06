package io.github.anblus.psychics.ability.tntbat

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Bat
import org.bukkit.entity.ItemDisplay
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
    var blastRange = 3.0

    init {
        displayName = "폭탄 박쥐떼"
        type = AbilityType.ACTIVE
        cost = 35.0
        cooldownTime = 14000L
        durationTime = 6000L
        knockback = 0.6
        damage = Damage.of(DamageType.BLAST, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 3.5))
        description = listOf(
            text("자신의 주변에 폭탄 박쥐떼를 소환합니다."),
            text("박쥐는 잠시 표적을 찾지 않다가, 근처 적을 발견하면 돌진해 폭발합니다."),
            text("박쥐는 죽거나 수명이 끝나도 폭발합니다.")
        )
        wand = ItemStack(Material.BLACK_DYE)
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(spawnCount) { NamedTextColor.GREEN to "소환 수" to "마리" }
        tooltip.stats(targetDelayTicks / 20.0) { NamedTextColor.YELLOW to "표적 탐지 시작" to "초 후" }
        tooltip.stats(blastRange) { NamedTextColor.RED to "폭발 범위" to "블록" }
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

        exhaust()

        repeat(concept.spawnCount) {
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
                spawnedTick = currentTick
            )
            psychic.plugin.entityEventManager.registerEvents(bat, this)
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
        tntBat.explode()
    }

    private inner class TntBat(
        val bat: Bat,
        val tntDisplay: ItemDisplay,
        val owner: Player,
        val spawnedTick: Int
    ) {
        private var dashDirection: Vector? = null
        private var exploded = false

        fun tick(): Boolean {
            if (exploded || !bat.isValid || !owner.isValid) {
                removeSilently()
                return false
            }

            val lifeTicks = currentTick - spawnedTick
            if (lifeTicks >= concept.durationTime / 50) {
                explode()
                return false
            }

            if (dashDirection == null && lifeTicks >= concept.targetDelayTicks) {
                findTargetDirection()?.let { direction ->
                    dashDirection = direction
                    bat.setAI(false)
                    faceDirection(direction)
                    bat.world.playSound(bat.location, Sound.ENTITY_BAT_LOOP, 0.9f, 0.5f)
                }
            }

            if (dashDirection != null) {
                updateDashMotion(dashDirection!!)
            }

            updateDisplay()
            return true
        }

        private fun findTargetDirection(): Vector? {
            val world = bat.world
            return world.getNearbyLivingEntities(bat.location, concept.searchRange, concept.searchRange, concept.searchRange)
                .filter { TargetFilter(esper.player).test(it) }
                .minByOrNull { it.location.distanceSquared(bat.location) }?.boundingBox?.center?.subtract(bat.eyeLocation.toVector())
                ?.normalize()
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
                0.2
            ) { entity ->
                esper.player.hostileFilter().test(entity)
            }

            if (rayResult != null) {
                explode()
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

        fun explode() {
            if (exploded) return
            exploded = true

            val center = bat.location
            val world = center.world

            world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1, 0.0, 0.0, 0.0, 0.0)
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.2f)

            world.getNearbyLivingEntities(center, concept.blastRange, concept.blastRange, concept.blastRange)
                .filter { esper.player.hostileFilter().test(it) }
                .forEach { victim ->
                    victim.psychicDamage(knockbackLocation = center)
                }

            removeSilently()
        }

        fun removeSilently() {
            psychic.plugin.entityEventManager.unregisterEvent(bat, this@AbilityTntBat)
            if (tntDisplay.isValid) tntDisplay.remove()
            if (bat.isValid) bat.remove()
        }
    }
}
