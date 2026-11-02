package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.client.ClientHooks;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.util.ChunkTickets;
import com.qwarwin.fpvdrones.util.Explosions;
import com.qwarwin.fpvdrones.util.Targeting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Interceptor drone in the manner of the P1-SUN: a bullet-shaped quad that sits on the ground until it's sent
 * after something, then climbs, runs it down at full speed leading the target and bursts next to it. Sent from
 * the interceptor remote (whatever the operator has his sight on) or from the radar console (a radar contact).
 * Once up it lives on its battery: a target that's gone leaves it hanging there, waiting for a new one.
 */
public class InterceptorEntity extends Entity {
    public static final int PARKED = 0, CLIMB = 1, CHASE = 2, LOITER = 3;
    private static final DataParameter<Integer> STATE = EntityDataManager.defineId(InterceptorEntity.class, DataSerializers.INT);
    private static final DataParameter<Integer> TARGET_ID = EntityDataManager.defineId(InterceptorEntity.class, DataSerializers.INT);
    private static final double FUSE = 3.0;
    private static final float TURN = 11F;

    /** Every interceptor in a loaded chunk, for sending the nearest one. */
    private static final Set<InterceptorEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    @Nullable
    private UUID owner;
    @Nullable
    private UUID target;
    @Nullable
    private Vector3d lastSeen;
    private int battery;
    private int climb;
    private double speed;
    private float health = 4F;

    public float propAngle, propAngleO;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, lerpPitch;
    private boolean soundStarted;

    public InterceptorEntity(EntityType<? extends InterceptorEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(STATE, PARKED);
        entityData.define(TARGET_ID, -1);
    }

    public int getState() {
        return entityData.get(STATE);
    }

    private void setState(int s) {
        entityData.set(STATE, s);
    }

    public boolean isFlying() {
        return getState() != PARKED;
    }

    /** Parked, or up and waiting for a new target. */
    public boolean isReady() {
        return isAlive() && (getState() == PARKED || getState() == LOITER);
    }

    public int getTargetId() {
        return entityData.get(TARGET_ID);
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
    }

    /** Seconds of battery left once in the air. */
    public int batterySeconds() {
        return battery / 20;
    }

    // ------------------------------------------------------------------ sending one

