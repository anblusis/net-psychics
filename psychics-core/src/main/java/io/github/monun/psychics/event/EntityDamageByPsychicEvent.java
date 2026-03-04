package io.github.monun.psychics.event;

import io.github.monun.psychics.Ability;
import io.github.monun.psychics.AbilityConcept;
import io.github.monun.psychics.damage.DamageType;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;

public class EntityDamageByPsychicEvent extends EntityDamageByEntityEvent {

    private final Ability<? extends AbilityConcept> ability;

    private final DamageType damageType;

    private Location knockbackSource;

    private double knockbackForce;

    /**
     * 기존 코드와 동일한 사용성을 유지하기 위한 생성자입니다.
     * 최신 API의 damageSource/modifiers/critical은 "기본값"으로 채웁니다.
     */
    public EntityDamageByPsychicEvent(
            @NotNull Entity damager,
            @NotNull Entity damagee,
            double damage,
            @NotNull Ability<? extends AbilityConcept> ability,
            @NotNull DamageType damageType,
            @Nullable Location knockbackSource,
            double knockbackForce
    ) {
        this(
                damager,
                damagee,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                createDefaultDamageSource(damager),
                createDefaultModifiers(damage),
                createDefaultModifierFunctions(),
                false,
                ability,
                damageType,
                knockbackSource,
                knockbackForce
        );
    }

    public EntityDamageByPsychicEvent(
            @NotNull Entity damager,
            @NotNull Entity damagee,
            @NotNull EntityDamageEvent.DamageCause cause,
            @NotNull DamageSource damageSource,
            @NotNull Map<EntityDamageEvent.DamageModifier, Double> modifiers,
            @NotNull Map<EntityDamageEvent.DamageModifier, ? extends com.google.common.base.Function<? super Double, Double>> modifierFunctions,
            boolean critical,
            @NotNull Ability<? extends AbilityConcept> ability,
            @NotNull DamageType damageType,
            @Nullable Location knockbackSource,
            double knockbackForce
    ) {
        super(damager, damagee, cause, damageSource, modifiers, modifierFunctions, critical);

        this.ability = ability;
        this.damageType = damageType;
        this.knockbackSource = knockbackSource;
        this.knockbackForce = knockbackForce;
    }

    private static @NotNull DamageSource createDefaultDamageSource(@NotNull Entity damager) {
        return DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK)
                .withDirectEntity(damager)
                .build();
    }

    private static @NotNull Map<EntityDamageEvent.DamageModifier, Double> createDefaultModifiers(double damage) {
        return Collections.singletonMap(EntityDamageEvent.DamageModifier.BASE, damage);
    }

    private static @NotNull Map<EntityDamageEvent.DamageModifier, com.google.common.base.Function<? super Double, Double>> createDefaultModifierFunctions() {
        return Collections.singletonMap(EntityDamageEvent.DamageModifier.BASE, input -> input);
    }

    @NotNull
    public Ability<? extends AbilityConcept> getAbility() {
        return ability;
    }

    @NotNull
    public DamageType getDamageType() {
        return damageType;
    }

    @NotNull
    public Location getKnockbackSource() {
        return knockbackSource;
    }

    public void setKnockbackSource(@Nullable Location knockbackSource) {
        this.knockbackSource = knockbackSource;
    }

    public double getKnockbackForce() {
        return knockbackForce;
    }

    public void setKnockbackForce(double knockbackForce) {
        this.knockbackForce = knockbackForce;
    }

}
