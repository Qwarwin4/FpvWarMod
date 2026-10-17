package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.FpvDrones;
import net.minecraft.util.ResourceLocation;

/**
 * KORNET and ATAKA ride the operator's line of sight (SACLOS): the missile goes wherever the sight points.
 * JAVELIN is fire-and-forget: locked before launch, flies a top-attack arc on its own.
 */
public enum MissileType {
    // punch: multiplier on the configured ATGM damage and blast
    KORNET("kornet_missile", 4.0, 220, 7F, true, 1.8F),
    ATAKA("ataka_missile", 4.5, 200, 6F, true, 1.6F),
    JAVELIN("javelin_missile", 3.2, 220, 5F, false, 1.7F);

    private final String name;
    private final double speed;
    private final int life;
    private final float turnRate;
    private final boolean beamRiding;
    private final float punch;

    MissileType(String name, double speed, int life, float turnRate, boolean beamRiding, float punch) {
        this.name = name;
        this.speed = speed;
        this.life = life;
        this.turnRate = turnRate;
        this.beamRiding = beamRiding;
        this.punch = punch;
    }

    public float punch() {
        return punch;
    }

    public double speed() {
        return speed;
    }

    public int life() {
        return life;
    }

    /** Degrees per tick. */
    public float turnRate() {
        return turnRate;
    }

    public boolean isBeamRiding() {
        return beamRiding;
    }

    public ResourceLocation model() {
        return FpvDrones.id("entity/" + name + "_flight");
    }

    public static MissileType byId(int id) {
        MissileType[] v = values();
        return v[Math.max(0, Math.min(v.length - 1, id))];
    }
}
