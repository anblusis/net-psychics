package io.github.anblus.psychics.ability.waverider

import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.task.TickerTask
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector

@Name("wave-rider")
class AbilityConceptWaveRider : AbilityConcept() {
    // 설정값(@Config 유지)
    @Config var waveSpeed = 0.9              // 틱당 전진 속도 (block/tick)
    @Config var slowDuration = 60            // 구속 지속 (ticks)
    @Config var slowAmplifier = 2            // 구속 레벨 (0 -> 구속 I)
    @Config var waveWidth = 5                    // 파도 가로 폭 (홀수 권장)
    @Config var waveAmplitude = 3.0         // 파도 최대 높이
    @Config var crashKnockback = 2.5         // 충돌 시 넉백 강도
    @Config var crashRadius = 4.0            // 충돌 범위

    init {
        displayName = "파도 타기"
        type = AbilityType.ACTIVE
        cost = 35.0
        cooldownTime = 10000L
        durationTime = 2000L
        damage = Damage.of(DamageType.MELEE, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 5.0))
        description = listOf(
            text("능력 사용 시 파도를 타고 질주합니다."),
            text("파도가 끝날 경우 전방으로 튀어오릅니다."),
            text("파도는 지형지물에 부딪치거나 적고 충돌할 경우 무너집니다."),
            text("파도는 무너지며 주위 적에게 구속과 함께 피해를 입히고 넉백시킵니다."),
            text("능력 사용 중엔 낙하 피해를 입지 않습니다.")
        )
        wand = ItemStack(Material.HEART_OF_THE_SEA)
    }
}

private data class WavePiece(
    val xIndex: Int,           // 좌우 인덱스
    val zIndex: Int,           // 깊이 인덱스
    val display: BlockDisplay
)

class AbilityWaveRider : ActiveAbility<AbilityConceptWaveRider>(), Listener {
    companion object {
        const val UPWARD_VELOCITY = 1.1
        const val STEP_UP_VELOCITY = 0.42
        const val DEPTH_COUNT = 8
    }

    private val wavePieces = mutableListOf<WavePiece>()
    private var task: TickerTask? = null
    private var sinkTask: TickerTask? = null
    private var running = false
    private var crashing = false
    private var tick = 0
    private var direction = Vector()
    private var baseLocation: org.bukkit.Location? = null // 파도 생성 기준(지면)
    private var fallImmune = false   // 튀어오른 뒤 착지 전까지 낙뎀 면역

