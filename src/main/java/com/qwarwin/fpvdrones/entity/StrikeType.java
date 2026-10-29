package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.registry.ModItems;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

public enum StrikeType {
    // modelOffset: shift (in model px) that puts the middle of the fuselage on the origin
    SHAHED_136("shahed136", 14F, -3.0F, true),
    GERAN_2("geran2", 16F, -3.0F, true),
    GERAN_5("geran5", 22F, -1.8F, false),
    SHAHED_238("shahed238", 14F, -3.0F, false);

    private final String name;
    private final float health;
    private final float modelOffset;
    private final boolean propeller;

    StrikeType(String name, float health, float modelOffset, boolean propeller) {
        this.name = name;
        this.health = health;
        this.modelOffset = modelOffset;
        this.propeller = propeller;
    }

    public String getName() {
        return name;
    }

    public float getHealth() {
        return health;
    }

    public float getModelOffset() {
        return modelOffset;
    }

    public boolean hasPropeller() {
        return propeller;
    }

    public boolean isJet() {
        return !propeller;
    }

    public ResourceLocation model() {
        return FpvDrones.id("entity/" + name);
    }

    public String translationKey() {
        return "item." + FpvDrones.MODID + "." + name;
    }

    /** Geran-5 is a bigger airframe than the Shahed-238 model it's drawn with: about 6 m long, 5.5 m span. */
    public float sizeFactor() {
        return this == GERAN_5 ? 1.6F : 1F;
    }

    public float power() {
        switch (this) {
            case GERAN_2:
                return Config.GERAN2_POWER.get().floatValue();
            case GERAN_5:
                return Config.GERAN5_POWER.get().floatValue();
            default:
                return Config.SHAHED_POWER.get().floatValue();
        }
    }

    public double speed() {
        if (this == GERAN_5) {
            return Config.GERAN5_SPEED.get();
        }
        if (this == SHAHED_238) {
            return Config.SHAHED238_SPEED.get();
        }
        return Config.SHAHED_SPEED.get();
    }

    public Item item() {
        switch (this) {
            case GERAN_2:
                return ModItems.GERAN2.get();
            case GERAN_5:
                return ModItems.GERAN5.get();
            case SHAHED_238:
                return ModItems.SHAHED238.get();
            default:
                return ModItems.SHAHED.get();
        }
    }

    public static StrikeType byId(int id) {
        StrikeType[] v = values();
        return id >= 0 && id < v.length ? v[id] : SHAHED_136;
    }

    @Nullable
    public static StrikeType byIdOrNull(int id) {
        StrikeType[] v = values();
        return id >= 0 && id < v.length ? v[id] : null;
    }
}
