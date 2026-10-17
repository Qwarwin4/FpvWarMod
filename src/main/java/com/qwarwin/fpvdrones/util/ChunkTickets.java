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

    private ChunkTickets() {
    }
}
