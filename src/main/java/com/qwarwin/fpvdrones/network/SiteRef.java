package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.launch.LaunchSite;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/** Points at a launch site over the network: block position or entity id. */
public final class SiteRef {
    public final boolean entity;
    public final BlockPos pos;
    public final int entityId;

    private SiteRef(boolean entity, BlockPos pos, int entityId) {
        this.entity = entity;
        this.pos = pos;
        this.entityId = entityId;
    }

    public static SiteRef of(LaunchSite site) {
        if (site instanceof Entity) {
            return new SiteRef(true, BlockPos.ZERO, ((Entity) site).getId());
        }
        return new SiteRef(false, ((TileEntity) site).getBlockPos(), -1);
    }

    public void write(PacketBuffer buf) {
        buf.writeBoolean(entity);
        if (entity) {
            buf.writeVarInt(entityId);
        } else {
            buf.writeBlockPos(pos);
        }
    }

    public static SiteRef read(PacketBuffer buf) {
        boolean entity = buf.readBoolean();
        return entity ? new SiteRef(true, BlockPos.ZERO, buf.readVarInt()) : new SiteRef(false, buf.readBlockPos(), -1);
    }

    @Nullable
    public LaunchSite resolve(PlayerEntity player) {
        if (entity) {
            Entity e = player.level.getEntity(entityId);
            if (e instanceof LaunchSite && e.isAlive() && e.distanceToSqr(player) < 12 * 12) {
                return (LaunchSite) e;
            }
            return null;
        }
        if (!player.level.isLoaded(pos) || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
            return null;
        }
        TileEntity te = player.level.getBlockEntity(pos);
        return te instanceof LaunchSite ? (LaunchSite) te : null;
    }
}
