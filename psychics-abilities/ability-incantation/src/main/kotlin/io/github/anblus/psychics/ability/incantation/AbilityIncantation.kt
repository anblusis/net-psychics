package io.github.anblus.psychics.ability.incantation

import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperAttribute
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.damage.Damage
import io.github.monun.psychics.damage.DamageType
import io.github.monun.psychics.item.isPsychicbound
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.psychics.util.TargetFilter
import io.github.monun.psychics.util.hostileFilter
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.event.EntityProvider
import io.github.monun.tap.event.TargetEntity
import io.github.monun.tap.fake.FakeEntity
import io.github.monun.tap.fake.Movement
import io.github.monun.tap.task.TickerTask
import io.github.monun.tap.trail.TrailSupport
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------- 패턴 메타 정의 ----------------

// 영창 입력 단위 (좌/우 클릭만)
enum class ChantInput(val code: String) { L("L"), R("R") }

// 패턴 메타: 시퀀스, 제목, 마나 소모, 설명 + 선택 정보 (데미지/힐/사거리/쿨타임)
data class SpellPatternMeta(
    val sequence: List<ChantInput>,
    val title: String,
    val manaCost: Double,
    val description: String,
    val damageValue: Double? = null,
    val damageType: DamageType? = null,
    val healValue: Double? = null,
    val range: Double? = null,
    val cooldownMs: Long? = null
) {
    val damageLabel: String? = damageType?.let {
        when (it) {
            DamageType.BLAST -> "기본 폭발 피해"
            DamageType.RANGED -> "기본 원거리 피해"
            DamageType.FIRE -> "기본 화염 피해"
            DamageType.MELEE -> "기본 근접 피해"
        }
    }
    val healLabel: String? = if (healValue != null) "기본 치유량" else null
}

