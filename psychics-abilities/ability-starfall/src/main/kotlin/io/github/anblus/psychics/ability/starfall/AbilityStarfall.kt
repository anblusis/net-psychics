package io.github.anblus.psychics.ability.starfall

import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.fake.FakeEntity
import io.github.monun.tap.fake.Movement
import io.github.monun.tap.fake.Trail
import io.github.monun.tap.math.normalizeAndLength
import io.github.monun.tap.trail.TrailSupport
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.math.*
import kotlin.random.Random

@Name("starfall")
class AbilityConceptStarfall : AbilityConcept() {
    @Config var minStarsPerCast = 6                 // 별 최소 개수
    @Config var maxStarsPerCast = 30              // 별 최대 개수

    @Config var speedBlocksPerTick = 3.0          // 낙하(이동) 속도 (block/tick)

    @Config var minDist = 8.0                     // 각도 보간용 최소 거리

    @Config var minAngleDeg = 20.0                // 최소 거리일 때 좌우 최대 각도
    @Config var maxAngleDeg = 60.0                // 최대 거리일 때 좌우 최대 각도

    @Config var explosionRadius = 3.0             // 착지 폭발 반경
    @Config var explosionPower = 2.0f              // 착지 폭발 위력

    init {
        displayName = "별똥별"
        type = AbilityType.ACTIVE
        cost = 50.0
        cooldownTime = 5000L
        durationTime = 5000L
        range = 64.0
        damage = Damage.of(DamageType.BLAST, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 6.0))
        description = listOf(
            text("능력 사용 시 해당 블록을 중심으로 하늘에서 별을 떨어뜨립니다."),
            text("별은 지면에 닿으면 폭발하고 소규모 광역 피해를 줍니다."),
            text("먼 곳에서 능력을 사용할수록 별이 더 많이 떨어집니다.")
        )
        wand = ItemStack(Material.ECHO_SHARD)
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(minDist) { NamedTextColor.BLUE to "최소 사거리" to "블록" }
        tooltip.stats(minStarsPerCast) { NamedTextColor.YELLOW to "최소 별 개수" to "개" }
        tooltip.stats(maxStarsPerCast) { NamedTextColor.GREEN to "최대 별 개수" to "개" }
    }
}

class AbilityStarfall : ActiveAbility<AbilityConceptStarfall>(), Listener {

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onInitialize() {
        targeter = {
            val player = esper.player
            val start = player.eyeLocation
            val world = start.world
            world.rayTraceBlocks(
                start,
                start.direction,
                concept.range,
                FluidCollisionMode.NEVER,
                true
            )?.hitPosition?.toLocation(world)?.let { hit ->
                val dist = start.clone().apply { y = hit.y }.distance(hit)
                if (concept.minDist <= dist && dist <= concept.range) hit else null
            }
        }
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        val player = esper.player
        val world = player.world
        val hit = target as Location

        // 마나 소모 및 쿨다운 적용
        exhaust()

        // 시작 이펙트 & 사운드
        world.spawnParticle(Particle.FIREWORKS_SPARK, player.location, 80, 0.8, 0.3, 0.8, 0.02)
        world.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.8f, 1.0f)

        val d = player.location.clone().apply { y = hit.y }.distance(hit)
        val dRatio = ((d - concept.minDist) / (concept.range - concept.minDist))

        val starCount = (concept.minStarsPerCast + (concept.maxStarsPerCast - concept.minStarsPerCast) * dRatio).toInt()
        val thetaRad = Math.toRadians(concept.minAngleDeg + (concept.maxAngleDeg - concept.minAngleDeg) * dRatio)

        val totalTicks = concept.durationTime / 50L // 밀리초를 틱으로 변환
        val interval = totalTicks / starCount // 별 하나당 간격 (틱)

        val location = player.location.clone()