    /** Off after {@code t}. False when it's already busy. */
    public boolean dispatch(Entity t) {
        if (!isReady() || level.isClientSide) {
            return false;
        }
        target = t.getUUID();
        lastSeen = t.getBoundingBox().getCenter();
        entityData.set(TARGET_ID, t.getId());
        if (getState() == PARKED) {
            battery = Config.INTERCEPTOR_BATTERY.get() * 20;
            climb = 0;
            setState(CLIMB);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.BEE_LOOP_AGGRESSIVE, SoundCategory.NEUTRAL, 1F, 2F);
        } else {
            setState(CHASE);
        }
        return true;
    }

    /**
     * The ready interceptor of {@code player}'s side nearest to the target, within {@code reach} of it, or null.
     */
    @Nullable
    public static InterceptorEntity nearestReady(World level, @Nullable UUID player, Vector3d to, double reach) {
        InterceptorEntity best = null;
        double bestD = reach * reach;
        synchronized (LOADED) {
            for (InterceptorEntity i : LOADED) {
                if (i.level != level || !i.isReady() || !Targeting.isFriendly(level, player, i.owner)) {
                    continue;
                }
                double d = i.position().distanceToSqr(to);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
        }
        return best;
    }

    /** {@code player}'s side's interceptors in loaded chunks within {@code reach} of a point. */
    public static java.util.List<InterceptorEntity> own(World level, @Nullable UUID player, Vector3d at, double reach) {
        java.util.List<InterceptorEntity> out = new java.util.ArrayList<>();
        synchronized (LOADED) {
            for (InterceptorEntity i : LOADED) {
                if (i.level == level && i.isAlive() && Targeting.isFriendly(level, player, i.owner)
                        && i.position().distanceToSqr(at) < reach * reach) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!level.isClientSide) {
            synchronized (LOADED) {
                LOADED.add(this);
            }
        }
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        synchronized (LOADED) {
            LOADED.remove(this);
        }
    }

    // ------------------------------------------------------------------ flight

    @Override
    public void tick() {
        super.tick();
        propAngleO = propAngle;
        if (level.isClientSide) {
            clientTick();
            return;
        }
        int state = getState();
        if (state == PARKED) {
            Vector3d m = getDeltaMovement();
            setDeltaMovement(0, m.y - 0.08, 0);
            move(MoverType.SELF, getDeltaMovement());
            if (isOnGround()) {
                setDeltaMovement(Vector3d.ZERO);
            }
            return;
        }
        if (--battery <= 0) {
            // flat battery: it comes down where it is and the charge goes off on the ground
            detonate(position(), null);
            return;
        }
        if (tickCount % 5 == 0) {
            ChunkTickets.follow((ServerWorld) level, this, getDeltaMovement(), 30);
        }
        Entity t = target == null ? null : ((ServerWorld) level).getEntity(target);
        if (t != null && t.isAlive() && Targeting.isAirborne(t)) {
            lastSeen = t.getBoundingBox().getCenter();
        } else if (state == CHASE) {
            // the target's down or gone: hang about for a new one
            target = null;
            entityData.set(TARGET_ID, -1);
            setState(LOITER);
            state = LOITER;
        }
        Vector3d v;
        if (state == CLIMB) {
            // straight up off the ground, then it goes
            v = new Vector3d(0, 0.9, 0);
            speed = 0.9;
            if (++climb > 10) {
                setState(target != null ? CHASE : LOITER);
            }
        } else if (state == CHASE && t != null) {
            double max = Config.INTERCEPTOR_SPEED.get();
            speed = Math.min(max, speed + 0.3);
            Vector3d aim = Targeting.lead(position(), t, speed, 0);
            Vector3d dir = getDeltaMovement().lengthSqr() > 1e-4 ? getDeltaMovement().normalize() : aim.subtract(position()).normalize();
            v = turn(dir, aim.subtract(position()).normalize(), TURN).scale(speed);
        } else {
            // loiter: slow down and hold height
            speed *= 0.85;
            Vector3d m = getDeltaMovement().scale(0.85);
            v = new Vector3d(m.x, m.y * 0.5, m.z);
        }
        Vector3d from = position();
        Vector3d to = from.add(v);
        if (t != null && state == CHASE) {
            // proximity fuse: anywhere along this tick's path close enough to the target
            Vector3d c = t.getBoundingBox().getCenter();
            Vector3d closest = closestOnSegment(from, to, c);
            if (closest.distanceTo(c) < FUSE + t.getBbWidth() * 0.5) {
                detonate(closest, t);
                return;
            }
        }
        BlockRayTraceResult block = level.clip(new RayTraceContext(from, to, RayTraceContext.BlockMode.COLLIDER,
                RayTraceContext.FluidMode.NONE, this));
        if (block.getType() != RayTraceResult.Type.MISS && state != LOITER) {
            detonate(block.getLocation(), null);
            return;
        }
        if (block.getType() != RayTraceResult.Type.MISS) {
            v = Vector3d.ZERO;
            to = from;
        }
        setDeltaMovement(v);
        setPos(to.x, to.y, to.z);
        face(v);
    }

    private void face(Vector3d v) {
        yRotO = yRot;
        xRotO = xRot;
        if (v.lengthSqr() > 0.01) {
            yRot = Targeting.yawTo(v);
            xRot = MathHelper.clamp(Targeting.pitchTo(v), -80F, 80F);
        }
    }

    private static Vector3d closestOnSegment(Vector3d a, Vector3d b, Vector3d p) {
        Vector3d ab = b.subtract(a);
        double len = ab.lengthSqr();
        if (len < 1e-9) {
            return a;
        }
        double k = MathHelper.clamp(p.subtract(a).dot(ab) / len, 0, 1);
        return a.add(ab.scale(k));
    }

    private static Vector3d turn(Vector3d cur, Vector3d wanted, float maxDeg) {
        double dot = MathHelper.clamp(cur.dot(wanted), -1.0, 1.0);
        double ang = Math.toDegrees(Math.acos(dot));
        if (ang <= maxDeg || ang < 1e-3) {
            return wanted;
        }
        double k = maxDeg / ang;
        return cur.scale(1 - k).add(wanted.scale(k)).normalize();
    }

    private void detonate(Vector3d pos, @Nullable Entity hit) {
        if (!isAlive()) {
            return;
        }
        remove();
        Explosions.interceptor(level, owner, pos, hit);
    }

    // ------------------------------------------------------------------ client

    private void clientTick() {
        if (!soundStarted) {
            soundStarted = true;
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onInterceptorSeen(this));
        }
        if (isFlying()) {
            propAngle += 95F;
        }
        if (lerpSteps > 0) {
            setPos(getX() + (lerpX - getX()) / lerpSteps, getY() + (lerpY - getY()) / lerpSteps,
                    getZ() + (lerpZ - getZ()) / lerpSteps);
            yRot += MathHelper.wrapDegrees(lerpYaw - yRot) / lerpSteps;
            xRot += (lerpPitch - xRot) / lerpSteps;
            lerpSteps--;
        }
        if (isFlying() && getState() == CHASE && random.nextInt(2) == 0) {
            Vector3d back = position().add(Vector3d.directionFromRotation(xRot, yRot).scale(-0.4));
            level.addParticle(ParticleTypes.SMOKE, back.x, back.y + 0.1, back.z, 0, 0, 0);
        }
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYaw = yRot;
        lerpPitch = xRot;
        lerpSteps = isFlying() ? 2 : 4;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSqr) {
        return distSqr < 256.0 * 256.0;
    }

    // ------------------------------------------------------------------ hits, pick-up

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level.isClientSide || isInvulnerableTo(source) || !isAlive()) {
            return false;
        }
        if (!isFlying() && "player".equals(source.getMsgId()) && source.getDirectEntity() instanceof PlayerEntity) {
            // knocked over on the ground by hand: back into the inventory
            PlayerEntity p = (PlayerEntity) source.getDirectEntity();
            if (!p.abilities.instabuild) {
                spawnAtLocation(new ItemStack(ModItems.INTERCEPTOR.get()));
            }
            remove();
            return true;
        }
        health -= amount;
        if (health <= 0F) {
            detonate(position(), null);
        }
        return true;
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND || isFlying() || !player.getItemInHand(hand).isEmpty()) {
            return ActionResultType.PASS;
        }
        if (!level.isClientSide) {
            if (!player.abilities.instabuild) {
                player.inventory.add(new ItemStack(ModItems.INTERCEPTOR.get()));
            }
            remove();
        }
        return ActionResultType.sidedSuccess(level.isClientSide);
    }

    @Override
    public boolean isPickable() {
        return isAlive();
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        target = tag.hasUUID("Target") ? tag.getUUID("Target") : null;
        battery = tag.getInt("Battery");
        int s = tag.getInt("State");
        setState(s == CLIMB ? CHASE : s);
        if (isFlying() && target == null) {
            setState(LOITER);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (target != null) {
            tag.putUUID("Target", target);
        }
        tag.putInt("Battery", battery);
        tag.putInt("State", getState());
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
