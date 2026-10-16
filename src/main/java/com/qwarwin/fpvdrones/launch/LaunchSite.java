package com.qwarwin.fpvdrones.launch;

import com.qwarwin.fpvdrones.entity.StrikeType;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.UUID;

/** Something strike drones can be launched from: the launcher block or the truck. */
public interface LaunchSite {
    LaunchConfig getLaunchConfig();

    int getCapacity();

    @Nullable
    StrikeType getLoaded(int slot);

    void setLoaded(int slot, @Nullable StrikeType type);

    /** Centre of the drone sitting in the slot. */
    Vector3d getLaunchPos(int slot);

    float getLaunchYaw();

    float getLaunchPitch();

    World getSiteLevel();

    @Nullable
    UUID getLastLaunched();

    void setLastLaunched(UUID id);

    void onSiteChanged();

    ITextComponent getSiteName();

    /** Tag stored in remotes/tablets to find this site again. */
    CompoundNBT createLink();

    /** Truck fires them one by one, the block just fires the single one. */
    Launches.Result fireAll(@Nullable UUID owner);

    default int countLoaded() {
        int n = 0;
        for (int i = 0; i < getCapacity(); i++) {
            if (getLoaded(i) != null) {
                n++;
            }
        }
        return n;
    }

    default int firstLoaded() {
        for (int i = 0; i < getCapacity(); i++) {
            if (getLoaded(i) != null) {
                return i;
            }
        }
        return -1;
    }

    default int firstFree() {
        for (int i = 0; i < getCapacity(); i++) {
            if (getLoaded(i) == null) {
                return i;
            }
        }
        return -1;
    }
}
