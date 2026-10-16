package com.qwarwin.fpvdrones.launch;

import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;

public class LaunchConfig {
    // stored in tenths of MHz
    public static final int DEFAULT_FREQ = 4330;

    private boolean hasTarget;
    private int x, y, z;
    private boolean autoY = true;
    private int frequency = DEFAULT_FREQ;

    public boolean hasTarget() {
        return hasTarget;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public boolean isAutoY() {
        return autoY;
    }

    public int getFrequency() {
        return frequency;
    }

    public void set(boolean hasTarget, int x, int y, int z, boolean autoY, int frequency) {
        this.hasTarget = hasTarget;
        this.x = x;
        this.y = Math.max(-64, Math.min(320, y));
        this.z = z;
        this.autoY = autoY;
        this.frequency = Math.max(10, Math.min(99999, frequency));
    }

    public void copyFrom(LaunchConfig o) {
        set(o.hasTarget, o.x, o.y, o.z, o.autoY, o.frequency);
    }

    public void load(CompoundNBT tag) {
        hasTarget = tag.getBoolean("HasTarget");
        x = tag.getInt("TX");
        y = tag.getInt("TY");
        z = tag.getInt("TZ");
        autoY = !tag.contains("AutoY") || tag.getBoolean("AutoY");
        frequency = tag.contains("Freq") ? tag.getInt("Freq") : DEFAULT_FREQ;
    }

    public CompoundNBT save(CompoundNBT tag) {
        tag.putBoolean("HasTarget", hasTarget);
        tag.putInt("TX", x);
        tag.putInt("TY", y);
        tag.putInt("TZ", z);
        tag.putBoolean("AutoY", autoY);
        tag.putInt("Freq", frequency);
        return tag;
    }

    public void write(PacketBuffer buf) {
        buf.writeBoolean(hasTarget);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(autoY);
        buf.writeVarInt(frequency);
    }

    public static LaunchConfig read(PacketBuffer buf) {
        LaunchConfig c = new LaunchConfig();
        c.hasTarget = buf.readBoolean();
        c.x = buf.readInt();
        c.y = buf.readInt();
        c.z = buf.readInt();
        c.autoY = buf.readBoolean();
        c.frequency = buf.readVarInt();
        return c;
    }

    public static String formatFreq(int tenths) {
        return (tenths / 10) + "." + (tenths % 10);
    }
}
