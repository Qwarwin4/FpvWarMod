package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.client.ClientPacketHandler;
import com.qwarwin.fpvdrones.entity.DroneKind;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class SessionStatePacket {
    public static final int START = 0, STOP = 1, SIGNAL = 2;

    public final int type;
    public int entityId;
    public DroneKind kind = DroneKind.FPV;
    public double homeX, homeY, homeZ;
    public double targetX, targetY, targetZ;
    public String reason = "";
    public float signal = 1F;

    private SessionStatePacket(int type) {
        this.type = type;
    }

    public static SessionStatePacket start(int entityId, DroneKind kind, double hx, double hy, double hz, Vector3d target) {
        SessionStatePacket p = new SessionStatePacket(START);
        p.entityId = entityId;
        p.kind = kind;
        p.homeX = hx;
        p.homeY = hy;
        p.homeZ = hz;
        p.targetX = target.x;
        p.targetY = target.y;
        p.targetZ = target.z;
        return p;
    }

    public static SessionStatePacket stop(String reason) {
        SessionStatePacket p = new SessionStatePacket(STOP);
        p.reason = reason;
        return p;
    }

    public static SessionStatePacket signal(float q) {
        SessionStatePacket p = new SessionStatePacket(SIGNAL);
        p.signal = q;
        return p;
    }

    public static void encode(SessionStatePacket m, PacketBuffer buf) {
        buf.writeByte(m.type);
        switch (m.type) {
            case START:
                buf.writeVarInt(m.entityId);
                buf.writeByte(m.kind.ordinal());
                buf.writeDouble(m.homeX);
                buf.writeDouble(m.homeY);
                buf.writeDouble(m.homeZ);
                buf.writeDouble(m.targetX);
                buf.writeDouble(m.targetY);
                buf.writeDouble(m.targetZ);
                break;
            case STOP:
                buf.writeUtf(m.reason, 128);
                break;
            default:
                buf.writeFloat(m.signal);
                break;
        }
    }

    public static SessionStatePacket decode(PacketBuffer buf) {
        SessionStatePacket m = new SessionStatePacket(buf.readByte());
        switch (m.type) {
            case START:
                m.entityId = buf.readVarInt();
                m.kind = DroneKind.byId(buf.readByte());
                m.homeX = buf.readDouble();
                m.homeY = buf.readDouble();
                m.homeZ = buf.readDouble();
                m.targetX = buf.readDouble();
                m.targetY = buf.readDouble();
                m.targetZ = buf.readDouble();
                break;
            case STOP:
                m.reason = buf.readUtf(128);
                break;
            default:
                m.signal = buf.readFloat();
                break;
        }
        return m;
    }

    public static void handle(SessionStatePacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.session(m)));
        ctx.get().setPacketHandled(true);
    }
}
