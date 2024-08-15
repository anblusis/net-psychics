package io.github.anblus.psychics.ability.phantomrider

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import io.github.monun.psychics.Ability
import io.github.monun.psychics.TestResult
import io.github.monun.tap.task.TickerTask
import org.bukkit.entity.Phantom
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerToggleSneakEvent

@Name("phantom-rider")
class AbilityConceptPhantomRider : AbilityConcept() {

    init {
        displayName = "팬텀 라이더"
        type = AbilityType.ACTIVE
        cost = 10.0
        cooldownTime = 5000L
        description = listOf(
            text("우클릭으로 팬텀을 소환하고 탈 수 있습니다."),
            text("팬텀을 타면 팬텀은 플레이어가 바라보는 방향으로 이동합니다.")
        )
        wand = ItemStack(Material.PHANTOM_MEMBRANE)
    }
}

class AbilityPhantomRider : Ability<AbilityConceptPhantomRider>(), Listener {

    private var summonedPhantom: Phantom? = null

    private lateinit var task: TickerTask

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onDisable() {
        summonedPhantom?.remove()
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = esper.player
        if (event.player != player) return

        if (event.action.isRightClick && event.item?.type == concept.wand?.type) {
            val result = test()
            if (result != TestResult.Success) {
                result.message(this)?.let { player.sendActionBar(it) }
                return
            }

            if (summonedPhantom != null && summonedPhantom!!.isValid) {
                player.sendActionBar(text("이미 소환된 팬텀이 있습니다."))
                return
            }

            // 팬텀 소환
            psychic.consumeMana(concept.cost)
            cooldownTime = concept.cooldownTime

            summonedPhantom = player.world.spawn(player.location, Phantom::class.java).apply {
                isPersistent = true
                addPassenger(player)
            }

            task = psychic.runTaskTimer(this::onTick, 0L, 1L)

            player.sendActionBar(text("팬텀을 소환했습니다."))
        }
    }

    @EventHandler
    fun onPlayerToggleSneak(event: PlayerToggleSneakEvent) {
        val player = esper.player
        if (event.player != player) return

        if (summonedPhantom != null && summonedPhantom!!.isValid) {
            summonedPhantom!!.eject()
            summonedPhantom!!.remove()
            summonedPhantom = null
            task.cancel()
            player.sendActionBar(text("팬텀을 해제했습니다."))
        }
    }

    private fun onTick() {
        val player = esper.player
        summonedPhantom?.let {
            if (!it.isValid || it.passengers.isEmpty() || it.passengers[0] != player) {
                it.remove()
                summonedPhantom = null
                return
            }

            // 팬텀이 플레이어의 시선 방향으로 이동
            val direction = player.location.direction
            it.velocity = direction.multiply(0.5)
        }
    }
}


