package com.qwarwin.fpvdrones.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.server.TicketType;

/**
 * Short lived chunk tickets for things flying around with nobody next to them. Callers refresh them every
 * few ticks; once the flight ends they time out on their own.
 *
 * A region ticket of radius r makes the centre chunk entity-ticking only up to r - 2 chunks out, so radius 3
 * keeps the 3x3 around the drone ticking and the ring outside it loaded.
 */
public final class ChunkTickets {
    public static final TicketType<Integer> FLIGHT = TicketType.create("fpvdrones_flight", Integer::compareTo, 60);
    private static final int RADIUS = 3;

    public static void keep(ServerWorld world, Entity entity, Vector3d at, int radius) {
        ChunkPos pos = new ChunkPos(((int) Math.floor(at.x)) >> 4, ((int) Math.floor(at.z)) >> 4);
        world.getChunkSource().addRegionTicket(FLIGHT, pos, radius, entity.getId());
    }

    /** Where it is now plus where it will be over the next {@code aheadTicks}, one ticket per second of flight. */
    public static void follow(ServerWorld world, Entity entity, Vector3d velocity, int aheadTicks) {
        keep(world, entity, entity.position(), RADIUS);
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (speed < 0.05) {
            return;
        }
        for (int t = 20; t <= aheadTicks; t += 20) {
            keep(world, entity, entity.position().add(velocity.scale(t)), RADIUS);
        }
    }

    public static final TicketType<Integer> PRELOAD = TicketType.create("fpvdrones_preload", Integer::compareTo, 100);

    /**
     * The world ahead of a drone someone is looking through: loaded (and generated if new) along the next
     * {@code seconds} of flight, {@code radius} chunks to each side, so the picture doesn't run into the void.
     * Refresh about once a second.
     */
    public static void preload(ServerWorld world, Entity entity, Vector3d velocity, int seconds, int radius) {
        double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (speed < 0.6) {
            return;
        }
        Vector3d dir = new Vector3d(velocity.x / speed, 0, velocity.z / speed);
        double reach = Math.min(800, speed * 20 * seconds);
        // small low-priority tickets starting where the view distance ends: the chunks round the pilot come first
        double from = Math.max(48, (world.getServer().getPlayerList().getViewDistance() - 2) * 16);
        for (double d = from; d <= reach; d += 40) {
            Vector3d at = entity.position().add(dir.scale(d));
            ChunkPos pos = new ChunkPos(((int) Math.floor(at.x)) >> 4, ((int) Math.floor(at.z)) >> 4);
            world.getChunkSource().addRegionTicket(PRELOAD, pos, radius, entity.getId());
        }
    }

    /** Whether a player is watching through it (drone view, tablet camera). */
    public static boolean watched(ServerWorld world, Entity entity) {
        for (net.minecraft.entity.player.ServerPlayerEntity p : world.players()) {
            if (p.getCamera() == entity) {
                return true;
            }
        }
        return false;
    }

    private ChunkTickets() {
    }
}
