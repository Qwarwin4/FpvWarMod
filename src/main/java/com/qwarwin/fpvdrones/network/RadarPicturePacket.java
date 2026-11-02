package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.client.ClientPacketHandler;
import com.qwarwin.fpvdrones.entity.InterceptorEntity;
import com.qwarwin.fpvdrones.entity.RadarConsoleEntity;
import com.qwarwin.fpvdrones.util.AirPicture;
import com.qwarwin.fpvdrones.util.Targeting;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** The air picture at a radar console, for its screen. */
public class RadarPicturePacket {
    public static final class Blip {
        public final int id, kind;
        public final float x, y, z, vx, vy, vz;
        public final boolean friendly;

        public Blip(int id, int kind, float x, float y, float z, float vx, float vy, float vz, boolean friendly) {
            this.id = id;
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.friendly = friendly;
        }
    }

    public static final class Own {
        public final int id, state, target, battery;
        public final float x, y, z;

        public Own(int id, int state, int target, int battery, float x, float y, float z) {
            this.id = id;
            this.state = state;
            this.target = target;
            this.battery = battery;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public final int console;
    public final boolean open;
    public final float cx, cy, cz;
    public final int radars;
    public final List<Blip> blips;
    public final List<Own> own;

    public RadarPicturePacket(int console, boolean open, float cx, float cy, float cz, int radars, List<Blip> blips, List<Own> own) {
        this.console = console;
        this.open = open;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.radars = radars;
        this.blips = blips;
        this.own = own;
    }

    public static RadarPicturePacket of(RadarConsoleEntity c, PlayerEntity viewer, boolean open) {
        Vector3d at = c.position();
        double link = Config.RADAR_LINK.get();
        List<Blip> blips = new ArrayList<>();
        for (AirPicture.Contact k : AirPicture.picture(c.level, viewer.getUUID(), at, link)) {
            if (blips.size() >= 200) {
                break;
            }
            blips.add(new Blip(k.id, k.kind, (float) k.pos.x, (float) k.pos.y, (float) k.pos.z,
                    (float) k.vel.x, (float) k.vel.y, (float) k.vel.z, Targeting.isFriendly(c.level, viewer.getUUID(), k.owner)));
        }
        List<Own> own = new ArrayList<>();
        for (InterceptorEntity i : InterceptorEntity.own(c.level, viewer.getUUID(), at, Config.INTERCEPTOR_REACH.get())) {
            if (own.size() >= 64) {
                break;
            }
            own.add(new Own(i.getId(), i.getState(), i.getTargetId(), i.batterySeconds(),
                    (float) i.getX(), (float) i.getY(), (float) i.getZ()));
        }
        return new RadarPicturePacket(c.getId(), open, (float) at.x, (float) at.y, (float) at.z,
                com.qwarwin.fpvdrones.util.AirPicture.radarsNear(c.level, viewer.getUUID(), at, link), blips, own);
    }

    public static void encode(RadarPicturePacket m, PacketBuffer buf) {
        buf.writeVarInt(m.console);
        buf.writeBoolean(m.open);
        buf.writeFloat(m.cx);
        buf.writeFloat(m.cy);
        buf.writeFloat(m.cz);
        buf.writeVarInt(m.radars);
        buf.writeVarInt(m.blips.size());
        for (Blip b : m.blips) {
            buf.writeVarInt(b.id);
            buf.writeByte(b.kind);
            buf.writeFloat(b.x);
            buf.writeFloat(b.y);
            buf.writeFloat(b.z);
            buf.writeFloat(b.vx);
            buf.writeFloat(b.vy);
            buf.writeFloat(b.vz);
            buf.writeBoolean(b.friendly);
        }
        buf.writeVarInt(m.own.size());
        for (Own o : m.own) {
            buf.writeVarInt(o.id);
            buf.writeByte(o.state);
            buf.writeVarInt(o.target + 1);
            buf.writeVarInt(o.battery);
            buf.writeFloat(o.x);
            buf.writeFloat(o.y);
            buf.writeFloat(o.z);
        }
    }

    public static RadarPicturePacket decode(PacketBuffer buf) {
        int console = buf.readVarInt();
        boolean open = buf.readBoolean();
        float cx = buf.readFloat(), cy = buf.readFloat(), cz = buf.readFloat();
        int radars = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), 256);
        List<Blip> blips = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            blips.add(new Blip(buf.readVarInt(), buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readBoolean()));
        }
        int k = Math.min(buf.readVarInt(), 64);
        List<Own> own = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            own.add(new Own(buf.readVarInt(), buf.readByte(), buf.readVarInt() - 1, buf.readVarInt(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat()));
        }
        return new RadarPicturePacket(console, open, cx, cy, cz, radars, blips, own);
    }

    public static void handle(RadarPicturePacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.radarPicture(m)));
        ctx.get().setPacketHandled(true);
    }
}
