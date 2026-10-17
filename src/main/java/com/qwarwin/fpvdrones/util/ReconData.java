package com.qwarwin.fpvdrones.util;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/** Last point the player marked from a Mavic (RTH or a dropped beacon). Lives in player persistent NBT. */
public final class ReconData {
    private static final String KEY = "fpvdrones_recon";

    public static void set(PlayerEntity player, BlockPos pos, int beacon) {
        CompoundNBT tag = new CompoundNBT();
        tag.putLong("Pos", pos.asLong());
        tag.putInt("Beacon", beacon);
        player.getPersistentData().put(KEY, tag);
    }

    @Nullable
    public static BlockPos getPos(PlayerEntity player) {
        CompoundNBT data = player.getPersistentData();
        return data.contains(KEY) ? BlockPos.of(data.getCompound(KEY).getLong("Pos")) : null;
    }

    public static int getBeacon(PlayerEntity player) {
        CompoundNBT data = player.getPersistentData();
        return data.contains(KEY) ? data.getCompound(KEY).getInt("Beacon") : 0;
    }

    private ReconData() {
    }
}
