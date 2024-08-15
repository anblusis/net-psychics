package io.github.anblus.psychics.ability.maestro

import io.github.monun.psychics.*
import io.github.monun.psychics.util.friendlyFilter
import io.github.monun.tap.config.Name
import io.github.monun.tap.config.Config
import io.github.monun.tap.task.TickerTask
import net.kyori.adventure.text.Component.text
import org.bukkit.*
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import kotlin.math.max

@Name("maestro")
class AbilityConceptMaestro : AbilityConcept() {

    @Config
    val linkRange = 20.0

    @Config
    val manaPerTick = 2.0

    init {
        displayName = "Maestro"
        type = AbilityType.ACTIVE
        cost = 0.0 // 마나가 지속적으로 소모되므로 초기 비용은 없음
        cooldownTime = 30000L
        description = listOf(
            text("주위 아군 간에 연결 고리를 형성합니다."),
            text("연결된 아군이 받는 피해와 치유는 연결된 모든 아군에게 고르게 분배됩니다."),
            text("연결된 아군이 능력 사용자로부터 너무 멀리 떨어질 경우 연결이 해제됩니다.")
        )
        wand = ItemStack(Material.MUSIC_DISC_11)
    }
}

class AbilityMaestro : ActiveAbility<AbilityConceptMaestro>(), Listener {

    private val linkedEntities = mutableSetOf<LivingEntity>()
    private var isActive = false
    private var task: TickerTask? = null

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onDisable() {
        unlinkAll()
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        if (isActive) {
            unlinkAll()
        } else {
            linkAll()
        }
    }

    private fun linkAll() {
        val player = esper.player

        // 링크할 아군 찾기
        linkedEntities.clear()
        player.world.getNearbyEntities(player.location, concept.linkRange, concept.linkRange, concept.linkRange)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && player.friendlyFilter().test(it) }
            .forEach { linkedEntities.add(it) }

        if (linkedEntities.isNotEmpty()) {
            linkedEntities.add(player)
            linkedEntities.forEach { psychic.plugin.entityEventManager.registerEvents(it, this) }
            playLinkEffects()
            player.sendMessage("아군들이 연결되었습니다!")
            isActive = true
            startLinkTask()
        } else {
            player.sendMessage("주변에 연결할 아군이 없습니다.")
        }
    }

    private fun startLinkTask() {
        val player = esper.player

        task = psychic.runTaskTimer({
            if (!isActive || psychic.mana < concept.manaPerTick) {
                unlinkAll()
                return@runTaskTimer
            }

            psychic.consumeMana(concept.manaPerTick)

            val toRemove = mutableSetOf<LivingEntity>()
            linkedEntities.forEach { entity ->
                if (!entity.isValid || entity.location.distance(player.location) > concept.linkRange) {
                    toRemove.add(entity)
                } else {
                    playGuardianBeamEffect(player.location, entity.location)
                }
            }
            toRemove.forEach {
                linkedEntities.remove(it)
                psychic.plugin.entityEventManager.unregisterEvent(it, this)
                it.sendMessage("마에스트로의 연결이 해제되었습니다.")
            }
            if (linkedEntities.size <= 1) {
                unlinkAll()
            }
        }, 0L, 1L)
    }

    private fun unlinkAll() {
        linkedEntities.forEach {
            psychic.plugin.entityEventManager.unregisterEvent(it, this)
            it.sendMessage("마에스트로의 연결이 해제되었습니다.")
        }
        linkedEntities.clear()
        task?.cancel()
        task = null
        isActive = false
    }

    @EventHandler
    fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (linkedEntities.contains(event.entity)) {
            distributeDamage(event.entity as LivingEntity, event.finalDamage)
            event.isCancelled = true
        }
    }

    private fun distributeDamage(target: LivingEntity, damage: Double) {
        val splitDamage = damage / linkedEntities.size
        linkedEntities.forEach {
            it.damage(splitDamage)
        }
    }

    @EventHandler
    fun onEntityHeal(event: EntityRegainHealthEvent) {
        if (linkedEntities.contains(event.entity)) {
            distributeHealing(event.entity as LivingEntity, event.amount)
            event.isCancelled = true
        }
    }

    private fun distributeHealing(target: LivingEntity, healAmount: Double) {
        val splitHealing = healAmount / linkedEntities.size
        linkedEntities.forEach {
            it.health = max(0.0, kotlin.math.min(it.maxHealth, it.health + splitHealing))
        }
    }

    private fun playLinkEffects() {
        val player = esper.player
        linkedEntities.forEach { entity ->
            player.world.spawnParticle(Particle.HEART, entity.location, 10, 0.5, 1.0, 0.5, 0.1)
            player.world.playSound(entity.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.0f)
        }
    }

    private fun playGuardianBeamEffect(start: Location, end: Location) {
        val world = start.world
        val startVec = start.add(0.0, 1.0, 0.0).toVector()
        val endVec = end.add(0.0, 1.0, 0.0).toVector()
        val direction = endVec.subtract(startVec).normalize()

        for (i in 0..10) {
            val point = startVec.clone().add(direction.clone().multiply(i / 10.0))
            world?.spawnParticle(Particle.GU, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    
}
