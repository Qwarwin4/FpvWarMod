package com.qwarwin.fpvdrones.util;

import net.minecraft.util.RegistryKey;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Electronic warfare: the jammers that are switched on report themselves every tick, drones ask how much
 * noise there is where they are. A jammer leaves its owner's (and his team's) drones alone: they work on
 * frequencies it doesn't cover.
 */
public final class Jamming {
    private static final Map<UUID, Source> SOURCES = new HashMap<>();

    private static final class Source {
        RegistryKey<World> dim;
        Vector3d pos;
        double radius;
        UUID owner;
        long seen;
    }

    /** A jammer is on at pos this tick. */
    public static void report(World level, UUID id, Vector3d pos, double radius, @Nullable UUID owner) {
        Source s = SOURCES.computeIfAbsent(id, k -> new Source());
        s.dim = level.dimension();
        s.pos = pos;
        s.radius = radius;
        s.owner = owner;
        s.seen = level.getGameTime();
    }

    public static void off(UUID id) {
        SOURCES.remove(id);
    }

    /**
     * Noise at pos for a drone of {@code victim}: 0 none, 1 right on top of a jammer. Strongest at the
     * jammer and fading to its edge; several jammers add up.
     */
    public static double level(World level, Vector3d pos, @Nullable UUID victim) {
        if (SOURCES.isEmpty()) {
            return 0;
        }
        long now = level.getGameTime();
        double clear = 1.0;
        for (Iterator<Source> it = SOURCES.values().iterator(); it.hasNext(); ) {
            Source s = it.next();
            if (now - s.seen > 40 || now < s.seen) {
                // switched off, unloaded or gone without saying so
                it.remove();
                continue;
            }
            if (s.dim != level.dimension() || Targeting.isFriendly(level, s.owner, victim)) {
                continue;
            }
            double d = s.pos.distanceTo(pos);
            if (d < s.radius) {
                clear *= d / s.radius;
            }
        }
        return 1.0 - clear;
    }

    private Jamming() {
    }
}
