package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.entity.MountedWeaponEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/** Gunner's trigger state plus one-shot clicks (RMB = secondary weapon, G = mode). */
public class WeaponInputPacket {
    private static final int FIRING = 1, SECONDARY = 2, ACTION = 4;

    private final int flags;

    public WeaponInputPacket(boolean firing, boolean secondary, boolean action) {
        this.flags = (firing ? FIRING : 0) | (secondary ? SECONDARY : 0) | (action ? ACTION : 0);
    }

    private WeaponInputPacket(int flags) {
        this.flags = flags;
    }

    public static void encode(WeaponInputPacket m, PacketBuffer buf) {
        buf.writeByte(m.flags);
    }

    public static WeaponInputPacket decode(PacketBuffer buf) {
        return new WeaponInputPacket(buf.readByte());
    }

    public static void handle(WeaponInputPacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity p = ctx.get().getSender();
            if (p != null && p.getVehicle() instanceof MountedWeaponEntity) {
                ((MountedWeaponEntity) p.getVehicle()).handleInput(p, (m.flags & FIRING) != 0,
                        (m.flags & SECONDARY) != 0, (m.flags & ACTION) != 0);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