// 패턴 메타 목록 (입력 L/R만 사용)
val SPELL_PATTERNS: List<SpellPatternMeta> = listOf(
    // 치유: 기본 치유
    SpellPatternMeta(
        sequence = listOf(ChantInput.L, ChantInput.R),
        title = "치유",
        manaCost = 15.0,
        description = "즉시 자신을 치유합니다.",
        healValue = 2.0,
        cooldownMs = 500L
    ),
    SpellPatternMeta(
        sequence = listOf(ChantInput.R, ChantInput.L, ChantInput.L),
        title = "소형 폭발",
        manaCost = 10.0,
        description = "전방으로 폭발 구체를 발사합니다. 구체는 충돌 시 작은 폭발을 일으킵니다.",
        damageValue = 2.0,
        damageType = DamageType.BLAST,
        range = 32.0,
        cooldownMs = 2000L
    ),
    // 관통 화살: 기존 유지
    SpellPatternMeta(
        sequence = listOf(ChantInput.L, ChantInput.R, ChantInput.R),
        title = "마탄",
        manaCost = 12.0,
        description = "벽을 관통하는 마탄을 발사합니다.",
        damageValue = 1.0,
        damageType = DamageType.RANGED,
        range = 20.0,
        cooldownMs = 1000L
    ),
    // 마력 증폭: 잠시 공격과 활력을 증폭시킴
    SpellPatternMeta(
        sequence = listOf(ChantInput.R, ChantInput.R, ChantInput.L),
        title = "마력 증폭",
        manaCost = 25.0,
        description = "짧은 시간 동안 공격력과 재생을 강화합니다.",
        cooldownMs = 1000L
    ),
    // 속박 파동: 주변 적을 둔화시키는 파동 방출
    SpellPatternMeta(
        sequence = listOf(ChantInput.R, ChantInput.R, ChantInput.R),
        title = "속박 파동",
        manaCost = 25.0,
        description = "주변에 속박성 파동을 방출해 적을 둔화·약화시키고 경미한 피해 를 줍니다.",
        damageValue = 0.5,
        damageType = DamageType.BLAST,
        range = 6.0,
        cooldownMs = 8000L
    ),
    // 화염 잔영: 전방 경로에 불길을 남김
    SpellPatternMeta(
        sequence = listOf(ChantInput.R, ChantInput.L, ChantInput.R, ChantInput.R),
        title = "화염 잔영",
        manaCost = 15.0,
        description = "전방 경로에 벽을 관통하는 불길을 남겨 적에게 화염 피해를 입힙니다.",
        damageValue = 0.5,
        damageType = DamageType.FIRE,
        range = 16.0,
        cooldownMs = 1000L
    ),
    // 보호 장막: 흡수 보호막과 피해 저항 부여
    SpellPatternMeta(
        sequence = listOf(ChantInput.L, ChantInput.L, ChantInput.R, ChantInput.L),
        title = "보호 장막",
        manaCost = 16.0,
        description = "흡수 보호막과 저항을 부여해 생존력을 높입니다.",
        cooldownMs = 4000L
    ),
    // 연속 치유: 더 높은 치유
    SpellPatternMeta(
        sequence = listOf(ChantInput.L, ChantInput.R, ChantInput.L, ChantInput.R),
        title = "연속 치유",
        manaCost = 30.0,
        description = "즉시 자신을 3연속 치유합니다.",
        healValue = 2.0,
        cooldownMs = 1000L
    ),
    // 확산 폭발: 더 멀리, 더 넓은 반경
    SpellPatternMeta(
        sequence = listOf(ChantInput.R, ChantInput.L, ChantInput.L, ChantInput.R, ChantInput.L),
        title = "확산 폭발",
        manaCost = 20.0,
        description = "전방으로 폭발 구체를 발사합니다. 구체는 충돌 시 큰 폭발을 일으킵니다.",
        damageValue = 4.0,
        damageType = DamageType.BLAST,
        range = 40.0,
        cooldownMs = 4000L
    ),
    // 연사 화살: 세 갈래 연속 관통 사격
    SpellPatternMeta(
        sequence = listOf(ChantInput.L, ChantInput.R, ChantInput.R, ChantInput.L, ChantInput.R),
        title = "세 갈래 마탄",
        manaCost = 24.0,
        description = "세 갈래로 벽을 관통하는 마탄을 발사합니다.",
        damageValue = 2.0,
        damageType = DamageType.RANGED,
        range = 20.0,
        cooldownMs = 2000L
    ),
    // 마나 생성: 마나 회복
     SpellPatternMeta(
        sequence = listOf(ChantInput.L, ChantInput.L, ChantInput.R, ChantInput.R, ChantInput.L),
        title = "마나 생성",
        manaCost = 0.0,
        description = "즉시 마나를 5 회복합니다.",
        cooldownMs = 2000L
    ),
)

/**
 * 영창(Incantation) 능력
 * 기본템: 마법 족보(책), 지팡이(막대기)
 * 첫 사용(시전)으로 세션 시작, 이후 입력은 L/R 순차 축적.
 * 완전 일치 패턴 발견 시 즉시 발동 (남은 슬롯 꽉 채우지 않아도 됨).
 */
@Name("incantation")
class AbilityConceptIncantation : AbilityConcept() {
    @Config
    var failCooldownTime = 10000L

    @Config
    var sessionTimeoutMs = 5000L

