package io.github.sincostan1122.psychics.ability.flash


import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.fake.FakeEntity
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.ArmorStand
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack


@Name("Flash")
class AbilityConceptFlash : AbilityConcept() {
    @Config
    var trange = 5.0

    init {
        cost = 50.0
        cooldownTime = 10L
        wand = ItemStack(Material.DIAMOND)
        displayName = "점멸"

        description = listOf(
            text("일정 거리를 순간이동 합니다."),
        )
    }
    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.header(
            text().color(NamedTextColor.GREEN).content("순간이동 거리 ").decorate(TextDecoration.BOLD).decoration(
                TextDecoration.ITALIC, false)
                .append(text().color(NamedTextColor.WHITE).content(trange.toInt().toString()))
                .append(text().color(NamedTextColor.WHITE).content(" 블럭")).build()
        )
    }
}
class AbilityFlash : ActiveAbility<AbilityConceptFlash>(), Listener {
    var fakeEntity: FakeEntity<ArmorStand>? = null


    override fun onEnable() {
        psychic.registerEvents(this)
        fakeEntity = psychic.spawnFakeEntity(teleportloc(), ArmorStand::class.java).apply {
            updateMetadata {
                isVisible = false
            }
        }
        val world = teleportloc().world
        psychic.runTaskTimer({
            fakeEntity?.moveTo(teleportloc())
            if (esper.player.isValid && esper.player.gameMode != GameMode.SPECTATOR) world.spawnParticle(Particle.COMPOSTER, teleportloc(), 3)
        }, 0L, 1L)
    }
    override fun onDisable() {
        // 제거
        fakeEntity?.let { fakeEntity ->
            fakeEntity.remove()
            this.fakeEntity = null
        }
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        val concept = concept
        psychic.consumeMana(concept.cost)
        cooldownTime = concept.cooldownTime

        esper.player.teleport(teleportloc())
    }

    private fun teleportloc(): Location {
        return esper.player.eyeLocation.apply {
            add(direction.multiply(concept.trange))
        }
    }
}
