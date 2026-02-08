package io.github.anblus.psychics.ability.phantomrider

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.Ability
import io.github.monun.psychics.TestResult
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.tooltip.template
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.task.TickerTask
import net.kyori.adventure.text.Component.space
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Phantom
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.text.DecimalFormat

@Name("phantom-rider")
class AbilityConceptPhantomRider : AbilityConcept() {
    @Config var manaCostPerTick = 0.1
    @Config var dashCost = 15.0
    @Config var dashCooldown = 3000L
    @Config var dashRange = 10.0
    @Config var dashSpeed = 30.0
    @Config var normalSpeed = 10.0
    @Config var dashKnockback = 1.5
    @Config var phantomBaseHealth = 10.0
    @Config var phantomEsperStatisticHealth = EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 5.0)

    init {
        displayName = "팬텀 라이더"
        type = AbilityType.ACTIVE
        cost = 35.0
        cooldownTime = 10000L
        damage = Damage.of(DamageType.MELEE, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 2.0))
        description = listOf(
            text("팬텀에 탑승하여 바라보는 방향으로 비행합니다."),
            text("탑승 중 능력을 재사용 시 전방으로 돌진합니다."),
            text("돌진 시 경로상의 적에게 피해와 넉백을 줍니다."),
            text("탑승 중에는 지속적으로 마나를 소모하며, 마나가 고갈되면 해제됩니다.")
        )
        wand = ItemStack(Material.PHANTOM_MEMBRANE)
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(manaCostPerTick * 20) { NamedTextColor.GREEN to "탑승 중 마나 소모" to null }
        tooltip.stats(dashCost) { NamedTextColor.DARK_GREEN to "돌진 마나 소모" to null }
        tooltip.stats(phantomBaseHealth + stats(phantomEsperStatisticHealth)) { NamedTextColor.DARK_RED to "팬텀 체력" to null }
    }
}

class AbilityPhantomRider : Ability<AbilityConceptPhantomRider>(), Listener {
    private var phantom: Phantom? = null
    private var mount: ArmorStand? = null

    private var movementTask: TickerTask? = null
    private var isDashing = false
    private var dashTicks = 0
    private val damagedEntities = mutableSetOf<LivingEntity>()
    private var dashDirection: Vector? = null

    private var maxDashTick: Int = 0

    override fun onEnable() {
        psychic.registerEvents(this)
        maxDashTick = (concept.dashRange * 20.0 / concept.dashSpeed).toInt()
    }
    override fun onDisable() { cleanup() }

