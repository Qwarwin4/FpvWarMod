package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

// pilot client -> server, every tick
public class DroneControlPacket {
    public final int id;
    public final double x, y, z;
    public final float yaw, pitch;
    public final double vx, vy, vz;
    public final boolean impact;

    public DroneControlPacket(int id, double x, double y, double z, float yaw, float pitch, Vector3d v, boolean impact) {
        this(id, x, y, z, yaw, pitch, v.x, v.y, v.z, impact);
    }

    private DroneControlPacket(int id, double x, double y, double z, float yaw, float pitch,
                               double vx, double vy, double vz, boolean impact) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.impact = impact;
    }

    public static void encode(DroneControlPacket m, PacketBuffer buf) {
        buf.writeVarInt(m.id);
        buf.writeDouble(m.x);
        buf.writeDouble(m.y);
        buf.writeDouble(m.z);
        buf.writeFloat(m.yaw);
        buf.writeFloat(m.pitch);
        buf.writeFloat((float) m.vx);
        buf.writeFloat((float) m.vy);
        buf.writeFloat((float) m.vz);
        buf.writeBoolean(m.impact);
    }

    public static DroneControlPacket decode(PacketBuffer buf) {
        return new DroneControlPacket(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readBoolean());
    }

    public static void handle(DroneControlPacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity p = ctx.get().getSender();
            if (p == null) {
                return;
            }
            Entity e = p.getLevel().getEntity(m.id);
            if (e instanceof AbstractDroneEntity && Double.isFinite(m.x) && Double.isFinite(m.y) && Double.isFinite(m.z)) {
                ((AbstractDroneEntity) e).acceptControl(p, m);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
