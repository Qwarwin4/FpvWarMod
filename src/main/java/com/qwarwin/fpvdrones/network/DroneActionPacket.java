package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.launch.Launches;
import com.qwarwin.fpvdrones.session.SessionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class DroneActionPacket {
    public static final int TOGGLE_VIEW = 0;
    public static final int PRIMARY = 1;
    public static final int VEHICLE_MENU = 2;

    private final int action;

    public DroneActionPacket(int action) {
        this.action = action;
    }

    public static void encode(DroneActionPacket m, PacketBuffer buf) {
        buf.writeVarInt(m.action);
    }

    public static DroneActionPacket decode(PacketBuffer buf) {
        return new DroneActionPacket(buf.readVarInt());
    }

    public static void handle(DroneActionPacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity p = ctx.get().getSender();
            if (p == null) {
                return;
            }
            switch (m.action) {
                case TOGGLE_VIEW:
                    SessionManager.toggle(p);
                    break;
                case PRIMARY: {
                    Entity t = SessionManager.getTarget(p);
                    if (t instanceof AbstractDroneEntity) {
                        ((AbstractDroneEntity) t).primaryAction(p);
                    }
                    break;
                }
                case VEHICLE_MENU:
                    if (p.getVehicle() instanceof LaunchTruckEntity) {
                        Launches.openMenu(p, (LaunchTruckEntity) p.getVehicle());
                    }
                    break;
                default:
                    break;
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