        // 각 별을 일정 간격으로 떨어뜨림
        repeat(starCount) { index ->
            psychic.runTask({
                spawnStar(hit, location, d, thetaRad)
            }, interval * index)
        }
    }

    private fun spawnStar(hit: Location, location: Location, distance: Double, angle: Double) {
        val forward = location.direction.clone().setY(0.0).normalize()

        val randomAngle = Random.nextDouble(-angle, angle)
        val rotated = forward.clone().rotateAroundY(randomAngle)

        // 목표 XZ 위치 = 플레이어 위치 + 회전 전방 * d, 목표 Y는 사용자가 조준한 hit의 Y
        val targetXZ = location.clone().add(rotated.clone().multiply(distance))
        val targetLoc = Location(location.world, targetXZ.x, hit.y, targetXZ.z)

        // 시작 위치: 플레이어 위 + 좌우 지터
        val jitter = Vector(
            Random.nextDouble(-distance, distance),
            0.0,
            Random.nextDouble(-distance, distance)
        )

        val startLoc = location.clone().add(0.0, concept.range*2, 0.0).add(jitter)

        val targetDirection = targetLoc.clone().subtract(startLoc).toVector().normalize()
        targetLoc.direction = targetDirection

        TrailSupport.trail(startLoc, targetLoc.clone().add(targetLoc.direction.clone().multiply(10)), 0.5) { w, x, y, z ->
            w.spawnParticle(Particle.SPELL_WITCH, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
        }

        // FakeEntity(별) 준비
        val starEntity = psychic.spawnFakeEntity(startLoc, ArmorStand::class.java).apply {
            updateMetadata {
                isVisible = false
                isMarker = true
            }
            updateEquipment { helmet = ItemStack(Material.NETHER_STAR) }
        }

        val projectile = StarProjectile(starEntity)
        psychic.launchProjectile(startLoc, projectile)

        projectile.velocity = targetLoc.direction.clone().multiply(concept.speedBlocksPerTick)

        location.world.playSound(targetLoc, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.PLAYERS, 0.8f, 1.8f)
    }

    private inner class StarProjectile(
        private val star: FakeEntity<ArmorStand>
    ) : PsychicProjectile(1200, 1000.0) {
        private var yaw = Random.nextFloat() * 360f
        private val rotationRadius = 0.33 // 회전 반경 (아머스탠드 머리 크기에 맞춤)

        override fun onMove(movement: Movement) {
            val to = movement.to
            // 아머스탠드 전체를 yaw 회전시켜 자전 효과
            yaw += 36f // 매 틱마다 36도씩 회전 (1초에 약 2바퀴)
            if (yaw >= 360f) yaw -= 360f

            // yaw 각도에 따른 원형 오프셋 계산 (제자리 회전처럼 보이게)
            val yawRad = Math.toRadians(yaw.toDouble())
            val offsetX = -sin(yawRad) * rotationRadius
            val offsetZ = cos(yawRad) * rotationRadius

            val spinLoc = to.clone().apply {
                // 중심점에서 yaw에 따라 원형으로 오프셋
                x += offsetX
                z += offsetZ
                y -= 1.8
                this.yaw = this@StarProjectile.yaw
                pitch = 0f
            }
            star.moveTo(spinLoc)

            // 꼬리 파티클
            to.world.spawnParticle(Particle.END_ROD, movement.from, 0, 0.0, 0.0, 0.0, 0.1)
        }

        override fun onTrail(trail: Trail) {
            trail.velocity?.let { velocity ->
                val from = trail.from
                val length = velocity.normalizeAndLength()

                val world = from.world
                // 블록 충돌 감지 (rayCast)
                world.rayTraceBlocks(
                    from,
                    velocity,
                    length,
                    FluidCollisionMode.NEVER,
                    true
                )?.let { rayTraceResult ->
                    // 블록과 충돌 시 해당 위치에서 폭발
                    val hitLocation = rayTraceResult.hitPosition.toLocation(world)
                    explodeAndRemove(hitLocation)
                }
            }
        }

        override fun onRemove() {
            star.remove()
        }

        private fun explodeAndRemove(loc: Location) {
            val world = loc.world

            // 바닐라 폭발 알고리즘 재현
            val explosionPower = concept.explosionPower
            vanillaExplosion(loc, explosionPower)

            // 사운드 & 파티클
            world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 0.8f, 1.2f)
            world.spawnParticle(Particle.EXPLOSION_NORMAL, loc, 1, 0.0, 0.0, 0.0, 0.0)
            world.spawnParticle(Particle.CRIT, loc, 12, 0.6, 0.3, 0.6, 0.02)
            world.spawnParticle(Particle.GLOW, loc, 6, 0.5, 0.2, 0.5, 0.01)

            // 피해 처리 (적 필터)
            val r = concept.explosionRadius
            val player = esper.player
            val filter = TargetFilter(player)
            world.getNearbyEntities(loc, r, r, r, filter).forEach { e ->
                if (e is LivingEntity) {
                    e.psychicDamage(knockbackLocation = loc, knockback = 0.6)
                    e.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, 100, 0, false, false))
                }
            }

            remove()
        }

        private fun vanillaExplosion(center: Location, power: Float) {
            val world = center.world
            val blocksToDestroy = mutableSetOf<Block>()

            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4.0f, (0.8f + Random.nextFloat() * 0.4f))

            // power에 따른 폭발 이펙트
            val particleCount = (power * 4).toInt()
            val particleRadius = power * 0.5

            world.spawnParticle(Particle.EXPLOSION_NORMAL, center, particleCount, particleRadius, particleRadius, particleRadius, 0.1)

            // 바닐라는 16x16x16 = 4096개 광선 사용
            val rays = 8
            val rayStep = 0.3

            for (x in 0 until rays) {
                for (y in 0 until rays) {
                    for (z in 0 until rays) {
                        // 큐브 표면에 있는 광선만 계산 (최적화)
                        if (x != 0 && x != rays - 1 && y != 0 && y != rays - 1 && z != 0 && z != rays - 1) {
                            continue
                        }

                        // 광선 방향 계산 (-1 ~ 1 범위로 정규화)
                        val dirX = (x.toDouble() / (rays - 1) * 2.0 - 1.0)
                        val dirY = (y.toDouble() / (rays - 1) * 2.0 - 1.0)
                        val dirZ = (z.toDouble() / (rays - 1) * 2.0 - 1.0)

                        val length = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
                        val normalizedX = dirX / length
                        val normalizedY = dirY / length
                        val normalizedZ = dirZ / length

                        var currentPower = power * (0.7 + Random.nextDouble() * 0.6)

                        var rayX = center.x
                        var rayY = center.y
                        var rayZ = center.z

                        // 광선을 따라 이동하며 블록 파괴 판정
                        while (currentPower > 0.0) {
                            val blockX = rayX.toInt()
                            val blockY = rayY.toInt()
                            val blockZ = rayZ.toInt()

                            val block = world.getBlockAt(blockX, blockY, blockZ)

                            if (!block.type.isAir) {
                                // 블록 저항력 계산 (바닐라 공식)
                                val resistance = block.type.blastResistance
                                currentPower -= (resistance + 0.3) * 0.3

                                if (currentPower > 0.0) {
                                    blocksToDestroy.add(block)
                                }
                            }

                            // 광선 이동
                            rayX += normalizedX * rayStep
                            rayY += normalizedY * rayStep
                            rayZ += normalizedZ * rayStep

                            // 폭발 범위 초과 체크
                            if (center.distance(Location(world, rayX, rayY, rayZ)) > power * 2) {
                                break
                            }
                        }
                    }
                }
            }

            // 수집된 블록들을 파괴
            blocksToDestroy.forEach { block ->
                // 플레이어가 파괴할 수 없는 블록은 제외
                if (!block.type.isAir && block.type != Material.BEDROCK) {
                    block.type = Material.AIR
                }
            }
        }
    }
}
