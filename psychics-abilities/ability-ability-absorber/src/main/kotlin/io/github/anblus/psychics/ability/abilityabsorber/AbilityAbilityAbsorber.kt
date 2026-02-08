package io.github.anblus.psychics.ability.abilityabsorber

import com.google.common.collect.ImmutableList
import io.github.monun.psychics.*
import io.github.monun.psychics.attribute.EsperStatistic
import io.github.monun.psychics.tooltip.TooltipBuilder
import io.github.monun.psychics.tooltip.stats
import io.github.monun.tap.config.Config
import io.github.monun.tap.config.Name
import io.github.monun.tap.event.EntityProvider
import io.github.monun.tap.event.TargetEntity
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

@Name("ability-absorber")
class AbilityConceptAbilityAbsorber : AbilityConcept() {

    @Config
    var maxAbilityCount = 10

    init {
        displayName = "능력 흡수"
        description = listOf(
            text("상대를 처치하면 그 플레이어의 능력을 흡수합니다."),
            text("사망 시 흡수한 능력은 모두 사라집니다."),
            // 테스트 설명은 임시 비활성화
            // text("[테스트] 책 들고 좌클릭: 랜덤 능력 1개 흡수").color(NamedTextColor.GRAY)
        )
    }

    override fun onRenderTooltip(tooltip: TooltipBuilder, stats: (EsperStatistic) -> Double) {
        tooltip.stats(maxAbilityCount) { NamedTextColor.GREEN to "최대 능력 개수" to "개" }
    }
}

class AbilityAbilityAbsorber : Ability<AbilityConceptAbilityAbsorber>(), Listener {
    // 동기화 객체: 흡수 작업을 순차적으로 처리
    private val absorptionLock = Any()

    override fun onEnable() {
        psychic.registerEvents(this)
    }

