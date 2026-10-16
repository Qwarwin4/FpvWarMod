package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.client.ClientPacketHandler;
import com.qwarwin.fpvdrones.entity.StrikeType;
import com.qwarwin.fpvdrones.launch.LaunchConfig;
import com.qwarwin.fpvdrones.launch.LaunchSite;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class OpenLauncherPacket {
    public final SiteRef site;
    public final LaunchConfig config;
    public final BlockPos origin;
    // StrikeType ordinal per slot, -1 for empty
    public final int[] slots;

    public OpenLauncherPacket(LaunchSite site) {
        this.site = SiteRef.of(site);
        this.config = site.getLaunchConfig();
        Vector3d o = site.getLaunchPos(0);
        this.origin = new BlockPos(o.x, o.y, o.z);
        this.slots = new int[site.getCapacity()];
        for (int i = 0; i < slots.length; i++) {
            StrikeType t = site.getLoaded(i);
            slots[i] = t == null ? -1 : t.ordinal();
        }
    }

    private OpenLauncherPacket(SiteRef site, LaunchConfig config, BlockPos origin, int[] slots) {
        this.site = site;
        this.config = config;
        this.origin = origin;
        this.slots = slots;
    }

    public static void encode(OpenLauncherPacket m, PacketBuffer buf) {
        m.site.write(buf);
        m.config.write(buf);
        buf.writeBlockPos(m.origin);
        buf.writeVarIntArray(m.slots);
    }

    public static OpenLauncherPacket decode(PacketBuffer buf) {
        return new OpenLauncherPacket(SiteRef.read(buf), LaunchConfig.read(buf), buf.readBlockPos(), buf.readVarIntArray(8));
    }

    public static void handle(OpenLauncherPacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.openLauncher(m)));
        ctx.get().setPacketHandled(true);
    }
}
