package io.github.anblus.psychics.ability.wildswing

import com.destroystokyo.paper.event.player.PlayerJumpEvent
import io.github.monun.psychics.Ability
import io.github.monun.psychics.AbilityConcept
import io.github.monun.psychics.AbilityType
import io.github.monun.psychics.TestResult
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.damage.psychicDamage
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.task.TickerTask
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Name("wild-swing")
class AbilityConceptWildSwing : AbilityConcept() {

    @Config
    var rotationSpeedPerAttackSpeed = 0.3

    @Config
    var targetSlownessTicks = 40

    @Config
    var targetSlownessAmplifier = 1

    @Config
    var casterSlownessAmplifier = 1

    @Config
    var damageReductionPercent = 50.0

    @Config
    var manaRestoreOnHit = 2.0

    @Config
    var defaultDamageMultiplier = 0.45

    @Config
    var esperAttackDamageMultiplier = 0.25

    init {
        displayName = "마구 휘두르기"
        type = AbilityType.ACTIVE
        durationTime = 5000L
        cost = 40.0
        range = 4.0
        description = listOf(
            text("아무 도구를 든 상태에서 웅크린 채 우클릭 시 능력을 사용합니다."),
            text("능력 사용 시 주위로 도구를 휘둘러 근처 적들에게 피해를 줍니다."),
            text("적에게 피해를 줄 시 마나를 회복하고, 구속 효과를 부여합니다."),
            text("사용 중에는 이동이 제한되고, 받는 피해가 감소합니다."),
            text("회전 속도와 피해량은 각각 도구의 공격 속도와 공격력에 비례합니다."),
            text("능력 재사용 시 능력을 즉시 종료합니다.")
        )
        wand = ItemStack(Material.IRON_HOE)
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(damageReductionPercent) { NamedTextColor.YELLOW to "받는 피해 감소" to "%" }
        tooltip.stats(manaRestoreOnHit) { NamedTextColor.GREEN to "적중당 마나 회복량" to "" }
        tooltip.stats(defaultDamageMultiplier + stats(EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to esperAttackDamageMultiplier))) { NamedTextColor.DARK_PURPLE to "도구 피해량의" to "배" }
    }
}

class AbilityWildSwing : Ability<AbilityConceptWildSwing>(), Listener {
    private data class SwingSession(
        val display: ItemDisplay,
        val itemSnapshot: ItemStack,
        val attackSpeed: Double,
        val baseItemDamage: Double,
        var angle: Double,
        var remainingTicks: Int,
        val hittedEntity: MutableMap<java.util.UUID, Double> // UUID to remaining immunity angle (in radians)
    )

    private var session: SwingSession? = null
    private var task: TickerTask? = null

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    override fun onDisable() {
        cancelSwing()
    }

    private fun isActive(): Boolean = session != null

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val action = event.action
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        if (player != esper.player) return

        val item = player.inventory.itemInMainHand
        if (item.type == Material.AIR) return

        session?.let { active ->
            if (active.itemSnapshot.isSimilar(item)) {
                cancelSwing()
            }
            event.isCancelled = true
            return
        }

        if (!player.isSneaking) return

        val attackSpeed = getItemAttackSpeed(item) ?: run {
            // player.sendActionBar(text("공격 속도가 있는 무기가 필요합니다."))
            return
        }
        val baseDamage = getItemBaseDamage(item)

        val result = test()
        if (result != TestResult.Success) {
            result.message(this)?.let { player.sendActionBar(it) }
            return
        }

        psychic.consumeMana(concept.cost)
        cooldownTime = concept.cooldownTime
        player.setCooldown(item.type, (cooldownTime / 50L).toInt())