    // 책 들고 좌클릭: 랜덤 능력 1개 흡수 (테스트용) - 임시 비활성화
    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_AIR && event.action != Action.LEFT_CLICK_BLOCK) return
        val item = event.item ?: return
        if (item.type != Material.BOOK) return

         event.isCancelled = true
         absorbRandomAbility()
    }

    @EventHandler
    fun onOwnerDeath(event: PlayerDeathEvent) {
        esper.attachPsychic(psychic.manager.getPsychicConcept(concept.name)!!).isEnabled = true
    }

    // 실제 PvP 킬 시 자동 흡수
    @EventHandler
    @TargetEntity(EntityProvider.EntityDeath.Killer::class)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.entity

        val manager: PsychicManager = psychic.manager
        val victimEsper = manager.getEsper(victim) ?: return
        val victimPsychic = victimEsper.psychic ?: return

        // 흡수할 능력 목록 수집
        val toAbsorbConcepts = victimPsychic.abilities.map { it.concept }

        if (toAbsorbConcepts.isEmpty()) return

        // 동기화된 흡수 실행
        val absorbed = absorbAbilities(toAbsorbConcepts)

        if (absorbed > 0) {
            esper.player.sendMessage(
                text("${victim.name}의 능력을 흡수했습니다!").color(NamedTextColor.GOLD)
            )
        }
    }

    // 테스트용: 랜덤 능력 1개를 kill 방식과 동일하게 흡수 (현재 미사용)
    private fun absorbRandomAbility() {
        val manager = psychic.manager
        val allAbilities = manager.abilityContainersById.values.toList()

        if (allAbilities.isEmpty()) {
            esper.player.sendMessage(text("[테스트] 사용 가능한 능력이 없습니다!").color(NamedTextColor.RED))
            return
        }

        val existingIds = psychic.abilities.map { it.concept.container.description.artifactId }.toSet()
        val availableAbilities = allAbilities.filter { it.description.artifactId !in existingIds }

        if (availableAbilities.isEmpty()) {
            esper.player.sendMessage(text("[테스트] 더 이상 흡수할 수 있는 새로운 능력이 없습니다!").color(NamedTextColor.YELLOW))
            return
        }

        // 랜덤으로 1개만 선택하고 donor 개념을 '정상 초기화'하여 생성
        val container = availableAbilities.random()
        val donor = container.conceptClass.getConstructor().newInstance()
        runCatching {
            val init = AbilityConcept::class.java.getDeclaredMethod(
                "initialize",
                String::class.java,
                AbilityContainer::class.java,
                PsychicConcept::class.java,
                org.bukkit.configuration.ConfigurationSection::class.java
            )
            init.isAccessible = true
            val tmpPsychic = PsychicConcept::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val conf = org.bukkit.configuration.file.YamlConfiguration().createSection("ability")
            // 비어있는 설정으로 초기화하면 컨테이너 기본값이 적용됨
            init.invoke(donor, container.description.name, container, tmpPsychic, conf)
            // 선택적으로 onInitialize 호출 (일부 능력이 내부 상태를 구성하는 경우 대비)
            runCatching { donor.onInitialize() }
        }.onFailure {
            // 실패 시 최소한 container만 주입된 donor를 사용하되, 이후 clone에서 필드들이 비어있을 수 있음
            val containerField = AbilityConcept::class.java.getDeclaredField("container").apply { isAccessible = true }
            containerField.set(donor, container)
        }

        // 동기화된 흡수 실행: 완전 초기화된 donor를 넘김
        val absorbed = absorbAbilities(listOf(donor))

        if (absorbed > 0) {
            esper.player.sendMessage(
                text("✨ [테스트] ${container.description.name} 능력을 흡수했습니다!").color(NamedTextColor.GREEN)
            )
            esper.player.sendMessage(
                text("현재 총 능력 수: ${psychic.abilities.size}개").color(NamedTextColor.AQUA)
            )
        }
    }

    /**
     * 능력 흡수의 핵심 로직 - 동기화되어 동시 실행 방지
     * @param conceptsToAbsorb 흡수할 능력 컨셉 목록
     * @return 실제로 흡수된 능력 개수
     */
    private fun absorbAbilities(conceptsToAbsorb: List<AbilityConcept>): Int {
        // 동기화 블록: 한 번에 하나의 흡수 작업만 실행
        synchronized(absorptionLock) {
            val manager = psychic.manager
            val killerPsychic = psychic

            // 현재 시점의 능력 목록 기준으로 중복 체크
            val existingIds = killerPsychic.abilities.map { it.concept.container.description.artifactId }.toMutableSet()

            // 최대 개수 제한 계산
            val currentCount = existingIds.size
            if (currentCount >= concept.maxAbilityCount) {
                return 0
            }
            val capacity = concept.maxAbilityCount - currentCount

            // 중복 제거 + 남은 용량만큼만 선택
            val toClone = conceptsToAbsorb
                .asSequence()
                .filter { existingIds.add(it.container.description.artifactId) }
                .take(capacity)
                .toList()

            if (toClone.isEmpty()) return 0

            // 새로운 PsychicConcept 생성 및 기본 설정 복사
            val baseConcept = killerPsychic.concept
            val ctor = PsychicConcept::class.java.getDeclaredConstructor().apply { isAccessible = true }
            val newConcept = ctor.newInstance()
            copyPsychicConcept(baseConcept, newConcept)

            // 기존 능력은 이름/설정 유지하되 새 컨셉 기준으로 재복제
            val reClonedExisting = baseConcept.abilityConcepts.map { cloneAbilityConcept(it, newConcept) }

            // 새로 추가되는 능력은 name 충돌 방지를 위해 고유 이름을 강제 부여
            val usedNames = reClonedExisting.map { safeGetName(it) }.toMutableSet()
            val addedClones = toClone.map { src ->
                val dst = cloneAbilityConcept(src, newConcept)
                val baseName = runCatching { src.name }.getOrElse { src.container.description.name }
                val unique = uniqueName(baseName, usedNames)
                setName(dst, unique)
                usedNames += unique
                dst
            }

            // artifactId 기준으로 최종 중복 제거 후 최대 개수 제한 재확인
            val merged = (reClonedExisting + addedClones)
                .distinctBy { it.container.description.artifactId }
                .take(concept.maxAbilityCount)

            val actuallyAdded = merged.size - reClonedExisting.size

            // 초기화
            initializeModulesManual(newConcept, manager, merged)

            // 유효성 검증 및 보정
            validateAndFixConcepts(newConcept)

            // 마나 보존: 교체 전 현재 마나를 저장해 교체 후 복원 (새 최대치에 맞게 보정)
            val preservedMana = killerPsychic.mana

            // 새 컨셉으로 교체 (이 작업도 동기화 블록 안에서 실행)
            val newPsychic = esper.attachPsychic(newConcept)
            // attach 이후 마나/바 생성되므로 여기서 복원
            newPsychic.mana = preservedMana.coerceIn(0.0, newPsychic.concept.mana)
            newPsychic.isEnabled = true

            return actuallyAdded
        }
    }

    private fun validateAndFixConcepts(concept: PsychicConcept) {
        val list = concept.abilityConcepts

        // 1) 모든 항목이 동일한 PsychicConcept를 가리키는지 확인
        var fixed = false
        list.forEach { ac ->
            runCatching {
                val f = AbilityConcept::class.declaredMemberProperties.firstOrNull { it.name == "psychicConcept" }?.javaField
                f?.isAccessible = true
                val current = f?.get(ac) as? PsychicConcept
                if (current !== concept) {
                    f?.set(ac, concept)
                    fixed = true
                }
            }
        }

        // 2) name 유일성 보장 (GUI 매핑/저장 충돌 방지)
        val used = hashSetOf<String>()
        list.forEach { ac ->
            var n = safeGetName(ac)
            if (!used.add(n)) {
                n = uniqueName(n, used)
                setName(ac, n)
                used += n
                fixed = true
            }
        }

        // 3) displayName/description이 비었으면 최소 기본값 채우기
        list.forEach { ac ->
            runCatching {
                val dnF = AbilityConcept::class.declaredMemberProperties.firstOrNull { it.name == "displayName" }?.javaField
                dnF?.isAccessible = true
                val dn = dnF?.get(ac) as? String
                if (dn.isNullOrBlank()) dnF?.set(ac, safeGetName(ac))

                val descF = AbilityConcept::class.declaredMemberProperties.firstOrNull { it.name == "description" }?.javaField
                descF?.isAccessible = true
                val desc = descF?.get(ac) as? List<*>
                if (desc == null) {
                    descF?.set(ac, listOf(text("설명이 없습니다").color(NamedTextColor.WHITE)))
                    fixed = true
                }
            }
        }

        if (fixed) {
            // 디버그 로그
            val info = list.joinToString { "${safeGetName(it)}(${it.container.description.artifactId})" }
            esper.player.sendMessage(text("[디버그] 능력 목록 정리: ").append(text(info).color(NamedTextColor.GRAY)))
        }
    }

    // 이름 충돌 방지용 유틸들
    private fun uniqueName(base: String, used: MutableSet<String>): String {
        if (base !in used) return base
        var i = 2
        while (true) {
            val candidate = "$base-$i"
            if (candidate !in used) return candidate
            i++
        }
    }

    private fun setName(concept: AbilityConcept, name: String) {
        runCatching {
            val f = AbilityConcept::class.declaredMemberProperties.firstOrNull { it.name == "name" }?.javaField
            f?.isAccessible = true
            f?.set(concept, name)
        }
    }

    private fun safeGetName(concept: AbilityConcept): String {
        return runCatching { concept.name }.getOrElse { concept.container.description.name }
    }

    private fun copyPsychicConcept(from: PsychicConcept, to: PsychicConcept) {
        val kFrom = PsychicConcept::class
        val kTo = PsychicConcept::class
        fun set(propName: String, value: Any?) {
            val p = kTo.declaredMemberProperties.firstOrNull { it.name == propName } ?: return
            p.isAccessible = true
            val javaField = p.javaField
            javaField?.isAccessible = true
            javaField?.set(to, value)
        }
        fun get(propName: String): Any? {
            val p = kFrom.declaredMemberProperties.firstOrNull { it.name == propName } ?: return null
            p.isAccessible = true
            val javaField = p.javaField
            javaField?.isAccessible = true
            return javaField?.get(from)
        }
        set("manager", get("manager"))
        set("name", get("name"))
        set("displayName", get("displayName"))
        set("healthBonus", get("healthBonus"))
        set("healthRegenPerSecond", get("healthRegenPerSecond"))
        set("mana", get("mana"))
        set("manaRegenPerSecond", get("manaRegenPerSecond"))
        set("manaColor", get("manaColor"))
        // descriptionRaw 및 description까지 함께 복사하여 스타일 유지 (보라색 기울임 방지)
        runCatching { set("descriptionRaw", get("descriptionRaw")) }
        runCatching { set("description", get("description")) }
    }

    private fun cloneAbilityConcept(src: AbilityConcept, target: PsychicConcept): AbilityConcept {
        val clazz = src.container.conceptClass
        val dst = clazz.getConstructor().newInstance()

        // 공통 프로퍼티를 리플렉션으로 복사
        val kFrom = AbilityConcept::class
        val kTo = AbilityConcept::class
        fun setDst(name: String, value: Any?) {
            val p = kTo.declaredMemberProperties.firstOrNull { it.name == name } ?: return
            p.isAccessible = true
            val f = p.javaField
            f?.isAccessible = true
            f?.set(dst, value)
        }
        fun getSrc(name: String): Any? {
            val p = kFrom.declaredMemberProperties.firstOrNull { it.name == name } ?: return null
            p.isAccessible = true
            val f = p.javaField
            f?.isAccessible = true
            return f?.get(src)
        }

        // 필수 필드부터 먼저 설정 (container는 이미 설정되어 있어야 함)
        setDst("container", src.container)
        setDst("psychicConcept", target)

        // name 필드는 반드시 초기화되어야 함 (save 시 사용됨)
        val srcName = getSrc("name")
        if (srcName != null) {
            setDst("name", srcName)
        } else {
            // name이 없으면 container의 이름을 사용
            setDst("name", src.container.description.name)
        }

        // 나머지 프로퍼티 복사
        setDst("logger", getSrc("logger"))
        setDst("displayName", getSrc("displayName"))
        setDst("type", getSrc("type"))
        setDst("levelRequirement", getSrc("levelRequirement"))
        setDst("cooldownTime", getSrc("cooldownTime"))
        setDst("cost", getSrc("cost"))
        setDst("castingTime", getSrc("castingTime"))
        setDst("interruptible", getSrc("interruptible"))
        setDst("castingBarColor", getSrc("castingBarColor"))
        setDst("durationTime", getSrc("durationTime"))
        setDst("range", getSrc("range"))
        setDst("damage", getSrc("damage"))
        setDst("knockback", getSrc("knockback"))
        setDst("healing", getSrc("healing"))

        // wand/supplyItems 복사 (supply item을 제대로 복사하도록 수정)
        runCatching {
            val wand = getSrc("_wand")
            if (wand != null) setDst("_wand", wand)
        }
        runCatching {
            val supplyItems = getSrc("_supplyItems")
            if (supplyItems != null) {
                setDst("_supplyItems", supplyItems)
            }
        }
        runCatching { setDst("descriptionRaw", getSrc("descriptionRaw")) }

        // description 복사 시 스타일 적용 (흰색 + 기울임체 해제)
        runCatching {
            val srcDesc = getSrc("description") as? List<*>
            if (srcDesc != null) {
                val styledDesc = srcDesc.mapNotNull { component ->
                    if (component is net.kyori.adventure.text.Component) {
                        component
                            .color(NamedTextColor.WHITE)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
                    } else {
                        null
                    }
                }
                setDst("description", styledDesc)
            }
        }

        return dst
    }

    private fun initializeModulesManual(concept: PsychicConcept, manager: PsychicManager, abilities: List<AbilityConcept>) {
        // manager는 copyPsychicConcept에서 이미 설정됨. 안전을 위해 다시 설정
        runCatching {
            val k = PsychicConcept::class
            val f = k.declaredMemberProperties.firstOrNull { it.name == "manager" }?.javaField
            f?.isAccessible = true
            f?.set(concept, manager)
        }
        // abilityConcepts 설정 (ImmutableList로 고정)
        runCatching {
            val field = PsychicConcept::class.declaredMemberProperties.firstOrNull { it.name == "abilityConcepts" }?.javaField
            field?.isAccessible = true
            field?.set(concept, ImmutableList.copyOf(abilities))
        }
        // 각 AbilityConcept.onInitialize 호출 + logger 보강 주입
        for (ac in abilities) {
            // logger가 미설정이면 manager.logger로 채움
            runCatching {
                val field = AbilityConcept::class.declaredMemberProperties.firstOrNull { it.name == "logger" }?.javaField
                field?.isAccessible = true
                val current = field?.get(ac)
                if (current == null) field?.set(ac, manager.logger)
            }
            runCatching { ac.onInitialize() }
        }
    }
}
