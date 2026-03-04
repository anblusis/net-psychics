package io.github.anblus.psychics.ability.swordthrowing

import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.damage.psychicDamage
import io.github.monun.psychics.event.EntityDamageByPsychicEvent
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.event.EntityProvider
import io.github.monun.tap.event.TargetEntity
import io.github.monun.tap.trail.TrailSupport
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.*
import org.bukkit.attribute.Attribute
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f

@Name("sword-throwing")
class AbilityConceptSwordThrowing : AbilityConcept() {

    @Config
    var markDurationTicks = 100

    @Config
    var daggerLaunchPassiveSpeed = 0.35

    @Config
    var daggerLaunchSkillSpeed = 0.9

    @Config
    var daggerMaxFlyTicks = 200

    @Config
    var daggerGravity = 0.09

    @Config
    var daggerHitbox = 0.5

    init {
        displayName = "검 투척"
        type = AbilityType.COMPLEX
        cooldownTime = 2000L
        cost = 35.0
        range = 16.0
        damage = Damage.of(DamageType.MELEE, EsperAttribute.ATTACK_DAMAGE to 4.0)
        description = listOf(
            text("검으로 적을 공격하면 적에게 표식을 남깁니다."),
            text("표식을 가진 적을 검으로 3회 적중 시 적의 뒤에 검을 투척합니다."),
            text("박힌 검을 바라본 채 우클릭 시 순간이동하며 경로 상 적들에게 피해를 줍니다."),
            text("피해량은 검의 기본 공격력에 더해집니다."),
            text(""),
            text("웅크린 채 우클릭 시 마나를 소모하여 바라보는 방향으로 검을 던집니다.")
        )
        wand = ItemStack(Material.IRON_SWORD)
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(markDurationTicks / 20) { NamedTextColor.DARK_RED to "표식 지속 시간" to "초" }
        tooltip.stats(daggerMaxFlyTicks / 20) { NamedTextColor.DARK_GREEN to "검 지속 시간" to "초" }
    }
}

class AbilitySwordThrowing : Ability<AbilityConceptSwordThrowing>(), Listener {
    companion object {
        private val cooldownMaterials = arrayOf(
            Material.WOODEN_SWORD,
            Material.STONE_SWORD,
            Material.IRON_SWORD,
            Material.COPPER_SWORD,
            Material.GOLDEN_SWORD,
            Material.DIAMOND_SWORD,
            Material.NETHERITE_SWORD
        )
    }

    private var markedTarget: LivingEntity? = null
    private var markExpireTick: Int = 0
    private var markStacks: Int = 0
    private var markDisplay: ItemDisplay? = null
    private var markTextDisplay: TextDisplay? = null

    private val thrownSwords = mutableListOf<DaggerProjectile>()

    override fun onEnable() {
        psychic.registerEvents(this)
        psychic.runTaskTimer(this::onTick, 0L, 1L)
    }

    override fun onDisable() {
        clearMark()
        thrownSwords.toList().forEach { it.removeEntity() }
        thrownSwords.clear()
    }

    private fun clearMark() {
        markedTarget = null
        markStacks = 0
        markExpireTick = 0
        markDisplay?.remove()
        markDisplay = null
        markTextDisplay?.remove()
        markTextDisplay = null
    }

    private fun onTick() {
        if (markExpireTick > 0) markExpireTick--

        if (markedTarget != null && (markedTarget?.isDead == true || !markedTarget!!.isValid || markExpireTick <= 0)) {
            clearMark()
        }

        markedTarget?.let { target ->
            markDisplay?.let { display ->
                if (!display.isValid || display.isDead) {
                    markDisplay = null
                } else {
                    display.teleport(target.location.add(0.0, target.height + 0.5, 0.0))
                }
            }
            markTextDisplay?.let { textDisplay ->
                if (!textDisplay.isValid || textDisplay.isDead) {
                    markTextDisplay = null
                } else {
                    textDisplay.teleport(target.location.add(0.0, target.height + 0.9, 0.0))
                }
            }
        }

        val iterator = thrownSwords.iterator()
        while (iterator.hasNext()) {
            val projectile = iterator.next()
            if (!projectile.tick()) iterator.remove()
        }
    }

    private fun spawnDagger(startLoc: Location, itemStack: ItemStack, initVelocity: Vector) {
        val stand = startLoc.world.spawn(startLoc, ItemDisplay::class.java) {
            it.isPersistent = false
            it.setItemStack(itemStack)
            it.isGlowing = true
        }

        val projectile = DaggerProjectile(stand, startLoc.clone(), initVelocity)
        thrownSwords += projectile
    }

