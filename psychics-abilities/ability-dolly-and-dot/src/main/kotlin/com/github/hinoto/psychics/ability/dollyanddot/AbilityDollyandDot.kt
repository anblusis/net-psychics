package com.github.hinoto.psychics.ability.dollyanddot

import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.ActiveAbility
import io.github.monun.psychics.PsychicProjectile
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.fake.FakeEntity
import io.github.monun.tap.fake.Movement
import io.github.monun.tap.fake.Trail
import io.github.monun.tap.math.normalizeAndLength
import io.github.monun.tap.math.toRadians
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import java.io.Console
import kotlin.math.cos
import kotlin.math.sin

@Name("dolly-and-dot")
class AbilityConceptDollyAndDot : AbilityConcept() {
    @Config
    var collisionRange : Double = 2.0

    init {
        displayName = "돌리랑 도트"
        wand = ItemStack(Material.ACACIA_BOAT)
        type = AbilityType.ACTIVE
        description = listOf(
            Component.text("돌리랑 도트가 제일 좋아, 돌리랑 도트가 제일 좋아"),
            Component.text("돌리랑 도트를 소환해 돌진시켜 전방에 모래길을 만듭니다.")
        )
        knockback = 3.0
        durationTime = 3000L
        cooldownTime = 30000L
        damage = Damage.of(DamageType.MELEE, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 1.0))
        knockback = 3.0
    }
}

class AbilityDollyAndDot : ActiveAbility<AbilityConceptDollyAndDot>(), Listener {

    var dolly : LivingEntity? = null
    var dot : LivingEntity? = null
    var world : World? = null

    var vector : Vector = Vector(0,0,0)

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {

        val loc = event.player.location
        vector = loc.direction
        world = loc.world

        //Bukkit.broadcast(Component.text(loc.toString()))

        dolly = event.player.world.spawnEntity(loc.clone().apply {
            x = loc.x + cos(loc.yaw.toDouble().toRadians());
            z = loc.z + sin(loc.yaw.toDouble().toRadians())
        }, EntityType.LLAMA) as LivingEntity
        dot = event.player.world.spawnEntity(loc.clone().apply {
            x = loc.x - cos(loc.yaw.toDouble().toRadians())
            z = loc.z - sin(loc.yaw.toDouble().toRadians())
        }, EntityType.LLAMA) as LivingEntity

        dolly!!.setRotation(loc.yaw, loc.pitch)
        dot!!.setRotation(loc.yaw, loc.pitch)
        dolly!!.setGravity(false)
        dot!!.setGravity(false)
        dolly!!.setAI(false)
        dot!!.setAI(false)
        dolly!!.isInvulnerable = true
        dot!!.isInvulnerable = true

        val projectile = DollyDotProjectile().apply {
            dollydot =
                this@AbilityDollyAndDot.psychic.spawnFakeEntity(loc, ArmorStand::class.java).apply {
                    updateMetadata {
                        isVisible = false
                        isMarker = true
                    }
                }
        }

        psychic.launchProjectile(loc, projectile)
        projectile.velocity = vector

        val task = psychic.runTaskTimer(this::onUpdate, 0L, 1L)
        psychic.runTask(Runnable() {
            task.cancel()
            dolly!!.remove()
            dot!!.remove()
            projectile.remove()
        }, concept.durationTime/1000*20)
        esper.player.sendActionBar(text("돌리랑 도트가 제일 좋아"))
        cooldownTime = concept.cooldownTime
    }

    private fun onUpdate() {
        dolly!!.teleport(dolly!!.location.add(vector))
        dot!!.teleport(dot!!.location.add(vector))
        var sandblocks = listOf<Block>()
        var airblocks = listOf<Block>()
        for(i : Int in -1..1) {
            for(j : Int in -1..1) {
                sandblocks = sandblocks.plus(listOf(
                    dolly!!.location.clone().add(i.toDouble(), -1.0, j.toDouble()).block,
                    dot!!.location.clone().add(i.toDouble(), -1.0, j.toDouble()).block
                ))
                airblocks = airblocks.plus(listOf(
                    dolly!!.location.clone().add(i.toDouble(), 0.0, j.toDouble()).block,
                    dolly!!.location.clone().add(i.toDouble(), 1.0, j.toDouble()).block,
                    dolly!!.location.clone().add(i.toDouble(), 2.0, j.toDouble()).block,
                    dot!!.location.clone().add(i.toDouble(), 0.0, j.toDouble()).block,
                    dot!!.location.clone().add(i.toDouble(), 1.0, j.toDouble()).block,
                    dot!!.location.clone().add(i.toDouble(), 2.0, j.toDouble()).block,
                ))
            }
        }
        for(block in sandblocks) {
            if(block.type != Material.VOID_AIR && block.type != Material.BEDROCK) {
                block.type = Material.SAND
            }
        }
        for(block in airblocks) {
            if (block.type != Material.VOID_AIR && block.type != Material.BEDROCK) {
                block.type = Material.AIR
            }
        }
        val world = dolly!!.location.world

        world.spawnParticle(Particle.BLOCK, dolly!!.location,
            32, 1.0, 1.0, 1.0, 4.0, Material.SAND.createBlockData(), true)
        world.playSound(dolly!!.location, Sound.BLOCK_SAND_STEP, 1.0f, 1.0f)
        world.spawnParticle(Particle.BLOCK, dot!!.location,
            32, 1.0, 1.0, 1.0, 4.0, Material.SAND.createBlockData(), true)
        world.playSound(dot!!.location, Sound.BLOCK_SAND_STEP, 1.0f, 1.0f)
    }

    inner class DollyDotProjectile : PsychicProjectile(60, 60.0) {
        lateinit var dollydot: FakeEntity<ArmorStand>

        override fun onMove(movement: Movement) {
            dollydot.moveTo(movement.to.clone())
        }

        override fun onTrail(trail: Trail) {
            trail.velocity?.let { v ->

                val length = v.normalizeAndLength()

                val start = trail.from
                val world = start.world

                world.rayTrace(
                    start, v, length, FluidCollisionMode.NEVER,
                    true, concept.collisionRange/2.0, TargetFilter(esper.player)
                )?.let { result ->
                    val hitLocation = result.hitPosition.toLocation(world)
                    world.spawnParticle(
                        Particle.EXPLOSION,
                        hitLocation,
                        1
                    )
                    world.playSound(hitLocation, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.0f)

                    result.hitEntity?.let { entity ->
                        if (entity is LivingEntity) {
                            entity.psychicDamage()
                        }
                    }
                }
            }
        }
    }
}