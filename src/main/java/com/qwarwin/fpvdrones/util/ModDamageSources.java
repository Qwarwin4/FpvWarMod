package com.qwarwin.fpvdrones.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.util.IndirectEntityDamageSource;

import javax.annotation.Nullable;

public final class ModDamageSources {
    public static final String SHELL = "fpvdrones.shell";

    public static DamageSource shrapnel(@Nullable Entity attacker) {
        return attacker == null ? new DamageSource("fpvdrones.shrapnel")
                : new EntityDamageSource("fpvdrones.shrapnel_by", attacker);
    }

    // shaped charge jet goes straight through armour
    public static DamageSource cumulative(@Nullable Entity attacker) {
        DamageSource src = attacker == null ? new DamageSource("fpvdrones.cumulative")
                : new EntityDamageSource("fpvdrones.cumulative_by", attacker);
        return src.bypassArmor();
    }

    public static DamageSource shell(Entity shell, @Nullable Entity attacker) {
        return attacker == null ? new DamageSource(SHELL).setProjectile()
                : new IndirectEntityDamageSource(SHELL + "_by", shell, attacker).setProjectile();
    }

    private ModDamageSources() {
    }
}