    override fun onEnable() { psychic.registerEvents(this) }
    override fun onDisable() { stopWave() }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        if (running || crashing) return
        val result = test()
        if (result != TestResult.Success) {
            result.message(this)?.let { esper.player.sendActionBar(it) }
            return
        }
        exhaust()
        startWave()
    }

    private fun startWave() {
        val player = esper.player
        running = true
        crashing = false
        tick = 0
        direction = player.eyeLocation.direction.clone().setY(0.0).normalize()
        spawnWaveDisplays()
        player.world.playSound(player.location, Sound.ENTITY_DOLPHIN_SPLASH, 1.0f, 1.1f)
        player.sendActionBar(text("파도 질주!", NamedTextColor.AQUA, TextDecoration.BOLD))
        task = psychic.runTaskTimer({ tickWave() }, 0L, 1L)
    }

    private fun spawnWaveDisplays() {
        // 기존 디스플레이 제거 후 새로 구성
        wavePieces.forEach { runCatching { it.display.remove() } }
        wavePieces.clear()

        val player = esper.player
        val up = Vector(0,1,0)
        val forwardNorm = direction.clone().normalize()
        // 올바른 오른쪽 벡터: up x forward
        val right = up.clone().crossProduct(forwardNorm).normalize()
        val spacing = 0.5
        val centerBias = -0.5

        val half = concept.waveWidth / 2
        val mats = arrayOf(
            Material.LIGHT_BLUE_GLAZED_TERRACOTTA.createBlockData(),
            Material.CYAN_GLAZED_TERRACOTTA.createBlockData(),
            Material.BLUE_GLAZED_TERRACOTTA.createBlockData()
        )

        // 3D 곡면 파도 생성
        for (zIdx in 0 until DEPTH_COUNT) {
            for (xIdx in -half..half) {
                val base = player.location.clone().add(0.0, 0.05, 0.0)
                val lateral = (xIdx * spacing + centerBias)
                val spawnLoc = base.clone()
                    .add(right.clone().multiply(lateral))
                    .add(forwardNorm.clone().multiply(-zIdx * 0.4))

                val blockData = mats.random()
                val bd = player.world.spawn(spawnLoc, BlockDisplay::class.java) { d ->
                    d.block = blockData
                    d.isGlowing = false
                    d.interpolationDelay = 0
                    d.interpolationDuration = 1

                    // 블록 크기를 약간 다양하게
                    val scale = 0.85f + Math.random().toFloat() * 0.3f
                    runCatching {
                        val trans = d.transformation
                        trans.scale.set(scale, scale, scale)
                        d.transformation = trans
                    }
                }
                wavePieces += WavePiece(xIdx, zIdx, bd)
            }
        }
    }

    private fun tickWave() {
        val player = esper.player
        if (!running || crashing) return
        if (!player.isValid) { stopWave(); return }
        tick++

        val forward = direction.clone()

        // 플레이어 전방 충돌/턱 판정
        run {
            val ahead = player.location.clone().add(forward.multiply(0.6))
            val feet = ahead.block
            val above = ahead.clone().add(0.0,1.0,0.0).block
            if (feet.type.isSolid && !above.type.isSolid) {
                player.velocity = player.velocity.apply { y = STEP_UP_VELOCITY }
            } else if (feet.type.isSolid && above.type.isSolid) {
                finish(true); return
            }
        }

        // 파도 정상 높이 계산 먼저 (공중 시전 보정 필요)
        val crestHeight = calculateWaveCrestHeight()
        val groundY = findGroundY(player.location)
        val baseY = if (groundY != null) {
            groundY + 0.05
        } else {
            // 공중: 플레이어 아래 지면 못 찾음 -> 플레이어 현재 높이보다 낮게 파도 뿌리 위치를 설정해 초기 상승 제거
            player.location.y - crestHeight - 10
        }
        baseLocation = org.bukkit.Location(player.world, player.location.x, baseY, player.location.z, player.location.yaw, 0f)
        val targetY = baseY + crestHeight
        val dy = targetY - player.location.y
        val vy = when {
            dy > 0.18 -> (dy * 0.38).coerceAtMost(0.55)
            dy < -0.25 -> (dy * 0.34).coerceAtLeast(-0.9)
            else -> dy * 0.28
        }

        // 수평 속도 고정
        val vel = player.velocity
        vel.x = forward.x * concept.waveSpeed
        vel.z = forward.z * concept.waveSpeed
        vel.y = vy
        player.velocity = vel

        // 파도 디스플레이 갱신 (baseLocation 사용)
        updateWaveDisplays()

        // 충돌 AABB 체크
        val aabb = computeWaveBox()
        for (ent in player.world.getNearbyEntities(aabb)) {
            if (ent is LivingEntity && TargetFilter(player).test(ent)) {
                ent.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, concept.slowDuration, concept.slowAmplifier, true, true, true))
                finish(true)
                return
            }
        }

        // 시각 효과
        if (tick % 3 == 0) player.world.playSound(player.location, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_INSIDE, 0.3f,1.4f)
        player.world.spawnParticle(Particle.BUBBLE_POP, player.location.clone().add(0.0,0.25,0.0), 2, 0.25,0.1,0.25,0.0)

        if (tick >= concept.durationTime / 50L) finish(false)
    }

    private fun findGroundY(loc: org.bukkit.Location): Double? {
        val w = loc.world
        val startY = loc.blockY
        for (dy in 0..100) {
            val y = startY - dy
            if (y < w.minHeight) break
            val block = w.getBlockAt(loc.blockX, y, loc.blockZ)
            // 물도 지면으로 인식
            if (block.type.isSolid || block.type == Material.WATER) return y + 1.0
        }
        return null
    }

    private fun calculateWaveCrestHeight(): Double {
        return concept.waveAmplitude
    }

    private fun updateWaveDisplays() {
        val base = baseLocation ?: return
        val up = Vector(0,1,0)
        val forwardNorm = direction.clone().normalize()
        // 오른쪽 벡터
        val right = up.clone().crossProduct(forwardNorm).normalize()
        val spacing = 0.5
        val centerBias = -0.5

        val half = concept.waveWidth / 2
        for (piece in wavePieces) {
            val xIdx = piece.xIndex
            val zIdx = piece.zIndex

            val xNorm = if (half > 0) xIdx.toDouble() / half.toDouble() else 0.0
            val zNorm = zIdx.toDouble() / (DEPTH_COUNT - 1).coerceAtLeast(1)

            val heightFactor = (1.0 - zNorm).coerceAtLeast(0.0)
            val widthFactor = (1.0 - kotlin.math.abs(xNorm) * 0.25).coerceAtLeast(0.0)
            val yHeight = concept.waveAmplitude * heightFactor * widthFactor

            val forwardSkew = yHeight * 0.35
            val xSpread = (xIdx * spacing + centerBias)
            val zOffset = -zIdx * 0.4 + forwardSkew

            val target = base.clone()
                .add(right.clone().multiply(xSpread))
                .add(0.0, yHeight, 0.0)
                .add(forwardNorm.clone().multiply(zOffset))

            piece.display.teleport(target)
        }
    }

    private fun computeWaveBox(): BoundingBox {
        if (wavePieces.isEmpty()) return BoundingBox(0.0,0.0,0.0,0.0,0.0,0.0)
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (wp in wavePieces) {
            val l = wp.display.location
            if (l.x < minX) minX = l.x; if (l.y < minY) minY = l.y; if (l.z < minZ) minZ = l.z
            if (l.x > maxX) maxX = l.x; if (l.y > maxY) maxY = l.y; if (l.z > maxZ) maxZ = l.z
        }
        return BoundingBox(minX-0.6, minY-0.2, minZ-0.6, maxX+0.6, maxY+1.4, maxZ+0.6)
    }

    private fun finish(hit: Boolean) {
        if (crashing) return // 이미 붕괴 진행 중
        val player = esper.player
        if (hit) {
            crashWave()
        } else {
            running = false
            task?.cancel(); task = null
            val v = player.velocity; v.y = UPWARD_VELOCITY; player.velocity = v
            fallImmune = true // 튀어 오르는 순간부터 착지 전까지 면역
            player.world.playSound(player.location, Sound.ENTITY_DOLPHIN_JUMP,1f,1.0f)
            player.world.spawnParticle(Particle.SPLASH, player.location, 25, 1.2,0.4,1.2,0.05)
            playWaveDebris(direction.clone())
            stopWave()
        }
    }

    private fun crashWave() {
        if (crashing) return
        crashing = true
        running = false
        task?.cancel(); task = null
        val player = esper.player
        val crashCenter = baseLocation?.clone() ?: player.location.clone()

        // 사운드 & 파티클 유지하되 힘 조정
        player.world.playSound(crashCenter, Sound.ENTITY_GENERIC_SPLASH, 2.0f, 0.6f)
        player.world.playSound(crashCenter, Sound.ITEM_BUCKET_EMPTY, 1.6f, 0.8f)
        player.world.playSound(crashCenter, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 1.3f, 0.95f)
        player.world.spawnParticle(Particle.SPLASH, crashCenter.clone().add(0.0,0.55,0.0),100,concept.crashRadius,concept.crashRadius,concept.crashRadius,0.24)
        player.world.spawnParticle(Particle.BUBBLE, crashCenter.clone().add(0.0,0.4,0.0),50,concept.crashRadius,concept.crashRadius,concept.crashRadius,0.15)
        player.world.spawnParticle(Particle.BUBBLE_POP, crashCenter.clone().add(0.0,0.9,0.0),50,concept.crashRadius,concept.crashRadius,concept.crashRadius,0.10)

        // 피해/슬로 1회 적용
        val radius = concept.crashRadius
        player.world.getNearbyEntities(crashCenter, radius, radius, radius).forEach { ent ->
            if (ent is LivingEntity && TargetFilter(player).test(ent)) {
                ent.psychicDamage(knockbackLocation = crashCenter, knockback = concept.crashKnockback * 0.85)
                ent.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, concept.slowDuration, concept.slowAmplifier, true, true, true))
            }
        }

        // 플레이어 위로 튕김 및 낙뎀 면역 시작
        player.velocity = player.velocity.apply { y = UPWARD_VELOCITY * 0.85 }
        fallImmune = true

        playWaveDebris(direction.clone())
    }

    private fun playWaveDebris(forward: Vector) {
        val forwardNorm = forward.apply { y = 0.0 }.normalize()
        if (forwardNorm.lengthSquared() == 0.0) return

        data class Flying(
            val piece: WavePiece,
            var vel: Vector
        )
        val flying = mutableListOf<Flying>()
        val rand = java.util.concurrent.ThreadLocalRandom.current()

        for (piece in wavePieces) {
            val hSpeed = 0.45 + rand.nextDouble() * 0.35
            val up = 0.25 + rand.nextDouble() * 0.25
            val vel = forwardNorm.clone().multiply(hSpeed).add(Vector(0.0, up, 0.0))
            flying += Flying(piece, vel)
        }

        sinkTask?.cancel(); sinkTask = null
        sinkTask = psychic.runTaskTimer(object : Runnable {
            var life = 0
            override fun run() {
                life++
                var aliveAny = false
                flying.forEach { f ->
                    val d = f.piece.display
                    if (!d.isValid || d.isDead) return@forEach

                    val l = d.location
                    f.vel.y -= 0.07
                    f.vel.x *= 0.95
                    f.vel.z *= 0.95
                    val newLoc = l.clone().add(f.vel)
                    val below = newLoc.clone().add(0.0, -0.35, 0.0).block
                    if (below.type.isSolid || below.type == Material.WATER || life > 26) {
                        d.world.spawnParticle(Particle.SPLASH, newLoc, 4, 0.2,0.12,0.2,0.03)
                        d.remove()
                    } else {
                        d.teleport(newLoc)
                        if (life % 4 == 0) d.world.spawnParticle(Particle.FALLING_WATER, newLoc, 1, 0.03,0.03,0.03,0.0)
                        aliveAny = true
                    }
                }
                if (!aliveAny) stopWave()
            }
        }, 0L, 1L)
    }

    private fun stopWave() {
        task?.cancel(); task = null
        sinkTask?.cancel(); sinkTask = null
        running = false
        crashing = false
        wavePieces.forEach { runCatching { it.display.remove() } }
        wavePieces.clear()
        baseLocation = null
    }

    @EventHandler
    fun onFallDamage(e: org.bukkit.event.entity.EntityDamageEvent) {
        if (e.cause != org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL) return
        if (fallImmune) {
            e.isCancelled = true
            fallImmune = false
        }
    }

    @EventHandler
    fun onPlayerMove(e: PlayerMoveEvent) {
        if (!fallImmune) return
        val to = e.to
        val below = to.clone().add(0.0, -0.1, 0.0).block
        if (below.type.isSolid) {
            psychic.runTask({ fallImmune = false }, 10L)
        }
    }
}