    init {
        displayName = "영창"
        type = AbilityType.ACTIVE
        cooldownTime = 0L
        description = listOf(
            text("기본적으로 마법 족보와 영창 지팡이를 지급받습니다."),
            text("마법 족보에는 사용 가능한 마법이 정리되어 있습니다."),
            text("지팡이를 손에 든 채 웅크려 영창을 시작합니다."), // 오탈자 교정
            text("좌클릭 (L), 우클릭 (R)으로 영창을 입력합니다."),
            text("영창 완료 시 다시 웅크려 확정하며 잘못된 영창은 디버프를 부여합니다."),
        )

        // 지팡이(Wand)
        wand = ItemStack(Material.STICK).apply {
            itemMeta = itemMeta.apply {
                displayName(text("영창 지팡이"))
                addItemFlags(ItemFlag.HIDE_ENCHANTS)
            }
            addUnsafeEnchantment(Enchantment.FORTUNE, 1)
            isPsychicbound = true
        }

        // WRITTEN_BOOK 페이지 구성 (각 패턴 1페이지)
        val book = ItemStack(Material.WRITTEN_BOOK).apply {
            val meta = (itemMeta as BookMeta).apply {
                title = "마법 족보"
                author = "마법 협회"
                displayName(text("마법 족보"))

                SPELL_PATTERNS.forEach { p ->
                    val seq = p.sequence.joinToString(" ") { it.code }
                    val page = buildString {
                        appendLine("§d§l${p.title}")
                        appendLine("§b$seq")
                        appendLine("§6§l마나 §0${p.manaCost.toInt()}")
                        p.cooldownMs?.let { appendLine("§6§l쿨타임 §0${it / 1000.0} 초") }
                        p.range?.let { appendLine("§6§l사거리 §0${it.toInt()} 블록") }
                        p.damageLabel?.let { lbl -> p.damageValue?.let { v -> appendLine("§6§l${lbl} §0$v") } }
                        p.healLabel?.let { lbl -> p.healValue?.let { v -> appendLine("§6§l${lbl} §0$v") } }
                        appendLine("")
                        appendLine("")
                        appendLine(p.description)
                    }
                    addPages(text(page))
                }
            }
            itemMeta = meta
            isPsychicbound = true
        }
        supplyItems = listOf(book, wand!!)
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(stats(EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to 1.0))) { NamedTextColor.DARK_PURPLE to "효과 계수" to "배" }
    }

}

class AbilityIncantation : Ability<AbilityConceptIncantation>(), Listener {
    // 세션 구조
    data class ChantSession(
        val startTime: Long,
        val inputs: MutableList<ChantInput> = mutableListOf(),
        var finished: Boolean = false,
        var timeoutTask: TickerTask? = null,
        var particleTask: TickerTask? = null
    )

    private var session: ChantSession? = null
    private var success = false

    private val maxLength = 5

    override fun onEnable() { psychic.registerEvents(this) }

