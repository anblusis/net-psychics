package io.github.anblus.psychics.ability.heavenlystrike
/*
import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.fake.FakeEntity
import io.github.monun.tap.task.TickerTask
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.math.cos
import kotlin.math.sin

@Name("heavenly-strike")
class AbilityConceptHeavenlyStrike : AbilityConcept() {

    @Config
    val intervalTick = 20  // 주기적 타격 간격 (틱 단위, 20틱 = 1초)

    @Config
    val damageValue = 2.0

    @Config
    val maxRange = 12.0

    @Config
    val effectRadius = 2.0

    @Config
    val innerRadius = 1.0

    @Config
    val knockbackStrength = 0.5

    init {
        displayName = "천상의 강타"
        type = AbilityType.ACTIVE
        cooldownTime = 15000L
        cost = 15.0
        damage = Damage.of(DamageType.RANGED, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to damageValue))
        description = listOf(
            text("공중에서 사용 가능하며, 사용 시 느린 낙하 효과를 부여합니다."),
            text("능력 발동 중 주기적으로 아래를 향해 피해와 공중 부양 효과를 줍니다."),
            text("능력 발동 중 다시 아이템 클릭 시 발동이 취소됩니다.")
        )
        wand = ItemStack(Material.FEATHER)
    }
}

class AbilityHeavenlyStrike : ActiveAbility<AbilityConceptHeavenlyStrike>(), Listener {
    private var isAbilityActive = false
    private lateinit var task: TickerTask
    private var worker: FakeEntity? = null

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onDisable() {
        cancel()
    }

    private fun cancel() {
        isAbilityActive = false
        task.cancel()
        esper.player.removePotionEffect(PotionEffectType.SLOW_FALLING)
        worker?.remove()
        worker = null
    }

    private fun applyHeavenlyStrike(player: LivingEntity) {
        val location = player.location.clone().apply { y -= 1.0 } // 플레이어 바로 아래 지점을 기준으로
        val radiusIncrement = (concept.effectRadius - concept.innerRadius) / concept.maxRange

        for (y in 0..concept.maxRange.toInt()) {
            val currentOuterRadius = concept.innerRadius + radiusIncrement * y
            val currentInnerRadius = radiusIncrement * y
            val yOffset = -y.toDouble()

            for (theta in 0..360 step 10) {
                val radians = Math.toRadians(theta.toDouble())
                val x = cos(radians)
                val z = sin(radians)

                if (x * x + z * z > currentInnerRadius * currentInnerRadius) {
                    player.world.spawnParticle(
                        Particle.END_ROD,
                        location.clone().add(x * currentOuterRadius, yOffset, z * currentOuterRadius),
                        1,
                        0.0,
                        0.0,
                        0.0,
                        0.1
                    )
                }
            }

            val entities = player.world.getNearbyEntities(location.clone().add(0.0, yOffset, 0.0), currentOuterRadius, 1.0, currentOuterRadius)
            for (entity in entities) {
                if (entity is LivingEntity && esper.player.hostileFilter().test(entity)) {
                    entity.damage(concept.damageValue)
                    entity.velocity = entity.velocity.add(0.0, concept.knockbackStrength, 0.0)
                }
            }
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = esper.player
        if (event.player != player) return

        val action = event.action

        if (event.item?.type == concept.wand?.type) {
            if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
                if (isAbilityActive) {
                    cancel()
                } else {
                    if (!psychic.consumeMana(concept.cost)) return player.sendActionBar(TestResult.FailedCost.message(this))
                    isAbilityActive = true
                    player.addPotionEffect(PotionEffect(PotionEffectType.SLOW_FALLING, Int.MAX_VALUE, 1))

                    task = psychic.runTaskTimer({
                        if (!isAbilityActive) {
                            task.cancel()
                            return@runTaskTimer
                        }
                        applyHeavenlyStrike(player)
                    }, 0L, concept.intervalTick.toLong())

                    worker = psychic.spawnFakeEntity(player.location, ArmorStand::class.java).apply {
                        updateMetadata<ArmorStand> {
                            isVisible = false
                            isMarker = true
                        }
                        updateEquipment {
                            helmet = ItemStack(Material.FEATHER)
                        }
                    }

                    player.sendMessage(text("천상의 강타가 발동되었습니다!").color(NamedTextColor.GOLD))
                }
            }
        }
    }
}
*/