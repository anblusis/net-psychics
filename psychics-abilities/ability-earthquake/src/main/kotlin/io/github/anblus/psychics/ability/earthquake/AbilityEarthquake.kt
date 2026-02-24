package io.github.anblus.psychics.ability.earthquake

import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import java.util.*
import kotlin.math.*

@Name("earthquake")
class AbilityConceptEarthquake : AbilityConcept() {
    @Config var fanAngleDeg = 30.0           // 부채꼴 각도(전체)
    @Config var maxBlastResistance = 10.0     // 이 값 이하만 들어올림
    @Config var ringDelayPerBlock = 1.5      // 중심에서 1블록당 지연(틱)
    @Config var liftBaseVelocity = 0.6       // 블록 상승 기본 속도(Y)
    @Config var horizontalJitter = 0.0      // 블록 수평 흔들림
    @Config var slowTicks = 50              // 느려짐 지속(틱)
    @Config var slowAmplifier = 1            // 느려짐 레벨(0부터)
    @Config var heightDropLimit = 2.5        // 인접 구간 낙차가 이 값보다 크면 그 레이 중단

    init {
        displayName = "지진"
        type = AbilityType.ACTIVE
        cost = 30.0
        cooldownTime = 10000L
        range = 15.0
        damage = Damage.of(DamageType.BLAST, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 1.0))
        description = listOf(
            text("블록에 대고 사용 시 바라보는 방향으로 지진을 일으킵니다."),
            text("지진은 경로상의 블록과 적을 들어 올리고 피해를 입힙니다.")
        )
        wand = ItemStack(Material.BRICK)
    }
}

class AbilityEarthquake : ActiveAbility<AbilityConceptEarthquake>(), Listener {

    override fun onInitialize() {
        targeter = {
            val player = esper.player
            val start = player.eyeLocation
            val world = start.world

            world.rayTraceBlocks(
                start,
                start.direction,
                5.0,
                FluidCollisionMode.NEVER,
                true,
            )?.hitPosition
        }
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        exhaust()

        target as Vector

        val player = esper.player
        val world = player.world
        val forward = target.clone().subtract(player.location.toVector()).setY(0.0).normalize()

        world.playSound(player.location, Sound.BLOCK_STONE_PLACE, SoundCategory.PLAYERS, 1.0f, 0.7f)
        world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.6f, 1.2f)

        propagateFanRings(
            world,
            target.toLocation(world),
            atan2(forward.z, forward.x),
            concept.range,
            Math.toRadians(concept.fanAngleDeg / 2.0),
            mutableSetOf()
        )
    }

    private fun propagateFanRings(world: World, origin: Location, headingAngle: Double, range: Double, half: Double, victimsHit: MutableSet<UUID>) {
        val ox = origin.blockX
        val oz = origin.blockZ

        val rayStepDeg = 6.0
        val startDeg = -Math.toDegrees(half)
        val endDeg = Math.toDegrees(half)
        val degList = generateSequence(startDeg) { it + rayStepDeg }.takeWhile { it <= endDeg + 1e-6 }.toList()

        // 각 레이마다 표면 높이 추적하여 낙차 체크
        val prevSurfaceY = DoubleArray(degList.size) { origin.y }
        val stopped = BooleanArray(degList.size) { false }

        val step = 1.0
        var r = 0.0
        while (r <= range + 1e-6) {
            for ((i, deg) in degList.withIndex()) {
                if (stopped[i]) continue
                val theta = headingAngle + Math.toRadians(deg)
                val x = floor(ox + cos(theta) * r).toInt()
                val z = floor(oz + sin(theta) * r).toInt()

                // y 좌표를 아래에서 위로 올려가면서 표면 찾기
                val minY = (prevSurfaceY[i] - concept.heightDropLimit).toInt()
                val maxY = (prevSurfaceY[i] + concept.heightDropLimit).toInt() + 1
                var surfaceY = -99.0
                var solidFound = false
                for (y in minY .. maxY) {
                    val checkBlock = world.getBlockAt(x, y, z)
                    if (checkBlock.type.isSolid) {
                        solidFound = true
                    } else if (solidFound) {
                        surfaceY = (y - 1).toDouble()
                        break
                    }
                }

                if (surfaceY == -99.0) {
                    stopped[i] = true
                    continue
                }

                prevSurfaceY[i] = surfaceY

                val block = world.getBlockAt(x, surfaceY.toInt(), z)
                val resistance = block.type.blastResistance.toDouble()
                if (resistance <= concept.maxBlastResistance) {
                    val delay = (concept.ringDelayPerBlock * r).toLong()
                    scheduleLiftTop(world, block, delay, victimsHit)
                } else {
                    stopped[i] = true
                }
            }

            r += step
        }
    }

    private fun scheduleLiftTop(world: World, block: Block, delay: Long, victimsHit: MutableSet<UUID>) {
        val data = block.blockData
        val baseLoc = block.location.add(0.5, 0.5, 0.5)
        psychic.runTask({
            world.spawnParticle(Particle.BLOCK_CRACK, baseLoc, 7, 0.35, 0.35, 0.35, 0.0, data)
            // 원본 블록 임시 제거 (중복 방지, 착지 시 다시 놓임)
            block.setType(Material.AIR, false)
            val spawnLoc = baseLoc.clone().add(0.0, 0.51, 0.0)
            val fb: FallingBlock = world.spawnFallingBlock(spawnLoc, data)
            fb.dropItem = false
            fb.setHurtEntities(false)

            val damageCenter = baseLoc.clone().add(0.0, 1.5, 0.0)
            val victims = world.getNearbyEntities(damageCenter, 0.75, 1.5, 0.75, TargetFilter(esper.player))
            victims.forEach { ent ->
                if (ent is LivingEntity && victimsHit.add(ent.uniqueId)) {
                    ent.psychicDamage()
                    ent.velocity = ent.velocity.add(Vector(0.0, concept.liftBaseVelocity * 2.0, 0.0))
                    ent.addPotionEffect(PotionEffect(PotionEffectType.SLOW, concept.slowTicks, concept.slowAmplifier, false, false, true))
                }
            }

            // FallingBlock 상승
            val vx = (Math.random() - 0.5) * 2 * concept.horizontalJitter
            val vz = (Math.random() - 0.5) * 2 * concept.horizontalJitter
            val vy = concept.liftBaseVelocity
            fb.velocity = Vector(vx, vy, vz)
        }, delay)
    }
}
