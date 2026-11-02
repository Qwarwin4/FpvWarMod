package com.qwarwin.fpvdrones.util;

import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.DroneKind;
import com.qwarwin.fpvdrones.entity.InterceptorEntity;
import com.qwarwin.fpvdrones.entity.MissileEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.entity.StrikeType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the radars see: each radar sweeps once a second and posts its contacts here; a console puts together
 * the picture from every radar of its side within link range. Server side only, nothing saved: after a restart
 * the radars just sweep again.
 */
public final class AirPicture {
    public static final int SHAHED = 0, JET = 1, FPV = 2, MAVIC = 3, MISSILE = 4, INTERCEPTOR = 5, AIRCRAFT = 6, OTHER = 7;
    /** A contact not refreshed for this long is dropped. */
    private static final long STALE = 50;

    public static final class Contact {
        public final int id;
        public final UUID uuid;
        public final Vector3d pos;
        public final Vector3d vel;
        public final int kind;
        @Nullable
        public final UUID owner;
        public final long seen;

        Contact(Entity e, int kind, long seen) {
            this.id = e.getId();
            this.uuid = e.getUUID();
            this.pos = e.position();
            this.vel = e.position().subtract(e.xo, e.yo, e.zo);
            this.kind = kind;
            this.owner = Targeting.ownerOf(e);
            this.seen = seen;
        }
    }

    private static final class Radar {
        final World level;
        final Vector3d pos;
        @Nullable
        final UUID owner;
        final long time;
        final List<Contact> contacts;

        Radar(World level, Vector3d pos, @Nullable UUID owner, long time, List<Contact> contacts) {
            this.level = level;
            this.pos = pos;
            this.owner = owner;
            this.time = time;
            this.contacts = contacts;
        }
    }

    private static final Map<UUID, Radar> RADARS = new HashMap<>();

    /** One sweep of the radar {@code id} standing at {@code pos}, out to {@code range}. */
    public static synchronized void sweep(ServerWorld level, UUID id, Vector3d pos, @Nullable UUID owner, double range) {
        long now = level.getGameTime();
        List<Contact> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            int kind = kindOf(e);
            if (kind < 0 || !e.isAlive()) {
                continue;
            }
            double r = reach(level, e, range);
            if (e.position().distanceToSqr(pos) > r * r || level.random.nextDouble() > Targeting.scanChance(e)) {
                continue;
            }
            out.add(new Contact(e, kind, now));
        }
        RADARS.put(id, new Radar(level, pos, owner, now, out));
    }

    public static synchronized void off(UUID id) {
        RADARS.remove(id);
    }

    /**
     * How far it shows: a Shahed-size target to the full range, a quadcopter at 230 blocks, anything flying lower
     * than 10 blocks over the ground lost in the clutter past 300.
     */
    private static double reach(World level, Entity e, double range) {
        double r = Targeting.detectRange(level, e, range);
        if (e instanceof InterceptorEntity) {
            r = Math.min(r, 400);
        }
        int ground = level.getHeight(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(e.getX()), MathHelper.floor(e.getZ()));
        return e.getY() - ground < 10 ? Math.min(r, 300) : r;
    }

    /** -1 for what isn't an air target at all. */
    public static int kindOf(Entity e) {
        if (e instanceof StrikeDroneEntity) {
            StrikeType t = ((StrikeDroneEntity) e).getStrikeType();
            return t.isJet() ? JET : SHAHED;
        }
        if (e instanceof AbstractDroneEntity) {
            AbstractDroneEntity d = (AbstractDroneEntity) e;
            if (!d.isPowered()) {
                return -1;
            }
            return d.kind() == DroneKind.MAVIC ? MAVIC : FPV;
        }
        if (e instanceof MissileEntity) {
            return MISSILE;
        }
        if (e instanceof InterceptorEntity) {
            return ((InterceptorEntity) e).isFlying() ? INTERCEPTOR : -1;
        }
        if (Targeting.isAirTarget(e)) {
            return AIRCRAFT;
        }
        if (e instanceof LivingEntity && ((LivingEntity) e).isFallFlying()) {
            return OTHER;
        }
        return -1;
    }

    /** Radars of {@code viewer}'s side within {@code link} of {@code at}. */
    public static synchronized int radarsNear(World level, @Nullable UUID viewer, Vector3d at, double link) {
        int n = 0;
        for (Radar r : RADARS.values()) {
            if (r.level == level && r.pos.distanceToSqr(at) < link * link && Targeting.isFriendly(level, viewer, r.owner)) {
                n++;
            }
        }
        return n;
    }

    /** The merged picture from {@code viewer}'s side's radars within {@code link} of {@code at}. */
    public static synchronized List<Contact> picture(World level, @Nullable UUID viewer, Vector3d at, double link) {
        long now = level.getGameTime();
        Map<Integer, Contact> merged = new HashMap<>();
        Iterator<Radar> it = RADARS.values().iterator();
        while (it.hasNext()) {
            Radar r = it.next();
            if (now - r.time > STALE * 4 && r.level == level) {
                // a radar that stopped sweeping without saying so (unloaded with its chunk)
                it.remove();
                continue;
            }
            if (r.level != level || r.pos.distanceToSqr(at) > link * link || !Targeting.isFriendly(level, viewer, r.owner)) {
                continue;
            }
            for (Contact c : r.contacts) {
                if (now - c.seen > STALE) {
                    continue;
                }
                Contact old = merged.get(c.id);
                if (old == null || old.seen < c.seen) {
                    merged.put(c.id, c);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private AirPicture() {
    }
}