    override fun onDisable() { cancelSession() }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (session == null) return
        if (cooldownTime > 0L) return
        val action = event.action
        val input = when (action) {
            Action.LEFT_CLICK_AIR -> ChantInput.L
            Action.LEFT_CLICK_BLOCK -> ChantInput.L
            Action.RIGHT_CLICK_AIR -> ChantInput.R
            Action.RIGHT_CLICK_BLOCK -> ChantInput.R
            else -> return
        }
        cooldownTime = 10L
        appendInput(input)
    }

    @EventHandler(priority = EventPriority.HIGH)
    @TargetEntity(EntityProvider.EntityDamageByEntity.Damager::class)
    fun onEntityDamage(e: EntityDamageByEntityEvent) {
        if (!esper.player.inventory.itemInMainHand.isSimilar(concept.wand!!)) return
        val s = session ?: return
        if (s.finished || success) return

        e.isCancelled = true
        appendInput(ChantInput.L)
    }

    private fun startSession() {
        val player = esper.player
        val s = ChantSession(System.currentTimeMillis())
        session = s
        success = false
        val seconds = (concept.sessionTimeoutMs / 1000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }
        player.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, (concept.sessionTimeoutMs / 50L).toInt(), 4, false, false, false))
        player.sendActionBar(text("영창 시작 (${seconds}초)" ).color(NamedTextColor.LIGHT_PURPLE))

        s.particleTask = psychic.runTaskTimer({
            if (session == null || session!!.finished) {
                s.particleTask?.cancel()
                return@runTaskTimer
            }
            val loc = player.location
            val world = loc.world
            val radius = 0.8
            var i = 0.0
            while (i < 2 * Math.PI) {
                val x = radius * cos(i)
                val z = radius * sin(i)
                world.spawnParticle(Particle.DUST, loc.x + x, loc.y + 0.2, loc.z + z, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(org.bukkit.Color.WHITE, 1.0f))
                i += Math.PI / 16
            }
        }, 0L, 5L)

        val task = psychic.runTask( {
            if (!success) finalizeSessionFailure()
        }, (concept.sessionTimeoutMs / 50L))
        s.timeoutTask = task
    }

    private fun cancelSession() {
        session?.timeoutTask?.cancel()
        session?.particleTask?.cancel()

        esper.player.removePotionEffect(PotionEffectType.SLOWNESS)
        session = null
        success = false
    }

    private fun buildChantProgress(): Component {
        val s = session
        val builder = text().decoration(TextDecoration.ITALIC, false)
            .append(text("영창:").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD))
        if (s == null || s.inputs.isEmpty()) return builder.build()
        builder.append(text(" "))
        val lastIndex = s.inputs.lastIndex
        s.inputs.forEachIndexed { idx, ch ->
            if (idx > 0) builder.append(text(" "))
            val color = when (ch) {
                ChantInput.L -> NamedTextColor.GOLD
                ChantInput.R -> NamedTextColor.YELLOW
            }
            builder.append(
                text(ch.code)
                    .color(color)
                    .decoration(TextDecoration.BOLD, idx == lastIndex) // 마지막 입력만 굵게 강조
            )
        }
        return builder.build()
    }

    private fun appendInput(input: ChantInput) {
        val s = session ?: return
        if (s.finished || success) return

        s.inputs.add(input)
        val player = esper.player
        player.sendActionBar(buildChantProgress())
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.5f)

        // prefix 검사 -> 불가능하면 즉시 실패
        val stillPossible = SPELL_PATTERNS.any { pattern ->
            pattern.sequence.size >= s.inputs.size && pattern.sequence.take(s.inputs.size) == s.inputs
        }
        if (!stillPossible) {
            finalizeSessionFailure(); return
        }

        if (s.inputs.size > maxLength) finalizeSessionFailure()
    }

    // 웅크리기 입력
    private fun finalizeChantBySneak() {
        val s = session ?: return
        if (s.finished) return
        esper.player.sendActionBar(buildChantProgress())
        val current = s.inputs.toList()
        val meta = SPELL_PATTERNS.firstOrNull { it.sequence == current }
        if (meta != null) finalizeSuccess(meta) else finalizeSessionFailure()
    }

    private fun finalizeSuccess(meta: SpellPatternMeta) {
        val s = session ?: return
        if (s.finished) return
        success = true

        val player = esper.player
        if (!psychic.consumeMana(meta.manaCost)) {
            player.sendActionBar(text("마나 부족 (${meta.manaCost.toInt()})").color(NamedTextColor.RED))
            success = false
            finalizeSessionFailure()
            return
        }

        s.finished = true

        applyPatternEffect(meta) // now dispatches by meta.title
        player.sendActionBar(text("영창 성공: ${meta.title}").color(NamedTextColor.GREEN))

        cooldownTime = meta.cooldownMs ?: concept.cooldownTime
        cleanupSession()
    }

    private fun finalizeSessionFailure() {
        val s = session ?: return
        if (s.finished) return
        s.finished = true
        val player = esper.player
        player.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, (concept.failCooldownTime / 50).toInt(), 0))
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 0.6f)
        player.sendActionBar(text("영창 실패").color(NamedTextColor.RED))
        cooldownTime = concept.failCooldownTime
        cleanupSession()
    }

    private fun cleanupSession() {
        session?.timeoutTask?.cancel()
        session?.particleTask?.cancel()
        esper.player.removePotionEffect(PotionEffectType.SLOWNESS)
        session = null
    }

    // 이동형 폭발 처리
    private inner class ExplosionProjectile(
        private val damageType: DamageType,
        private val damageValue: Double,
        private val radius: Double,
        range: Double,
        maxTicks: Int = 200
    ) : PsychicProjectile(maxTicks, range) {
        lateinit var fireball: FakeEntity<ArmorStand>

        private var exploded = false

        override fun onMove(movement: Movement) {
            fireball.moveTo(movement.to.clone().apply { y -= 1.8 })
        }

        override fun onTrail(trail: io.github.monun.tap.fake.Trail) {
            val vel = trail.velocity ?: return
            val from = trail.from
            val world = from.world
            val length = vel.length()
            // 경로 파티클
            world.spawnParticle(Particle.SMALL_FLAME, from, 2, 0.02, 0.02, 0.02, 0.0)

            // 충돌 감지 (블록/엔티티)
            from.world.rayTrace(
                from,
                vel,
                length,
                FluidCollisionMode.NEVER,
                true,
                0.6,
                TargetFilter(esper.player)
            )?.let { ray ->
                val hitLoc = ray.hitPosition.toLocation(world)
                explode(hitLoc)
            }
        }

        override fun onRemove() {
            if (!exploded) {
                // 마지막 위치로 폭발
                explode(location)
            }
            fireball.remove()
        }

        private fun explode(center: org.bukkit.Location) {
            if (exploded) return
            exploded = true
            val world = center.world
            if (radius >= 4.0)
                world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1, 0.0, 0.0, 0.0, 0.0)
            else
                world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1, 0.0, 0.0, 0.0, 0.0)
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.05f)
            val dmg = Damage.of(damageType, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to damageValue))
            world.getNearbyEntities(center, radius, radius, radius) { esper.player.hostileFilter().test(it) }
                .forEach { (it as LivingEntity).psychicDamage(dmg) }
            remove()
        }
    }

    private fun launchExplosion(meta: SpellPatternMeta, speed: Double, radius: Double) {
        val player = esper.player
        val loc = player.eyeLocation.clone()
        val projectile = ExplosionProjectile(
            damageType = meta.damageType!!,
            damageValue = meta.damageValue!!,
            radius = radius,
            range = meta.range!!
        ).apply {
            fireball =
                this@AbilityIncantation.psychic.spawnFakeEntity(loc, ArmorStand::class.java).apply {
                    updateMetadata {
                        isVisible = false
                        isMarker = true
                    }
                    updateEquipment {
                        helmet = ItemStack(Material.FIRE_CHARGE)
                    }
                }
        }
        psychic.launchProjectile(loc, projectile)
        projectile.velocity = loc.direction.multiply(speed)
    }

    private fun shootPiercingTrails(meta: SpellPatternMeta, lines: Int) {
        val player = esper.player
        val base = player.eyeLocation.clone()
        val range = meta.range!!
        val yawOffsets = if (lines == 3) listOf(-8f, 0f, 8f) else listOf(0f)
        val damageType = meta.damageType!!
        val damageValue = meta.damageValue!!
        val dmg = Damage.of(damageType, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to damageValue))

        yawOffsets.forEach { off ->
            val start = base.clone().apply { yaw += off }
            val to = start.clone().add(start.direction.multiply(range))
            val hitSet = mutableSetOf<LivingEntity>() // 각 트레일별 피격 엔티티 집합
            TrailSupport.trail(start, to, 0.4) { w, x, y, z ->
                w.spawnParticle(Particle.ENCHANTED_HIT, x, y, z, 1, 0.02, 0.02, 0.02, 0.0)
                w.rayTraceEntities(
                    org.bukkit.Location(w, x, y, z),
                    start.direction,
                    0.4,
                    0.2,
                    TargetFilter(esper.player)
                )?.hitEntity?.let {
                    if (it is LivingEntity && hitSet.add(it)) {
                        it.psychicDamage(dmg)
                    }
                }
            }
        }
        player.world.playSound(player.location, Sound.ENTITY_ARROW_SHOOT, 1.0f, 2.0f)
    }

    /**
     * 다회 치유 (지연 적용)
     */
    private fun performHeal(player: org.bukkit.entity.Player, amount: Double, number: Int) {
        repeat(number) { i ->
            psychic.runTask({
                player.psychicHeal(amount)
                player.playSound(player.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.4f)
                player.world.spawnParticle(Particle.HEART, player.location.add(0.0, 1.0, 0.0), 4, 0.3, 0.5, 0.3, 0.0)
            }, i * 6L)
        }
    }

    private fun applyPatternEffect(meta: SpellPatternMeta) {
        when (meta.title) {
            "소형 폭발" -> launchExplosion(meta, speed = 2.0, radius = 2.5)
            "확산 폭발" -> launchExplosion(meta, speed = 3.0, radius = 5.0)
            "마탄" -> shootPiercingTrails(meta, lines = 1)
            "세 갈래 마탄" -> shootPiercingTrails(meta, lines = 3)
            "치유" -> performHeal(esper.player, meta.healValue!!, number = 1)
            "연속 치유" -> performHeal(esper.player, meta.healValue!!, number = 3)
            "마력 증폭" -> performAmpBuff()
            "속박 파동" -> performSnareWave(meta)
            "보호 장막" -> performBarrier()
            "화염 잔영" -> performFlameTrail(meta)
            "마나 생성" -> psychic.mana += 5
        }
    }

    private fun performAmpBuff() {
        val p = esper.player
        p.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, 20 * 5, 0, false, true, true))
        p.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, 20 * 5, 0, false, true, true))
        p.world.playSound(p.location, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f)
        p.world.spawnParticle(Particle.ENCHANT, p.location.add(0.0, 1.0, 0.0), 24, 0.6, 0.8, 0.6, 0.0)
    }

    private fun performSnareWave(meta: SpellPatternMeta) {
        val p = esper.player
        val world = p.world
        val center = p.location
        val radius = meta.range!!
        world.spawnParticle(Particle.SONIC_BOOM, center, 1, 0.0, 0.0, 0.0, 0.0)
        world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.4f)
        val dmg = Damage.of(meta.damageType!!, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to meta.damageValue!!))
        world.getNearbyEntities(center, radius, radius, radius) { p.hostileFilter().test(it) }
            .forEach { ent ->
                ent as LivingEntity
                ent.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 20 * 4, 2, false, true, true))
                ent.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 20 * 4, 0, false, true, true))
                ent.psychicDamage(dmg, knockback = 2.0)
            }
    }

    private fun performBarrier() {
        val p = esper.player
        p.addPotionEffect(PotionEffect(PotionEffectType.ABSORPTION, 20 * 12, 1, false, true, true))
        p.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, 20 * 8, 0, false, true, true))
        p.playSound(p.location, Sound.ITEM_SHIELD_BLOCK, 1f, 1.0f)
        p.world.spawnParticle(Particle.INSTANT_EFFECT, p.location.add(0.0, 1.0, 0.0), 32, 0.6, 0.8, 0.6, 0.05,Particle.Spell(Color.WHITE, 1.0f))
    }

    private fun performFlameTrail(meta: SpellPatternMeta) {
        val p = esper.player
        val start = p.eyeLocation.clone()
        val range = meta.range!!
        val damageType = meta.damageType!!
        val damageValue = meta.damageValue!!
        val dmg = Damage.of(damageType, EsperStatistic.of(EsperAttribute.ATTACK_DAMAGE to damageValue))
        val to = start.clone().add(start.direction.multiply(range))
        val hitSet = mutableSetOf<LivingEntity>() // 한 번만 맞도록
        p.world.playSound(p.location, Sound.ITEM_FLINTANDSTEEL_USE, 1f, 1.3f)
        TrailSupport.trail(start, to, 0.4) { w, x, y, z ->
            w.spawnParticle(Particle.FLAME, x, y, z, 4, 0.3, 0.3, 0.3, 0.0)
            w.spawnParticle(Particle.SMALL_FLAME, x, y, z, 2, 0.02, 0.02, 0.02, 0.0)
            w.rayTraceEntities(
                org.bukkit.Location(w, x, y, z),
                start.direction,
                0.4,
                0.6,
                TargetFilter(esper.player)
            )?.hitEntity?.let {
                if (it is LivingEntity && hitSet.add(it)) {
                    it.fireTicks = max(60, it.fireTicks)
                    it.psychicDamage(dmg)
                }
            }
        }
    }

    @EventHandler
    fun onPlayerToggleSneak(event: PlayerToggleSneakEvent) {
        val player = event.player
        if (!player.isSneaking) return
        if (!player.inventory.itemInMainHand.isSimilar(concept.wand!!)) return
        if (session == null) {
            val result = test()

            if (result != TestResult.Success) {
                result.message(this)?.let { player.sendActionBar(it) }
                return
            }

            startSession() // 시작
        } else {
            // 완료(확정)
            finalizeChantBySneak()
        }
    }
}
