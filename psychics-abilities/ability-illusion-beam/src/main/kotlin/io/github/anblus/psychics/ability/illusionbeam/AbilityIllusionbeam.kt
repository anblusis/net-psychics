package io.github.anblus.psychics.ability.illusionbeam

import io.github.monun.psychics.*
import io.github.monun.tap.config.Name
import net.kyori.adventure.text.Component.text
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.fake.FakeEntity
import io.github.monun.tap.fake.Movement
import io.github.monun.tap.fake.Trail
import io.github.monun.tap.math.normalizeAndLength
import io.github.monun.tap.trail.TrailSupport
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.entity.*
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import kotlin.random.Random.Default

@Name("illusion-beam")
class AbilityConceptIllusionBeam : AbilityConcept() {

    @Config
    val beamSpeed = 2.0

    @Config
    val beamRandomness = 0.2

    init {
        displayName = "환상빔"
        type = AbilityType.ACTIVE
        cost = 15.0
        cooldownTime = 1000L
        castingTime = 500L
        range = 128.0
        damage = Damage.of(DamageType.RANGED, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 1.0))
        description = listOf(
            text("바라보는 방향으로 환상빔을 발사합니다."),
            text("환상빔을 맞은 블록은 무작위 블록으로 바뀝니다."),
            text("환상빔을 맞은 엔티티는 무작위 엔티티로 바뀝니다."),
            text("환상빔을 맞은 플레이어는 약간의 데미지만 입습니다.")
        )
        wand = ItemStack(Material.STICK)
    }
}

class AbilityIllusionBeam : ActiveAbility<AbilityConceptIllusionBeam>(), Listener {

    private var hasCharged: Boolean = false

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onChannel(channel: Channel) {
        if (hasCharged) return
        hasCharged = true
        val player = esper.player
        val world = player.world
        world.spawnParticle(Particle.SPELL_WITCH, player.boundingBox.center.toLocation(world), 10, 0.5, 0.5, 0.5, 0.1)
        world.playSound(player.location, Sound.BLOCK_BEACON_DEACTIVATE, 2.0f, 0.2f)

        player.addPotionEffect(PotionEffect(PotionEffectType.SLOW, (concept.castingTime / 50).toInt(), 4))
    }

    override fun onCast(event: PlayerEvent, action: WandAction, target: Any?) {
        psychic.consumeMana(concept.cost)
        cooldownTime = concept.cooldownTime

        hasCharged = false

        val start = esper.player.eyeLocation
        val direction = start.direction

        val beam = IllusionBeamProjectile(direction).apply {
            fakeEntity = this@AbilityIllusionBeam.psychic.spawnFakeEntity(start, ArmorStand::class.java).apply {
                updateMetadata {
                    isVisible = false
                }
            }
            velocity = direction.clone().multiply(concept.beamSpeed)
        }

        psychic.launchProjectile(start, beam)
        start.world.playSound(start, Sound.BLOCK_PORTAL_TRIGGER, 0.1f, 2.0f)
    }

    inner class IllusionBeamProjectile(
        private val originalDirection: Vector
    ) : PsychicProjectile(1200, concept.range) {

        lateinit var fakeEntity: FakeEntity<ArmorStand>

        override fun onPreUpdate() {
            velocity = originalDirection.clone().apply {
                x += Default.nextDouble(-concept.beamRandomness, concept.beamRandomness)
                y += Default.nextDouble(-concept.beamRandomness, concept.beamRandomness)
                z += Default.nextDouble(-concept.beamRandomness, concept.beamRandomness)
            }.multiply(velocity.length())
        }

        override fun onMove(movement: Movement) {
            fakeEntity.moveTo(movement.to)
        }

        override fun onTrail(trail: Trail) {
            val start = trail.from
            val world = start.world
            val direction = trail.velocity!!.clone()
            val length = direction.normalizeAndLength()

            val hitResult = world.rayTrace(
                start,
                direction,
                length,
                FluidCollisionMode.NEVER,
                true,
                0.3,
                TargetFilter(esper.player)
            )

            if (hitResult != null) {
                val hitBlock = hitResult.hitBlock
                val hitEntity = hitResult.hitEntity

                if (hitBlock != null) {
                    changeBlock(hitBlock)
                } else if (hitEntity != null && hitEntity is LivingEntity) {
                    changeEntity(hitEntity)
                }
                world.playSound(start, Sound.BLOCK_BEACON_ACTIVATE, 2.0f, 2.0f)
                world.spawnParticle(Particle.SPELL_MOB_AMBIENT, start, 10, 0.5, 0.5, 0.5, 0.1)
                remove()
            }

            TrailSupport.trail(start, trail.to, 0.35) { w, x, y, z ->
                w.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustTransition(Color.fromRGB(Default.nextInt(255), Default.nextInt(255),Default.nextInt(255)), Color.fromRGB(Default.nextInt(255), Default.nextInt(255),Default.nextInt(255)), 1.0f))
            }
            world.playSound(start, Sound.BLOCK_AMETHYST_BLOCK_FALL, 1.5f, 2.0f)
        }

        override fun onRemove() {
            fakeEntity.remove()
        }

        private fun changeBlock(block: Block) {
            val materials = Material.values().filter { it.isBlock && it.isSolid }
            val randomMaterial = materials[Default.nextInt(materials.size)]
            block.type = randomMaterial
        }

        private fun changeEntity(entity: LivingEntity) {
            if (entity is Player) {
                entity.psychicDamage()
                return
            }

            val world = entity.world
            val location = entity.location
            val entityType = EntityType.values().filter { it.isAlive && it != EntityType.PLAYER }.random()

            entity.remove()
            world.spawnEntity(location, entityType)
        }
    }
}