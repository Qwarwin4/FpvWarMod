package com.qwarwin.fpvdrones.client;

import com.qwarwin.fpvdrones.client.screen.LauncherScreen;
import com.qwarwin.fpvdrones.control.ControlState;
import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.DroneKind;
import com.qwarwin.fpvdrones.network.DroneCorrectionPacket;
import com.qwarwin.fpvdrones.network.OpenLauncherPacket;
import com.qwarwin.fpvdrones.network.SessionStatePacket;
import com.qwarwin.fpvdrones.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientPacketHandler {

    public static void session(SessionStatePacket m) {
        Minecraft mc = Minecraft.getInstance();
        switch (m.type) {
            case SessionStatePacket.START:
                start(mc, m);
                break;
            case SessionStatePacket.STOP: {
                boolean wasActive = ClientSession.active;
                ClientSession.active = false;
                ClientSession.entityId = -1;
                ClientSession.exitSent = false;
                ClientSession.desyncTicks = 0;
                ClientSession.zoom = 1F;
                ControlState.reset();
                if (mc.player != null) {
                    mc.setCameraEntity(mc.player);
                }
                if (wasActive && !m.reason.isEmpty()) {
                    ClientSession.lostTicks = 35;
                    ClientSession.lostReason = m.reason;
                    mc.getSoundManager().play(SimpleSound.forUI(ModSounds.STATIC.get(), 1.0F, 0.8F));
                }
                break;
            }
            default:
                ClientSession.signal = m.signal;
                break;
        }
    }

    private static void start(Minecraft mc, SessionStatePacket m) {
        ClientSession.active = true;
        ClientSession.exitSent = false;
        ClientSession.desyncTicks = 0;
        ClientSession.entityId = m.entityId;
        ClientSession.kind = m.kind;
        ClientSession.handheld = m.handheld;
        ClientSession.homeX = m.homeX;
        ClientSession.homeY = m.homeY;
        ClientSession.homeZ = m.homeZ;
        ClientSession.targetX = m.targetX;
        ClientSession.targetY = m.targetY;
        ClientSession.targetZ = m.targetZ;
        ClientSession.signal = 1F;
        ClientSession.ticks = 0;
        ClientSession.zoom = 1F;
        ClientSession.lostTicks = 0;
        ControlState.reset();
        Entity e = mc.level == null ? null : mc.level.getEntity(m.entityId);
        if (m.kind != DroneKind.STRIKE) {
            ControlState.controlledId = m.entityId;
            if (e instanceof AbstractDroneEntity) {
                ((AbstractDroneEntity) e).snapToServerPosition();
            }
        }
        if (e != null && mc.player != null) {
            mc.player.yRot = e.yRot;
            mc.player.xRot = m.kind == DroneKind.MAVIC ? 20F : 0F;
            ControlState.yaw = mc.player.yRot;
            ControlState.pitch = mc.player.xRot;
            mc.setCameraEntity(e);
        }
    }

    public static void correction(DroneCorrectionPacket m) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(m.id);
        if (e instanceof AbstractDroneEntity) {
            ((AbstractDroneEntity) e).applyCorrection(m.x, m.y, m.z);
        }
    }

    public static void radarPicture(com.qwarwin.fpvdrones.network.RadarPicturePacket m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof com.qwarwin.fpvdrones.client.screen.RadarScreen
                && ((com.qwarwin.fpvdrones.client.screen.RadarScreen) mc.screen).console() == m.console) {
            ((com.qwarwin.fpvdrones.client.screen.RadarScreen) mc.screen).update(m);
        } else if (m.open) {
            mc.setScreen(new com.qwarwin.fpvdrones.client.screen.RadarScreen(m));
        }
    }

    public static void openLauncher(OpenLauncherPacket m) {
        Minecraft.getInstance().setScreen(new LauncherScreen(m));
    }

    private ClientPacketHandler() {
    }
}