    @EventHandler(ignoreCancelled = true)
    fun onDamaged(event: EntityDamageEvent) {
        if (event.entity !is Player) {
            if (event.cause == EntityDamageEvent.DamageCause.SUFFOCATION) event.isCancelled = true
        } else {
            if (event.cause == EntityDamageEvent.DamageCause.FALL && mount != null) event.isCancelled = true
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        if (event.entity !is Player) {
            event.drops.clear()
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val player = esper.player
        if (event.action == Action.PHYSICAL) return
        if (event.item?.type != concept.wand?.type) return

        if (mount != null) {
            if (cooldownTime > 0L) {
                player.sendActionBar(text().content("아직 준비되지 않았습니다").decorate(TextDecoration.BOLD).build())
                return
            }
            if (!psychic.consumeMana(concept.dashCost)) {
                player.sendActionBar(text().content("마나가 부족합니다").decorate(TextDecoration.BOLD)
                    .append(space())
                    .append(text(DecimalFormat("#.##").format(concept.dashCost)))
                    .build())
                return
            }
            startDash()
            cooldownTime = concept.dashCooldown
            return
        }

        // 소환 테스트
        val result = test()
        if (result != TestResult.Success) {
            result.message(this)?.let { player.sendActionBar(it) }
            return
        }

        psychic.consumeMana(concept.cost)

        val health = concept.phantomBaseHealth + esper.getStatistic(concept.phantomEsperStatisticHealth)
        val spawnLoc = player.location.add(0.0, 1.2, 0.0)

        // 실제 이동체 (투명 아머스탠드)
        mount = player.world.spawn(spawnLoc, ArmorStand::class.java).apply {
            isVisible = false
            isSmall = true
            getAttribute(Attribute.GENERIC_MAX_HEALTH)?.baseValue = health
            this.health = health
        }

        // 디스플레이 팬텀
        phantom = player.world.spawn(spawnLoc, Phantom::class.java).apply {
            setShouldBurnInDay(false)
            setAI(false)
            getAttribute(Attribute.GENERIC_MAX_HEALTH)?.baseValue = health
            this.health = health
        }

        psychic.plugin.entityEventManager.registerEvents(phantom!!, this)

        // 팀 동기화 (디스플레이 팬텀 기준)
        runCatching {
            Bukkit.getScoreboardManager().mainScoreboard.getEntryTeam(player.name)?.addEntry(phantom!!.uniqueId.toString())
        }

        // 플레이어 탑승
        mount!!.addPassenger(player)

        // 이동 루프 시작
        startMovementLoop()
        player.world.playSound(player.location, Sound.ENTITY_PHANTOM_AMBIENT, 1.0f, 1.0f)
    }

    private fun startMovementLoop() {
        movementTask?.cancel()
        movementTask = psychic.runTaskTimer(Runnable {
            val player = esper.player
            val mount = this.mount
            val phantom = this.phantom
            val task = movementTask

            if (mount == null || phantom == null || !mount.isValid || !phantom.isValid || !player.isValid) {
                cleanup(); task?.cancel(); return@Runnable
            }
            if (player.vehicle != mount) {
                cleanup(); task?.cancel(); return@Runnable
            }

            // 지속 마나 소모
            if (!psychic.consumeMana(concept.manaCostPerTick)) {
                cleanup(); task?.cancel(); return@Runnable
            }

            // 방향 및 속도 - 대쉬 중이면 고정된 방향 사용
            val dir = if (isDashing) {
                dashDirection!!
            } else {
                player.eyeLocation.direction
            }
            val speed = if (isDashing) concept.dashSpeed else concept.normalSpeed
            mount.velocity = dir.clone().multiply(speed * 0.05)

            // 팬텀 텔레포트 (디스플레이)
            val pLoc = mount.location.clone()

            // 돌진 중이면 팬텀의 시선도 돌진 방향으로 고정
            if (isDashing && dashDirection != null) {
                val dashLoc = pLoc.clone()
                dashLoc.direction = dashDirection!!
                pLoc.yaw = dashLoc.yaw
                pLoc.pitch = -dashLoc.pitch
            } else {
                pLoc.yaw = player.eyeLocation.yaw
                pLoc.pitch = -player.eyeLocation.pitch
            }

            // 살짝 위로 올려 자연스러운 위치
            pLoc.add(0.0, 0.5, 0.0)
            phantom.teleport(pLoc)

            // 돌진 중 날개 파티클 효과
            if (isDashing) {
                // 팬텀의 좌우 날개 위치 계산 (팬텀이 바라보는 방향 기준)
                val rightWing = pLoc.clone().add(pLoc.direction.clone().crossProduct(Vector(0.0, 1.0, 0.0)).multiply(1.5))
                val leftWing = pLoc.clone().add(pLoc.direction.clone().crossProduct(Vector(0.0, 1.0, 0.0)).multiply(-1.5))

                // 양 날개에서 파티클 생성
                phantom.world.spawnParticle(Particle.CLOUD, rightWing, 1, 0.0, 0.0, 0.0, 0.02)
                phantom.world.spawnParticle(Particle.CLOUD, leftWing, 1, 0.0, 0.0, 0.0, 0.02)
            }

            // 체력 동기화 (아머스탠드 -> 팬텀)
            runCatching {
                if (phantom.health != mount.health) mount.health = phantom.health
            }

            if (isDashing) handleDashTick()
        }, 0L, 1L)
    }

    private fun handleDashTick() {
        val mount = this.mount ?: return
        val player = esper.player

        dashTicks++
        mount.world.getNearbyEntities(mount.location, 2.0, 2.0, 2.0)
            .filterIsInstance<LivingEntity>()
            .filter { it != mount && it != phantom && TargetFilter(player).test(it) && it !in damagedEntities }
            .forEach { targetEnt ->
                damagedEntities.add(targetEnt)
                targetEnt.psychicDamage(knockbackLocation = mount.location, knockback = concept.dashKnockback)
                targetEnt.world.spawnParticle(Particle.CRIT, targetEnt.eyeLocation, 16, 0.4, 0.4, 0.4, 0.05)
            }

        if (dashTicks >= maxDashTick) {
            isDashing = false
            dashTicks = 0
            damagedEntities.clear()
        }
    }

    private fun startDash() {
        isDashing = true
        dashTicks = 0
        damagedEntities.clear()
        dashDirection = esper.player.eyeLocation.direction
        phantom!!.world.playSound(phantom!!.location, Sound.ENTITY_PHANTOM_AMBIENT, 2.0f, 2.0f)
    }

    private fun cleanup() {
        cooldownTime = concept.cooldownTime
        movementTask?.cancel(); movementTask = null
        // 엔티티 제거
        if (phantom != null) {
            psychic.plugin.entityEventManager.unregisterEvent(phantom!!, this)
            phantom!!.remove()
            phantom = null
        }
        mount?.eject(); mount?.remove(); mount = null
        isDashing = false; dashTicks = 0; damagedEntities.clear(); dashDirection = null
    }
}