        startSwing(player, item, attackSpeed, baseDamage)
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.HIGH)
    fun onEntityDamage(event: EntityDamageEvent) {
        if (session == null) return

        val reduction = concept.damageReductionPercent.coerceIn(0.0, 100.0) / 100.0
        event.damage = max(0.0, event.damage * (1.0 - reduction))
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerJump(event: PlayerJumpEvent) {
        if (!isActive()) return
        event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!isActive()) return
        event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (!isActive()) return
        event.isCancelled = true
    }

    private fun startSwing(player: Player, item: ItemStack, attackSpeed: Double, baseDamage: Double) {
        val world = player.world
        val center = player.boundingBox.center.toLocation(world)
        val startLoc = Location(world, center.x, center.y, center.z, 0.0f, 0.0f)

        val display = world.spawn(startLoc, ItemDisplay::class.java) {
            it.isPersistent = false
            it.setItemStack(item.clone())
        }

        val baseLength = if (item.type == Material.TRIDENT) 2.0f else 1.0f
        val displayScale = (concept.range / baseLength).toFloat()
        display.transformation = Transformation(
            Vector3f(0.0f, 0.0f, 0.0f),
            Quaternionf(),
            Vector3f(displayScale, displayScale, displayScale),
            Quaternionf()
        )

        val durationTicks = max(1, (concept.durationTime / 50L).toInt())
        session = SwingSession(
            display = display,
            itemSnapshot = item.clone(),
            attackSpeed = attackSpeed,
            baseItemDamage = baseDamage,
            angle = 0.0,
            remainingTicks = durationTicks,
            hittedEntity = mutableMapOf()
        )

        player.addPotionEffect(
            PotionEffect(
                PotionEffectType.SLOWNESS,
                durationTicks + 5,
                concept.casterSlownessAmplifier,
                true,
                false,
                true
            )
        )
        player.addPotionEffect(
            PotionEffect(
                PotionEffectType.WEAKNESS,
                durationTicks + 5,
                4,
                true,
                false,
                true
            )
        )
        player.addPotionEffect(
            PotionEffect(
                PotionEffectType.MINING_FATIGUE,
                durationTicks + 5,
                4,
                true,
                false,
                true
            )
        )

        task = psychic.runTaskTimer({ tickSwing() }, 0L, 1L)
    }

    private fun tickSwing() {
        val player = esper.player
        val active = session ?: return

        if (!player.isValid || player.isDead) {
            cancelSwing()
            return
        }

        if (active.remainingTicks-- <= 0) {
            cancelSwing()
            return
        }

        val rotationSpeed = active.attackSpeed * concept.rotationSpeedPerAttackSpeed

        val it = active.hittedEntity.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value <= rotationSpeed) {
                it.remove()
            } else {
                entry.setValue(entry.value - rotationSpeed)
            }
        }

        active.angle -= rotationSpeed
        if (active.angle < 0) {
            active.angle += PI * 2
            player.world.playSound(player.location, Sound.ITEM_CROSSBOW_LOADING_END, SoundCategory.PLAYERS, 2.0f, 0.8f)
        }

        val world = player.world
        val center = player.boundingBox.center.toLocation(world)
        val displayLoc = Location(
            world,
            center.x,
            center.y,
            center.z,
            0.0f,
            0.0f
        )

        if (!active.display.isValid || active.display.isDead) {
            cancelSwing()
            return
        }

        active.display.teleport(displayLoc)
        val outward = Vector3f(cos(active.angle).toFloat(), 0.0f, sin(active.angle).toFloat())
        val transform = active.display.transformation
        val offsetMultiplier = concept.range / 2
        val type = active.display.itemStack.type

        val sin = sin(active.angle).toFloat()
        val cos = cos(active.angle).toFloat()

        val translationDirection = when (type) {
            Material.TRIDENT -> {
                Vector3f(outward).mul(-0.5f).add(Vector3f(-sin, 0f, cos).mul(2f)).add(0f, 0.5f, 0f)
            }
            else -> Vector3f(outward).mul(-1f).add(-sin, 0f, cos)
        }

        val localForward = when {
            Tag.ITEMS_SPEARS.isTagged(type) -> Vector3f(0.0f, 0.0f, -1.0f)
            else -> Vector3f(1.0f, 0.0f, 0.0f)
        }

        val flatRotation = Quaternionf().rotateX((PI / 2).toFloat())
        val leftRotation = Quaternionf().rotateTo(localForward, outward).mul(flatRotation)

        active.display.transformation = Transformation(
            (translationDirection.clone() as Vector3f).mul(offsetMultiplier.toFloat()),
            leftRotation,
            transform.scale,
            transform.rightRotation
        )

        translationDirection.normalize()

        val nearby = player.world.getNearbyLivingEntities(
            displayLoc.add(
                translationDirection.x * offsetMultiplier,
                0.0,
                translationDirection.z * offsetMultiplier
            ),
            offsetMultiplier,
            1.0,
            offsetMultiplier
        ).filter { player.hostileFilter().test(it) }

        for (target in nearby) {
            if (!active.hittedEntity.containsKey(target.uniqueId)) {
                applyHit(player, target, active.baseItemDamage)
                active.hittedEntity[target.uniqueId] = PI * 2
            }
        }
    }

    private fun applyHit(player: Player, target: LivingEntity, baseItemDamage: Double) {
        val damageAmount = baseItemDamage * (concept.defaultDamageMultiplier + esper.getStatistic(EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to concept.esperAttackDamageMultiplier)))
        target.psychicDamage(
            this,
            DamageType.MELEE,
            damageAmount,
            player,
            player.location,
            concept.knockback
        )
        target.addPotionEffect(
            PotionEffect(
                PotionEffectType.SLOWNESS,
                concept.targetSlownessTicks,
                concept.targetSlownessAmplifier,
                true,
                true,
                true
            )
        )
        target.world.playSound(target.location, Sound.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 0.6f, 0.8f)
        psychic.mana += concept.manaRestoreOnHit
    }

    private fun cancelSwing() {
        task?.cancel()
        task = null
        session?.display?.remove()
        session = null
        esper.player.removePotionEffect(PotionEffectType.SLOWNESS)
        esper.player.removePotionEffect(PotionEffectType.WEAKNESS)
        esper.player.removePotionEffect(PotionEffectType.MINING_FATIGUE)
    }

    private fun getItemAttackSpeed(item: ItemStack): Double? {
        val meta = item.itemMeta
        val modifiers = if (meta.hasAttributeModifiers()) {
            meta.getAttributeModifiers(Attribute.ATTACK_SPEED)
        } else {
            item.type.getDefaultAttributeModifiers(EquipmentSlot.HAND).get(Attribute.ATTACK_SPEED)
        }
        val modifiersSum = modifiers?.sumOf { it.amount } ?: 0.0
        if (modifiersSum == 0.0 && !Tag.ITEMS_HOES.isTagged(item.type)) return null

        val playerBase = esper.player.getAttribute(Attribute.ATTACK_SPEED)?.baseValue ?: 4.0
        val total = playerBase + modifiersSum

        return total
    }

    private fun getItemBaseDamage(item: ItemStack): Double {
        val meta = item.itemMeta
        val modifiers = if (meta.hasAttributeModifiers()) {
            meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE)
        } else {
            item.type.getDefaultAttributeModifiers(EquipmentSlot.HAND).get(Attribute.ATTACK_DAMAGE)
        }

        val baseAttr = esper.player.getAttribute(Attribute.ATTACK_DAMAGE)?.baseValue ?: 1.0
        val modifiersSum = modifiers?.sumOf { it.amount } ?: 0.0
        val total = baseAttr + modifiersSum

        return total
    }
}