    private inner class DaggerProjectile(
        val display: ItemDisplay,
        var location: Location,
        var velocity: Vector,
        var remainingTicks: Int = concept.daggerMaxFlyTicks,
    ) {

        var stuck = false

        fun tick(): Boolean {
            if (remainingTicks-- <= 0) {
                removeEntity()
                return false
            }
            if (!display.isValid || display.isDead) {
                removeEntity()
                return false
            }

            if (stuck) {
                location.world.spawnParticle(Particle.ENCHANT, location, 1, 0.2, 0.3, 0.2, 0.0)
            } else {
                velocity = velocity.apply { y -= concept.daggerGravity }
            }

            val from = location
            val move = velocity.clone()
            val length = move.length()
            if (length == 0.0) return true

            val dir = move.clone().normalize()
            val world = from.world

            // 디버그용: trail.from 시각화
            // world.spawnParticle(Particle.SPELL, from, 1, 0.0, 0.0, 0.0, 0.0)

            world.rayTraceBlocks(from, dir, length, FluidCollisionMode.NEVER, true)?.let hitBlockTrace@{ hit ->
                if (stuck) return@hitBlockTrace
                val hitBlock = hit.hitBlock ?: return@hitBlockTrace
                val hitLoc = hit.hitPosition.toLocation(world)

                stuck = true
                location = hitLoc
                updateRotation(dir)
                val offset = if (velocity.lengthSquared() > 0.0) velocity.clone().normalize().multiply(-0.2) else Vector()
                display.teleport(location.clone().add(offset))
                velocity.normalize().multiply(0.1)


                world.playSound(hitLoc, Sound.ITEM_TRIDENT_HIT, SoundCategory.PLAYERS, 0.6f, 1.5f)
                world.spawnParticle(
                    Particle.BLOCK,
                    hitLoc,
                    12,
                    0.2,
                    0.1,
                    0.2,
                    0.1,
                    hitBlock.blockData
                )
            }?: run {
                val next = from.clone().add(move)
                location = next
                updateRotation(dir)
                display.teleport(next.clone())
                stuck = false
            }

            return true
        }

        private fun updateRotation(direction: Vector) {
            if (direction.lengthSquared() == 0.0) return

            val forward = Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
            val bladeAxis = Vector3f(-1.0f, 1.0f, 0.0f).normalize()
            val base = Quaternionf().rotateTo(bladeAxis, forward)
            display.transformation = Transformation(
                Vector3f(0.0f, 0.0f, 0.0f),
                base,
                Vector3f(1.0f, 1.0f, 1.0f),
                Quaternionf()
            )
        }

        fun removeEntity() {
            display.remove()
        }
    }

    // --- 이벤트 핸들러 ---

