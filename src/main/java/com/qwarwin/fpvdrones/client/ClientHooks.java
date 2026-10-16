package com.qwarwin.fpvdrones.client;

import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.DroneKind;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

// entry points from common entity code, always called through DistExecutor
@OnlyIn(Dist.CLIENT)
public final class ClientHooks {

    public static void onDroneSeen(AbstractDroneEntity drone) {
        boolean mavic = drone.kind() == DroneKind.MAVIC;
        Minecraft.getInstance().getSoundManager().play(new EntityLoopSound(drone,
                mavic ? ModSounds.MAVIC_MOTOR.get() : ModSounds.FPV_MOTOR.get(), mavic ? 1.0F : 1.6F));
    }

    public static void onStrikeDroneSeen(StrikeDroneEntity drone) {
        boolean jet = drone.getStrikeType().isJet();
        Minecraft.getInstance().getSoundManager().play(new EntityLoopSound(drone,
                jet ? ModSounds.JET_ENGINE.get() : ModSounds.SHAHED_ENGINE.get(), jet ? 6.0F : 5.0F));
    }

    public static void onTruckSeen(LaunchTruckEntity truck) {
        Minecraft.getInstance().getSoundManager().play(new EntityLoopSound(truck, ModSounds.TRUCK_ENGINE.get(), 1.4F));
    }

    private ClientHooks() {
    }
}
