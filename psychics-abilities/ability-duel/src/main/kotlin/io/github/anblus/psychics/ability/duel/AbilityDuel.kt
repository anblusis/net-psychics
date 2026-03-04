package io.github.anblus.psychics.ability.duel

import io.github.monun.psychics.*
import io.github.monun.tap.config.Name
import io.github.monun.tap.config.Config
import io.github.monun.tap.event.EntityProvider
import io.github.monun.tap.event.TargetEntity
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.kyori.adventure.title.Title.Times
import net.kyori.adventure.title.Title.title
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.time.Duration.*
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Name("duel")
class AbilityConceptDuel : AbilityConcept() {

    @Config
    val debuffDuration = 600 // 30 seconds (20 ticks per second)

    @Config
    val arenaRadius = 10

    init {
        displayName = "결투"
        type = AbilityType.ACTIVE
        cost = 60.0
        cooldownTime = 20000L
        description = listOf(
            text("능력 아이템을 들고 블록에 우클릭하여 결투 위치를 지정합니다."),
            text("결투 위치를 지정한 후 능력 아이템으로 누군가를 때리면"),
            text("양쪽 모두 결투 위치로 이동하며 결투장이 생성됩니다."),
            text("결투장으로 이동할 시 양쪽 모두 체력을 회복합니다."),
            text("상대는 추가로 채굴 피로 효과를 받습니다.")
        )
        wand = ItemStack(Material.PAPER)
    }
}

class AbilityDuel : Ability<AbilityConceptDuel>(), Listener {
    companion object {
        val blocksToReplace = arrayOf(Material.BEDROCK, Material.OBSIDIAN, Material.COMMAND_BLOCK, Material.BARRIER)
    }

    private var duelLocation: Location? = null

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = esper.player
        if (event.player != player) return

        val action = event.action

        if (action == Action.RIGHT_CLICK_BLOCK && event.item?.type == concept.wand?.type) {
            val clickedBlock = event.clickedBlock
            if (clickedBlock != null) {
                val testResult = test()
                if (testResult != TestResult.Success && testResult != TestResult.FailedCost) {
                    testResult.message(this)?.let { player.sendActionBar(it) }
                    return
                }
                duelLocation = clickedBlock.location
                cooldownTime = concept.cooldownTime
                player.sendActionBar(text("결투 위치가 설정되었습니다.").color(NamedTextColor.GREEN))

                var tick = 0
                val effectTask = psychic.runTaskTimer({
                    repeat(10) {
                        tick ++
                        val height = tick.toDouble().div(45)
                        val angle = Math.toRadians(tick.toDouble() * 10)

                        val loc = Vector(cos(angle) * 0.6, height, sin(angle) * 0.6)
                        player.spawnParticle(
                            Particle.DUST,
                            duelLocation!!.clone().add(0.5, 1.5, 0.5).add(loc),
                            1,
                            Particle.DustOptions(Color.fromRGB(0,255 - tick.div(3).mod(256), 255 - tick.div(3).mod(256)), 0.8f)
                        )
                    }
                }, 0L, 1L)
                psychic.runTask({
                    effectTask.cancel()
                }, 8L)
            }
        }
    }

    @EventHandler
    @TargetEntity(EntityProvider.EntityDamageByEntity.Damager::class)
    fun onPlayerHit(event: EntityDamageByEntityEvent) {
        val player = esper.player
        if (player.inventory.itemInMainHand.type != concept.wand?.type) return

        val testResult = test()
        if (testResult != TestResult.Success) {
            testResult.message(this)?.let { player.sendActionBar(it) }
            return
        }

        if (duelLocation == null || !duelLocation!!.world!!.worldBorder.isInside(duelLocation!!)) {
            player.sendActionBar(text("결투 위치가 설정되지 않았거나 월드 보더 밖입니다.").decorate(TextDecoration.BOLD))
            return
        }

        event.isCancelled = true
        psychic.consumeMana(concept.cost)

        val target = event.entity as LivingEntity

        val playerPosition = duelLocation!!.clone().add(concept.arenaRadius.toDouble() - 3.0, 1.0, 0.0).apply { pitch = 0f; yaw = 90f }
        val targetPosition = duelLocation!!.clone().add(-(concept.arenaRadius.toDouble() - 3.0), 1.0, 0.0).apply { pitch = 0f; yaw = -90f }

        player.teleport(playerPosition)
        target.teleport(targetPosition)

        createArena(duelLocation!!)

        arrayOf(player, target).forEach { entity ->
            entity.health = entity.getAttribute(Attribute.MAX_HEALTH)!!.value
            entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 60, 4))
            entity.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 60, 4))
            if (entity is Player) {
                entity.playSound(entity.location, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f)
                entity.showTitle(createTitle("3"))
                psychic.runTask({
                    entity.playSound(entity.location, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f)
                    entity.showTitle(createTitle("2"))
                }, 20L)
                psychic.runTask({
                    entity.playSound(entity.location, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f)
                    entity.showTitle(createTitle("1"))
                }, 40L)
                psychic.runTask({
                    entity.playSound(entity.location, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 2.0f)
                    entity.showTitle(createTitle("START!"))
                }, 60L)
            }
        }
        target.addPotionEffect(PotionEffect(PotionEffectType.MINING_FATIGUE, concept.debuffDuration, 2))
    }
    private fun createTitle(content: String): Title {
        return title(text(content).color(NamedTextColor.RED),
            text(""),
            Times.times(ofSeconds(0L), ofSeconds(1L), ofSeconds(0L)))
    }

    private fun createArena(center: Location) {
        val world = center.world
        val radius = concept.arenaRadius

        for (x in -radius..radius) {
            for (z in -radius..radius) {
                for (y in 0..radius) {
                    val loc = center.clone().add(x.toDouble(), y.toDouble(), z.toDouble())
                    val distance = sqrt((x * x + y * y + z * z).toDouble())
                    if (distance <= radius - 0.1) {
                        val block = world!!.getBlockAt(loc)
                        if (block.type !in blocksToReplace) {
                            when {
                                y == 0 -> {
                                    block.type = Material.WHITE_CONCRETE
                                }
                                distance > radius - 1.1 -> {
                                    block.type = Material.GLASS
                                }
                                else -> {
                                    block.type = Material.AIR
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}