    private fun tryCastMultiThrow(player: Player, itemStack: ItemStack): Boolean {
        if (!Tag.ITEMS_SWORDS.isTagged(itemStack.type)) return false

        val result = test()
        if (result != TestResult.Success) {
            result.message(this)?.let { player.sendActionBar(it) }
            return false
        }

        psychic.consumeMana(concept.cost)

        cooldownTime = concept.cooldownTime
        val cooldownTicks = (cooldownTime / 50L).toInt()
        cooldownMaterials.forEach { material ->
            player.setCooldown(material, cooldownTicks)
        }

        val world = player.world
        val startLoc = player.eyeLocation.clone().add(0.0, 0.1, 0.0)
        val forward = player.location.direction.clone().setY(0.0)
        if (forward.lengthSquared() == 0.0) return true
        forward.normalize()
        val right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0)).normalize()
        /*
        val directions = arrayOf(
            forward.clone(),
            forward.clone().multiply(-1.0),
            right.clone(),
            right.clone().multiply(-1.0)
        )
         */
        val directions = arrayOf(
            forward.clone()
        )

        directions.forEach { dir ->
            val launchDir = dir.clone().setY(0.66).normalize()
            val initVelocity = launchDir.multiply(concept.daggerLaunchSkillSpeed)

            spawnDagger(startLoc, itemStack.clone(), initVelocity)
        }

        world.playSound(startLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.4f)
        world.spawnParticle(Particle.SWEEP_ATTACK, startLoc, 1, 0.0, 0.0, 0.0, 0.0)
        return true
    }

    @TargetEntity(EntityProvider.EntityDamageByEntity.Damager::class)
    @EventHandler(ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        if (event is EntityDamageByPsychicEvent) return

        // 실제 근접 공격만 표식 적립 허용 (투사체/기타 원인 제외)
        if (event.cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return

        val player = esper.player
        val target = event.entity as? LivingEntity ?: return

        val item = player.inventory.itemInMainHand
        if (!Tag.ITEMS_SWORDS.isTagged(item.type)) return

        // 새 대상 공격 -> 기존 표식 제거 후 새 표식 부여
        if (markedTarget == null || markedTarget != target) {
            clearMark()

            markedTarget = target
            markExpireTick = concept.markDurationTicks
            markStacks = 0

            val world = target.world
            val displayLocation = target.location.add(0.0, target.height + 0.5, 0.0)
            val display = world.spawn(displayLocation, ItemDisplay::class.java) {
                it.isPersistent = false
                it.setItemStack(item.clone())
                it.billboard = Display.Billboard.VERTICAL
                val bladeAxis = Vector3f(-1.0f, 1.0f, 0.0f).normalize()
                val down = Vector3f(0.0f, -1.0f, 0.0f)
                val rotation = Quaternionf().rotateTo(bladeAxis, down)
                it.transformation = Transformation(
                    Vector3f(0.0f, 0.0f, 0.0f),
                    rotation,
                    Vector3f(1.0f, 1.0f, 1.0f),
                    Quaternionf()
                )
            }
            markDisplay = display
            val textDisplay = world.spawn(displayLocation.clone().add(0.0, 0.6, 0.0), TextDisplay::class.java) {
                it.isPersistent = false
                it.text(text(markStacks.toString(), NamedTextColor.RED))
                it.billboard = Display.Billboard.CENTER
                it.isSeeThrough = true
            }
            markTextDisplay = textDisplay
            world.players.forEach { viewer ->
                if (viewer != player) {
                    viewer.hideEntity(psychic.plugin, display)
                    viewer.hideEntity(psychic.plugin, textDisplay)
                }
            }

            world.spawnParticle(
                Particle.ENCHANTED_HIT,
                target.location.add(0.0, target.height * 0.8, 0.0),
                10,
                0.3,
                0.3,
                0.3,
                0.0
            )
            world.playSound(target.location, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.7f, 1.6f)
        }

        markStacks += 1
        markedTarget?.let {
            markExpireTick = concept.markDurationTicks
        }
        markTextDisplay?.text(text(markStacks.toString(), NamedTextColor.RED))

        if (markStacks >= 3) {
            markStacks = 0
            markTextDisplay?.text(text("0", NamedTextColor.RED))

            val world = target.world

            val backDir = player.location.direction.clone().setY(0.0).normalize()

            val startLoc = target.eyeLocation.clone()
                .add(0.0, 0.2 , 0.0)

            val launchDir = backDir.setY(0.66).normalize()
            val initVelocity = launchDir.multiply(concept.daggerLaunchPassiveSpeed)

            spawnDagger(startLoc, item.clone(), initVelocity)

            world.playSound(startLoc, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.6f)
            world.spawnParticle(Particle.SWEEP_ATTACK, startLoc, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val action = event.action
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return

        val player = esper.player
        val eye = player.eyeLocation
        val dir = eye.direction.normalize()
        val world = player.world

        var targetSword: DaggerProjectile? = null

        if (thrownSwords.isNotEmpty()) {
            val rayResult = world.rayTraceEntities(eye, dir, concept.range, concept.daggerHitbox) { entity ->
                val display = entity as? ItemDisplay ?: return@rayTraceEntities false
                thrownSwords.any { it.display == display && it.stuck }
            }

            val hitDisplay = rayResult?.hitEntity as? ItemDisplay
            if (hitDisplay != null) {
                targetSword = thrownSwords.firstOrNull { it.display == hitDisplay } ?: return
                /*
                val to = targetSword.location.clone().subtract(eye).toVector()
                val dist = to.length()
                if (dist > 0.5) {
                    val blockHit = world.rayTraceBlocks(eye, to.clone().normalize(), dist - 0.5, FluidCollisionMode.NEVER, true)
                    if (blockHit?.hitBlock != null) {
                        targetSword = null
                    }
                }
                 */
            }
        }

        if (targetSword != null) {
            val from = player.location.clone()
            val to = targetSword.location.clone()

            val damage = concept.damage
            val damageType = damage?.type ?: DamageType.MELEE
            val meta = targetSword.display.itemStack.itemMeta
            val modifiers = meta?.attributeModifiers
            val swordDamage = modifiers?.get(Attribute.ATTACK_DAMAGE)?.sumOf { it.amount } ?: 0.0
            val damageAmount = esper.getStatistic(damage!!.stats) + swordDamage

            val hitSet = mutableSetOf<LivingEntity>()

            TrailSupport.trail(from, to, 0.5) { w, x, y, z ->
                w.spawnParticle(Particle.END_ROD, x, y + 1, z, 1, 0.0, 0.0, 0.0, 0.0)
                val nearby = world.getNearbyLivingEntities(Location(w, x, y, z), 0.8, 0.8, 0.8)
                    .filter { player.hostileFilter().test(it) }
                nearby.forEach { entity ->
                    if (hitSet.add(entity)) {
                        entity.world.spawnParticle(
                            Particle.SWEEP_ATTACK,
                            entity.location.add(0.0, entity.height * 0.6, 0.0),
                            5,
                            0.2,
                            0.2,
                            0.2,
                            0.0
                        )
                        entity.psychicDamage(
                            this,
                            damageType,
                            damageAmount,
                            player,
                            player.location,
                            concept.knockback
                        )
                    }
                }
            }

            world.playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f)
            world.playSound(to, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.0f, 1.2f)

            player.teleport(to.apply {
                direction = player.location.direction
            })

            targetSword.removeEntity()
            thrownSwords.remove(targetSword)

            event.isCancelled = true
            return
        }

        if (player.isSneaking) {
            val item = player.inventory.itemInMainHand
            if (tryCastMultiThrow(player, item)) {
                event.isCancelled = true
            }
        }
    }
}
