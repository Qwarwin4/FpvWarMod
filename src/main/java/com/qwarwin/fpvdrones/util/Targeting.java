package com.qwarwin.fpvdrones.util;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.MachineEntity;
import com.qwarwin.fpvdrones.entity.MissileEntity;
import com.qwarwin.fpvdrones.entity.MountedWeaponEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FlyingEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.monster.BlazeEntity;
import net.minecraft.entity.monster.VexEntity;
import net.minecraft.entity.passive.BatEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.scoreboard.Team;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.ITag;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Hit tests, friend-or-foe and lead calculation shared by the guns and missiles. */
public final class Targeting {
    /**
     * Other mods' aircraft for the gun's proximity fuse and "everything airborne" mode. Ships with Military Tech's
     * planes as optional entries, so nothing breaks without it.
     */
    public static final ITag.INamedTag<EntityType<?>> AIR_TARGETS = EntityTypeTags.createOptional(FpvDrones.id("air_targets"));
    /** Other mods' vehicles that guns, ATGMs and payloads should hit even though they aren't living things. */
    public static final ITag.INamedTag<EntityType<?>> SHOOTABLE = EntityTypeTags.createOptional(FpvDrones.id("shootable"));

    public static final class Hit {
        public final Entity entity;
        public final Vector3d pos;

        Hit(Entity entity, Vector3d pos) {
            this.entity = entity;
            this.pos = pos;
        }
    }

    /** Closest entity the segment passes through. */
    @Nullable
    public static Hit firstEntity(World level, Entity self, Vector3d from, Vector3d to, double grow, Predicate<Entity> filter) {
        AxisAlignedBB sweep = new AxisAlignedBB(from, to).inflate(grow + 1.0);
        Hit best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : level.getEntities(self, sweep, e -> e.isAlive() && !e.isSpectator() && filter.test(e))) {
            Optional<Vector3d> p = e.getBoundingBox().inflate(grow).clip(from, to);
            if (p.isPresent()) {
                double d = from.distanceToSqr(p.get());
                if (d < bestD) {
                    bestD = d;
                    best = new Hit(e, p.get());
                }
            }
        }
        return best;
    }

    /** Things worth shooting a gun or missile at. */
    public static boolean isShootable(Entity e) {
        return e instanceof LivingEntity || e instanceof MachineEntity || isAirTarget(e) || e.getType().is(SHOOTABLE);
    }

    public static boolean isAirTarget(Entity e) {
        return e instanceof StrikeDroneEntity || e instanceof AbstractDroneEntity || e instanceof MissileEntity
                || e.getType().is(AIR_TARGETS) && !e.isOnGround();
    }

    /** Everything up in the air: drones, missiles, flying mobs, players on elytra. */
    public static boolean isAirborne(Entity e) {
        if (isAirTarget(e)) {
            return !(e instanceof AbstractDroneEntity) || ((AbstractDroneEntity) e).isPowered();
        }
        if (e instanceof FlyingEntity || e instanceof BlazeEntity || e instanceof WitherEntity || e instanceof VexEntity) {
            return true;
        }
        if (e instanceof LivingEntity && ((LivingEntity) e).isFallFlying()) {
            return true;
        }
        return e instanceof BatEntity || e instanceof ParrotEntity && !e.isOnGround() || e instanceof BeeEntity;
    }

    /** The player responsible for a drone / machine, if any. */
    @Nullable
    public static UUID ownerOf(Entity e) {
        if (e instanceof AbstractDroneEntity) {
            return ((AbstractDroneEntity) e).getOperatorId();
        }
        if (e instanceof StrikeDroneEntity) {
            return ((StrikeDroneEntity) e).getOwner();
        }
        if (e instanceof MountedWeaponEntity) {
            return ((MountedWeaponEntity) e).getOwner();
        }
        if (e instanceof PlayerEntity) {
            return e.getUUID();
        }
        return null;
    }

    /** Same player or same scoreboard team. Unknown owners count as hostile. */
    public static boolean isFriendly(World level, @Nullable UUID a, @Nullable UUID b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        PlayerEntity pa = level.getPlayerByUUID(a);
        PlayerEntity pb = level.getPlayerByUUID(b);
        if (pa == null || pb == null) {
            return false;
        }
        Team ta = pa.getTeam();
        return ta != null && ta.isAlliedTo(pb.getTeam());
    }

    public static boolean canSee(World level, Vector3d from, Entity target) {
        Vector3d to = target.getBoundingBox().getCenter();
        return level.clip(new RayTraceContext(from, to, RayTraceContext.BlockMode.COLLIDER,
                RayTraceContext.FluidMode.NONE, target)).getType() == RayTraceResult.Type.MISS;
    }

    /** Where to point so a shell at {@code speed} blocks/tick meets the target, gravity included. */
    public static Vector3d lead(Vector3d muzzle, Entity target, double speed, double gravity) {
        Vector3d pos = target.getBoundingBox().getCenter();
        Vector3d vel = target.position().subtract(target.xo, target.yo, target.zo);
        Vector3d aim = pos;
        for (int i = 0; i < 4; i++) {
            double t = aim.distanceTo(muzzle) / speed;
            aim = pos.add(vel.scale(t)).add(0, 0.5 * gravity * t * t, 0);
        }
        return aim;
    }

    public static float yawTo(Vector3d d) {
        return (float) (Math.atan2(d.z, d.x) * (180.0 / Math.PI)) - 90F;
    }

    public static float pitchTo(Vector3d d) {
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        return (float) (-Math.atan2(d.y, h) * (180.0 / Math.PI));
    }

    private Targeting() {
    }
}
