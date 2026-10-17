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

import javax.annotation.Nullable;
import java.util.function.Supplier;

public class OpenLauncherPacket {
    public final SiteRef site;
    public final LaunchConfig config;
    public final BlockPos origin;
    // StrikeType ordinal per slot, -1 for empty
    public final int[] slots;
    // last point marked from the player's Mavic, if any
    @Nullable
    public final BlockPos recon;
    public final int reconBeacon;

    public OpenLauncherPacket(LaunchSite site, @Nullable BlockPos recon, int reconBeacon) {
        this.recon = recon;
        this.reconBeacon = reconBeacon;
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

    private OpenLauncherPacket(SiteRef site, LaunchConfig config, BlockPos origin, int[] slots,
                               @Nullable BlockPos recon, int reconBeacon) {
        this.recon = recon;
        this.reconBeacon = reconBeacon;
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
        buf.writeBoolean(m.recon != null);
        if (m.recon != null) {
            buf.writeBlockPos(m.recon);
        }
        buf.writeVarInt(m.reconBeacon);
    }

    public static OpenLauncherPacket decode(PacketBuffer buf) {
        SiteRef site = SiteRef.read(buf);
        LaunchConfig config = LaunchConfig.read(buf);
        BlockPos origin = buf.readBlockPos();
        int[] slots = buf.readVarIntArray(8);
        BlockPos recon = buf.readBoolean() ? buf.readBlockPos() : null;
        return new OpenLauncherPacket(site, config, origin, slots, recon, buf.readVarInt());
    }

    public static void handle(OpenLauncherPacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.openLauncher(m)));
        ctx.get().setPacketHandled(true);
    }
}
