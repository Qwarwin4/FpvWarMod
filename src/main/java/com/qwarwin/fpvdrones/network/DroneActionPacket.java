package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.entity.MavicEntity;
import com.qwarwin.fpvdrones.item.JavelinItem;
import com.qwarwin.fpvdrones.launch.Launches;
import com.qwarwin.fpvdrones.session.SessionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.fml.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

public class DroneActionPacket {
    public static final int TOGGLE_VIEW = 0;
    public static final int PRIMARY = 1;
    public static final int VEHICLE_MENU = 2;
    public static final int RETURN_HOME = 3;
    public static final int JAVELIN_FIRE = 4;

    private final int action;
    // what the camera was pointed at when H was pressed
    @Nullable
    private final BlockPos mark;

    public DroneActionPacket(int action) {
        this(action, null);
    }

    public DroneActionPacket(int action, @Nullable BlockPos mark) {
        this.action = action;
        this.mark = mark;
    }

    public static void encode(DroneActionPacket m, PacketBuffer buf) {
        buf.writeVarInt(m.action);
        buf.writeBoolean(m.mark != null);
        if (m.mark != null) {
            buf.writeBlockPos(m.mark);
        }
    }

    public static DroneActionPacket decode(PacketBuffer buf) {
        int action = buf.readVarInt();
        return new DroneActionPacket(action, buf.readBoolean() ? buf.readBlockPos() : null);
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
                case RETURN_HOME: {
                    Entity t = SessionManager.getTarget(p);
                    if (t instanceof MavicEntity) {
                        MavicEntity mavic = (MavicEntity) t;
                        if (mavic.isReturning()) {
                            mavic.cancelReturn(p);
                        } else if (!mavic.hasHome()) {
                            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.rth_no_home"), true);
                        } else {
                            mavic.startReturn(p, m.mark, "msg.fpvdrones.rth_manual");
                        }
                    }
                    break;
                }
                case JAVELIN_FIRE:
                    if (p.isUsingItem() && p.getUseItem().getItem() instanceof JavelinItem) {
                        ((JavelinItem) p.getUseItem().getItem()).fire(p);
                    }
                    break;
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
