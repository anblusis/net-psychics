package io.github.anblus.psychics.ability.dryriver

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Pose
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

@Name("dry-river")
class AbilityConceptDryRiver : AbilityConcept() {
    @Config
    var dashSpeed = 1.35

    @Config
    var collisionRadius = 0.7

    @Config
    var markDurationTicks = 100

    init {
        displayName = "강습"
        type = AbilityType.ACTIVE
        cost = 20.0
        range = 8.0
        cooldownTime = 10000L
        damage = Damage.of(DamageType.MELEE, EsperAttribute.ATTACK_DAMAGE to 4.0)
        description = listOf(
            text("전방으로 빠르게 돌진해 경로 상의 적을 꿰뚫습니다."),
            text("돌진 중 처음 적중한 적에게 표식을 남깁니다."),
            text("표식이 없는 적에게 적중하면 재사용 대기시간이 즉시 초기화됩니다.")
        )
        wand = ItemStack(Material.IRON_SWORD)
    }
}

class AbilityDryRiver : ActiveAbility<AbilityConceptDryRiver>() {
    private val marks = linkedMapOf<UUID, MarkState>()
    private var dashState: DashState? = null

    override fun onEnable() {
        psychic.runTaskTimer(this::onTick, 0L, 1L)
    }

    override fun onDisable() {
        dashState = null
        marks.values.forEach { it.display.remove() }
        marks.clear()
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        val player = esper.player
        if (dashState != null) return

        psychic.consumeMana(concept.cost)
        cooldownTime = concept.cooldownTime

        dashState = DashState(
            player.eyeLocation.direction,
            concept.range
        )

        player.world.playSound(player.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 1.1f)
    }

    private fun onTick() {
        marks.entries.removeIf { (targetId, markState) ->
            val target = Bukkit.getEntity(targetId) as? LivingEntity
            if (target == null || !target.isValid || target.isDead || !markState.display.isValid) {
                markState.display.remove()
                return@removeIf true
            }

            if (markState.remainingTicks-- <= 0) {
                markState.display.remove()
                return@removeIf true
            }
            markState.display.teleport(target.location.add(0.0, target.height + 0.55, 0.0))
            false
        }

        val state = dashState ?: return
        val player = esper.player

        if (!player.isValid || player.isDead) {
            dashState = null
            return
        }

        player.pose = Pose.SPIN_ATTACK

        val world = player.world
        val from = player.location.clone().add(0.0, 0.9, 0.0)
        val direction = state.direction.clone().normalize()

        val rayResult = world.rayTrace(
            from,
            direction,
            concept.dashSpeed,
            FluidCollisionMode.NEVER,
            true,
            concept.collisionRadius
        ) { entity ->
            player.hostileFilter().test(entity)
        }

        if (rayResult != null) {
            val hitLocation = rayResult.hitPosition.toLocation(world)
            val hitEntity = rayResult.hitEntity as? LivingEntity

            if (hitEntity != null) {
                hitEntity.psychicDamage()

                if (!state.hasMadeTarget && !marks.containsKey(hitEntity.uniqueId)) {
                    marks[hitEntity.uniqueId] = MarkState(
                        spawnMarkDisplay(hitEntity),
                        concept.markDurationTicks
                    )
                    state.hasMadeTarget = true
                    cooldownTime = 0L
                }

                world.spawnParticle(Particle.SWEEP_ATTACK, hitLocation, 1, 0.0, 0.0, 0.0, 0.0)
                world.playSound(hitLocation, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.25f)
            } else {
                world.playSound(hitLocation, Sound.BLOCK_STONE_HIT, 1.0f, 1.8f)
                dashState = null
                return
            }
        }

        val move = direction.multiply(concept.dashSpeed)
        val next = player.location.clone().add(move)
        next.direction = direction
        player.teleport(next)

        world.spawnParticle(Particle.DUST_PLUME, player.location.clone().add(0.0, 0.1, 0.0), 2, 0.15, 0.0, 0.15, 0.01)

        state.remainingDistance -= concept.dashSpeed
        if (state.remainingDistance <= 0.0) {
            dashState = null
        }
    }

    private fun spawnMarkDisplay(target: LivingEntity): ItemDisplay {
        val location = target.location.add(0.0, target.height + 0.55, 0.0)
        return target.world.spawn(location, ItemDisplay::class.java).apply {
            isPersistent = false
            setItemStack(ItemStack(Material.EMERALD))
            billboard = Display.Billboard.VERTICAL
            transformation = Transformation(
                Vector3f(),
                Quaternionf(),
                Vector3f(0.8f, 0.8f, 0.8f),
                Quaternionf()
            )
        }
    }

    private data class MarkState(
        val display: ItemDisplay,
        var remainingTicks: Int
    )

    private data class DashState(
        val direction: Vector,
        var remainingDistance: Double,
        var hasMadeTarget: Boolean = false
    )
}
