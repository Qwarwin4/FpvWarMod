package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.launch.LaunchConfig;
import com.qwarwin.fpvdrones.launch.LaunchSite;
import com.qwarwin.fpvdrones.launch.Launches;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

public class LauncherUpdatePacket {
    public static final int SAVE = 0, LAUNCH = 1, SALVO = 2, UNLOAD = 3;

    private final SiteRef site;
    private final LaunchConfig config;
    private final int action;

    public LauncherUpdatePacket(SiteRef site, LaunchConfig config, int action) {
        this.site = site;
        this.config = config;
        this.action = action;
    }

    public static void encode(LauncherUpdatePacket m, PacketBuffer buf) {
        m.site.write(buf);
        m.config.write(buf);
        buf.writeByte(m.action);
    }

    public static LauncherUpdatePacket decode(PacketBuffer buf) {
        return new LauncherUpdatePacket(SiteRef.read(buf), LaunchConfig.read(buf), buf.readByte());
    }

    public static void handle(LauncherUpdatePacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity p = ctx.get().getSender();
            LaunchSite site = p == null ? null : m.site.resolve(p);
            if (site == null) {
                return;
            }
            site.getLaunchConfig().copyFrom(m.config);
            site.onSiteChanged();
            switch (m.action) {
                case LAUNCH: {
                    Launches.Result r = Launches.fireNext(site, p.getUUID());
                    p.displayClientMessage(new TranslationTextComponent(r.key), true);
                    break;
                }
                case SALVO: {
                    int n = site.countLoaded();
                    Launches.Result r = site.fireAll(p.getUUID());
                    p.displayClientMessage(r == Launches.Result.OK
                            ? new TranslationTextComponent("msg.fpvdrones.salvo_ok", n)
                            : new TranslationTextComponent(r.key), true);
                    break;
                }
                case UNLOAD:
                    Launches.unload(p, site);
                    break;
                default:
                    p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.saved"), true);
                    break;
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
