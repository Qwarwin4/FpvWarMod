package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.FpvDrones;
import net.minecraft.util.ResourceLocation;

/**
 * KORNET and ATAKA ride the operator's line of sight (SACLOS): the missile goes wherever the sight points.
 * JAVELIN is fire-and-forget: locked before launch, flies a top-attack arc on its own.
 */
public enum MissileType {
    KORNET("kornet_missile", 4.0, 220, 7F, true),
    ATAKA("ataka_missile", 4.5, 200, 6F, true),
    JAVELIN("javelin_missile", 3.2, 220, 5F, false);

    private final String name;
    private final double speed;
    private final int life;
    private final float turnRate;
    private final boolean beamRiding;

    MissileType(String name, double speed, int life, float turnRate, boolean beamRiding) {
        this.name = name;
        this.speed = speed;
        this.life = life;
        this.turnRate = turnRate;
        this.beamRiding = beamRiding;
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
