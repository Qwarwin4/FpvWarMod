package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.client.ClientPacketHandler;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

// server -> pilot: snap back, the last control packet looked wrong
public class DroneCorrectionPacket {
    public final int id;
    public final double x, y, z;

    public DroneCorrectionPacket(int id, double x, double y, double z) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static void encode(DroneCorrectionPacket m, PacketBuffer buf) {
        buf.writeVarInt(m.id);
        buf.writeDouble(m.x);
        buf.writeDouble(m.y);
        buf.writeDouble(m.z);
    }

    public static DroneCorrectionPacket decode(PacketBuffer buf) {
        return new DroneCorrectionPacket(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble());
    }

    public static void handle(DroneCorrectionPacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.correction(m)));
        ctx.get().setPacketHandled(true);
    }
}
