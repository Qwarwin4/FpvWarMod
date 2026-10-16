package com.qwarwin.fpvdrones.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.server.TicketType;

// short lived tickets, callers refresh them while something is flying around
public final class ChunkTickets {
    public static final TicketType<Integer> FLIGHT = TicketType.create("fpvdrones_flight", Integer::compareTo, 60);

    public static void keep(ServerWorld world, Entity entity, Vector3d at, int radius) {
        ChunkPos pos = new ChunkPos(((int) Math.floor(at.x)) >> 4, ((int) Math.floor(at.z)) >> 4);
        world.getChunkSource().addRegionTicket(FLIGHT, pos, radius, entity.getId());
    }

    private ChunkTickets() {
    }
}
