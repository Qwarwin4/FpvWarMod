package com.qwarwin.fpvdrones.client;

import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.DroneKind;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.entity.MissileEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.entity.TerminatorEntity;
import com.qwarwin.fpvdrones.item.JavelinItem;
import com.qwarwin.fpvdrones.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
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

    public static void onTerminatorSeen(TerminatorEntity bmpt) {
        Minecraft.getInstance().getSoundManager().play(new EntityLoopSound(bmpt, ModSounds.TRACKS.get(), 1.6F));
    }

    public static void onMissileSeen(MissileEntity missile) {
        Minecraft.getInstance().getSoundManager().play(new EntityLoopSound(missile, ModSounds.MISSILE_MOTOR.get(), 3.0F));
    }

    /** Seeker growl while acquiring, steady high tone once locked. */
    public static void javelinTone(JavelinItem.Lock lock) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        int t = mc.player.tickCount;
        if (lock.locked()) {
            if (t % 4 == 0) {
                mc.getSoundManager().play(SimpleSound.forUI(ModSounds.LOCK_TONE.get(), 1F));
            }
        } else if (lock.targetId >= 0 && t % 8 == 0) {
            mc.getSoundManager().play(SimpleSound.forUI(ModSounds.SEEKER_TONE.get(), 0.8F + lock.ticks / (float) JavelinItem.LOCK_TICKS * 0.4F));
        }
    }

    private ClientHooks() {
    }
}
