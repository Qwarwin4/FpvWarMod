package com.qwarwin.fpvdrones.client;

import com.qwarwin.fpvdrones.entity.DroneKind;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientSession {
    public static boolean active;
    public static int entityId = -1;
    public static DroneKind kind = DroneKind.FPV;
    public static double homeX, homeY, homeZ;
    public static double targetX, targetY, targetZ;
    public static float signal = 1F;
    public static int ticks;
    public static float zoom = 1F;
    // watching through the DJI RC screen instead of goggles
    public static boolean handheld;
    // block under the crosshair, and the last one seen from a Mavic (goes into the launcher menu)
    public static BlockPos aim;
    public static BlockPos lastAim;

    // "no signal" screen after the link drops
    public static int lostTicks;
    // ticks the client has thought it's watching while the game says we're not a spectator any more
    public static int desyncTicks;
    public static boolean exitSent;
    public static String lostReason = "";

    public static void reset() {
        active = false;
        desyncTicks = 0;
        exitSent = false;
        entityId = -1;
        signal = 1F;
        ticks = 0;
        zoom = 1F;
        aim = null;
        lostTicks = 0;
        lostReason = "";
    }

    private ClientSession() {
    }
}
