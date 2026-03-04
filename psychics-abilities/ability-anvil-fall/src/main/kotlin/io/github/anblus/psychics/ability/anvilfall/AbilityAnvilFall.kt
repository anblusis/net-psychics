package io.github.anblus.psychics.ability.anvilfall

import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.damage.psychicDamage
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.task.TickerTask
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.FallingBlock
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector

// 바라보는 곳에 모루 낙하~
@Name("anvil-fall")
class AbilityConceptAnvilFall : AbilityConcept() {

    @Config
    val anvilFallingRange = 1 // 모루 중심으로 파괴할 블럭 반경(정사각형)

    @Config
    val anvilDurability = 200.0 // 블록을 파괴할 때마다 blastResistance 만큼 내구도 감소

    @Config
    val anvilDurabilityDamagePerFall = 15.0 // 낙하할 때마다 내구도 감소량

    @Config
    val spawnMinHeight = 4.0 // 타겟 위 최소 생성 높이

    @Config
    val spawnMaxHeight = 8.0 // 타겟 위 생성 높이

    init {
        displayName = "모루 낙하"
        type = AbilityType.ACTIVE
        cost = 15.0
        cooldownTime = 3000L
        range = 16.0
        damage = Damage.of(DamageType.MELEE, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 2.0))
        description = listOf(
            text("바라보는 곳 위에 모루를 생성해 떨어뜨립니다."),
            text("모루는 땅에 닿을 때마다 블록에 따른 내구도를 소모해 블록을 부숩니다."),
            text("모루가 땅에 닿을 때 근처에 있는 적들은 내구도에 비례한 피해를 받습니다."),
            text("내구도가 0이 되면 모루는 부서집니다.")
        )
        wand = ItemStack(Material.IRON_INGOT)
    }
}

class AbilityAnvilFall : ActiveAbility<AbilityConceptAnvilFall>(), Listener {
    private var task: TickerTask? = null
    private var anvil: FallingBlock? = null
    private var durability = 0.0
    private var facing: BlockFace = BlockFace.NORTH

    init {
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
            )?.hitBlock?.let { hit ->
                val aboveStart = hit.location.clone().add(0.5, 1.0, 0.5)
                val upHit = world.rayTraceBlocks(
                    aboveStart,
                    Vector(0.0, 1.0, 0.0),
                    concept.spawnMaxHeight,
                    FluidCollisionMode.NEVER,
                    true
                )?.hitBlock

                if(upHit != null && upHit.location.y < aboveStart.y + concept.spawnMinHeight) {
                    player.sendActionBar(text().content("모루를 생성할 공간이 부족합니다.").decorate(TextDecoration.BOLD).build())
                    return@let null
                }

                upHit?.location?.clone()?.add(0.5, -1.0, 0.5) ?: hit.location.clone().add(0.5, concept.spawnMaxHeight, 0.5)
            }

        }
    }

    override fun onDisable() {
        stop()
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        if (target !is Location) return

        exhaust()

        stop()

        durability = concept.anvilDurability
        val world = target.world

        val spawn = target.clone()
        val faces = listOf(
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
        )
        facing = faces.random()

        val fb = spawnAnvil(spawn, Material.ANVIL)
        anvil = fb

        world.playSound(spawn, Sound.BLOCK_ANVIL_PLACE, 1.2f, 0.8f)

        task = psychic.runTaskTimer(this::tickAnvil, 0L, 1L)
    }

    private fun tickAnvil() {
        val fb = anvil
        if (fb == null || !fb.isValid) {
            stop()
            return
        }

        val world = fb.world
        val loc = fb.location
        val belowBlock = world.getBlockAt(loc.blockX, (loc.y - 0.7).toInt(), loc.blockZ)
        if (!belowBlock.type.isSolid) return

        val range = concept.anvilFallingRange.toDouble()
        world.playSound(loc, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.7f)
        world.spawnParticle(Particle.BLOCK, loc, 18, range, 1.0, range, belowBlock.blockData)

        val damage = concept.damage ?: return
        val damageType = damage.type
        val damageAmount = damage.stats.let { esper.getStatistic(it) } * durability / concept.anvilDurability
        val damageLocation = belowBlock.location.clone().add(0.5, 1.0, 0.5)

        world.getNearbyLivingEntities(damageLocation, range + 0.5, 1.0, range + 0.5) { esper.player.hostileFilter().test(it) }
            .forEach { e ->
                e.psychicDamage(
                    this,
                    damageType,
                    damageAmount,
                    esper.player
                )
            }

        val impactY = belowBlock.y
        val centerX = belowBlock.x
        val centerZ = belowBlock.z
        val r = concept.anvilFallingRange

        val breakBlocks = mutableListOf<Block>()

        for (dx in -r..r) {
            for (dz in -r..r) {
                val b = world.getBlockAt(centerX + dx, impactY, centerZ + dz)
                breakBlocks.add(b)
            }
        }

        breakBlocks.shuffle()

        for (block in breakBlocks) {
            val type = block.type
            if (!type.isSolid) continue

            durability -= type.blastResistance.toDouble()

            if (durability < 0.0) break

            block.world.spawnParticle(Particle.BLOCK, block.location.clone().add(0.5, 0.5, 0.5), 10, 0.25, 0.25, 0.25, 0.0, block.blockData)
            block.breakNaturally(ItemStack(Material.AIR))
        }

        durability -= concept.anvilDurabilityDamagePerFall

        if (durability <= 0.0) {
            world.playSound(loc, Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f)
            stop()
            return
        }

        // esper.player.sendMessage(text("모루의 내구도가 ${"%.1f".format(durability)} 남았습니다."))

        val ratio = (durability / concept.anvilDurability).coerceIn(0.0, 1.0)
        val type = when {
            ratio > 0.66 -> Material.ANVIL
            ratio > 0.33 -> Material.CHIPPED_ANVIL
            else -> Material.DAMAGED_ANVIL
        }

        val vel = fb.velocity

        fb.remove()
        val newFb = spawnAnvil(fb.location, type)
        newFb.velocity = vel.multiply(0.5)
        anvil = newFb
    }

    private fun spawnAnvil(loc: Location, type: Material): FallingBlock {
        val data = type.createBlockData() as org.bukkit.block.data.Directional
        data.facing = facing
        return loc.world.spawnFallingBlock(loc, data).apply {
            dropItem = false
            setHurtEntities(false)
        }
    }

    private fun stop() {
        task?.cancel()
        task = null

        anvil?.let {
            if (it.location.block.type == Material.ANVIL) it.location.block.type = Material.AIR
            runCatching { it.remove() }
        }
        anvil = null
        durability = 0.0
        facing = BlockFace.NORTH
    }
}
