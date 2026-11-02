package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.entity.InterceptorEntity;
import com.qwarwin.fpvdrones.entity.RadarConsoleEntity;
import com.qwarwin.fpvdrones.util.AirPicture;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.function.Supplier;

/** From the radar console's screen: refresh the picture, or send an interceptor after a contact. */
public class RadarCommandPacket {
    public static final int REFRESH = 0, INTERCEPT = 1;
    public final int console, action, target;

    public RadarCommandPacket(int console, int action, int target) {
        this.console = console;
        this.action = action;
        this.target = target;
    }

    public static void encode(RadarCommandPacket m, PacketBuffer buf) {
        buf.writeVarInt(m.console);
        buf.writeByte(m.action);
        buf.writeVarInt(m.target + 1);
    }

    public static RadarCommandPacket decode(PacketBuffer buf) {
        return new RadarCommandPacket(buf.readVarInt(), buf.readByte(), buf.readVarInt() - 1);
    }

    public static void handle(RadarCommandPacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayerEntity p = ctx.get().getSender();
            if (p == null) {
                return;
            }
            Entity e = p.level.getEntity(m.console);
            if (!(e instanceof RadarConsoleEntity) || !e.isAlive() || ((RadarConsoleEntity) e).isWrecked()
                    || e.distanceToSqr(p) > 10 * 10) {
                return;
            }
            RadarConsoleEntity c = (RadarConsoleEntity) e;
            if (m.action == INTERCEPT) {
                intercept(p, c, m.target);
            }
            Network.sendTo(p, RadarPicturePacket.of(c, p, false));
        });
        ctx.get().setPacketHandled(true);
    }

    private static void intercept(ServerPlayerEntity p, RadarConsoleEntity c, int targetId) {
        Entity t = p.level.getEntity(targetId);
        boolean onScope = t != null && AirPicture.picture(p.level, p.getUUID(), c.position(), Config.RADAR_LINK.get())
                .stream().anyMatch(k -> k.id == targetId);
        if (!onScope || !t.isAlive()) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.intercept_no_target").withStyle(TextFormatting.GRAY), true);
            return;
        }
        InterceptorEntity i = InterceptorEntity.nearestReady(p.level, p.getUUID(), t.position(), Config.INTERCEPTOR_REACH.get());
        if (i == null || !i.dispatch(t)) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.intercept_none").withStyle(TextFormatting.RED), true);
            return;
        }
        p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.intercept_sent",
                (int) i.position().distanceTo(t.position())).withStyle(TextFormatting.GREEN), true);
    }
}
