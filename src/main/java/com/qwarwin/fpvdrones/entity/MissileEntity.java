package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.client.ClientHooks;
import com.qwarwin.fpvdrones.util.ChunkTickets;
import com.qwarwin.fpvdrones.util.Explosions;
import com.qwarwin.fpvdrones.util.Targeting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.DamageSource;
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
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/** Anti-tank guided missile in flight. Guidance runs on the server, the client just draws the plume. */
public class MissileEntity extends Entity implements IEntityAdditionalSpawnData {
    private static final DataParameter<Boolean> GUIDED = EntityDataManager.defineId(MissileEntity.class, DataSerializers.BOOLEAN);
    private static final float RAD2DEG = (float) (180.0 / Math.PI);

    private MissileType type = MissileType.KORNET;
    @Nullable
    private UUID owner;
    // SACLOS: whose sight the missile follows; it has to stay at the launcher it fired from
    @Nullable
    private UUID guide;
    private int launcherId = -1;
    // fire-and-forget target
    @Nullable
    private UUID target;
    @Nullable
    private Vector3d lastTarget;
    private double speed;
    private int life;

    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private boolean soundStarted;

    public MissileEntity(EntityType<? extends MissileEntity> type, World world) {
        super(type, world);
        noPhysics = true;
    }

    public void setup(MissileType type, @Nullable UUID owner, @Nullable Entity launcher, Vector3d pos, Vector3d dir) {
        this.type = type;
        this.owner = owner;
        this.guide = type.isBeamRiding() ? owner : null;
        this.launcherId = launcher == null ? -1 : launcher.getId();
        this.speed = type == MissileType.JAVELIN ? 0.7 : 1.2;
        setPos(pos.x, pos.y, pos.z);
        setDeltaMovement(dir.normalize().scale(speed));
        face();
        yRotO = yRot;
        xRotO = xRot;
    }

    public void setTarget(Entity e) {
        target = e.getUUID();
        lastTarget = e.getBoundingBox().getCenter();
    }

    public MissileType getMissileType() {
        return type;
    }

    public boolean isGuided() {
        return entityData.get(GUIDED);
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(GUIDED, true);
    }

    private void face() {
        Vector3d v = getDeltaMovement();
        yRot = Targeting.yawTo(v);
        xRot = Targeting.pitchTo(v);
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide) {
            clientTick();
            return;
        }
        if (++life > type.life()) {
            detonate(position(), null);
            return;
        }
        if (life % 5 == 1) {
            ChunkTickets.follow((ServerWorld) level, this, getDeltaMovement(), 40);
        }
        // soft launch, then the sustainer kicks in
        speed = life < 4 ? speed : Math.min(type.speed(), speed + 0.35);
        Vector3d dir = getDeltaMovement().normalize();
        Vector3d wanted = guidance(dir);
        Vector3d v = turn(dir, wanted, type.turnRate()).scale(speed);

        Vector3d from = position();
        Vector3d to = from.add(v);
        BlockRayTraceResult block = level.clip(new RayTraceContext(from, to, RayTraceContext.BlockMode.COLLIDER,
                RayTraceContext.FluidMode.NONE, this));
        Vector3d end = block.getType() != RayTraceResult.Type.MISS ? block.getLocation() : to;
        Targeting.Hit hit = Targeting.firstEntity(level, this, from, end, 0.3, this::canHit);
        if (hit != null) {
            detonate(hit.pos, hit.entity);
            return;
        }
        if (block.getType() != RayTraceResult.Type.MISS) {
            detonate(block.getLocation(), null);
            return;
        }
        setDeltaMovement(v);
        setPos(to.x, to.y, to.z);
        yRotO = yRot;
        xRotO = xRot;
        face();
    }

    private boolean canHit(Entity e) {
        if (!Targeting.isShootable(e)) {
            return false;
        }
        if (life < 8) {
            Entity launcher = level.getEntity(launcherId);
            if (e.getId() == launcherId || (launcher != null && launcher.hasPassenger(e)) || e.getUUID().equals(owner)) {
                return false;
            }
        }
        return true;
    }

    private Vector3d guidance(Vector3d dir) {
        if (type.isBeamRiding()) {
            PlayerEntity op = guide == null ? null : level.getPlayerByUUID(guide);
            Entity launcher = level.getEntity(launcherId);
            boolean linked = op != null && op.isAlive() && launcher != null && launcher.hasPassenger(op);
            entityData.set(GUIDED, linked);
            if (!linked) {
                return dir;
            }
            Vector3d eye = op.getEyePosition(1F);
            Vector3d look = op.getViewVector(1F);
            double along = position().subtract(eye).dot(look);
            // a point a bit ahead on the line of sight, so it settles onto the line instead of chasing it
            return eye.add(look.scale(Math.max(along, 0) + 12.0)).subtract(position()).normalize();
        }
        if (target != null && level instanceof ServerWorld) {
            Entity t = ((ServerWorld) level).getEntity(target);
            if (t != null && t.isAlive()) {
                lastTarget = t.getBoundingBox().getCenter();
            }
        }
        if (lastTarget == null) {
            return dir;
        }
        Vector3d to = lastTarget.subtract(position());
        double hd = Math.sqrt(to.x * to.x + to.z * to.z);
        if (hd > 25) {
            // top attack: climb out, then come down on the roof
            double over = Math.min(55.0, hd * 0.55);
            to = to.add(0, over, 0);
        }
        return to.normalize();
    }

    /** Anything that hits it in flight (a 30 mm burst, shrapnel) sets the warhead off where it is. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level.isClientSide || isInvulnerableTo(source) || life < 3) {
            return false;
        }
        detonate(position(), null);
        return true;
    }

    @Override
    public boolean isPickable() {
        return isAlive();
    }

    private static Vector3d turn(Vector3d cur, Vector3d wanted, float maxDeg) {
        double dot = MathHelper.clamp(cur.dot(wanted), -1.0, 1.0);
        double ang = Math.acos(dot) * RAD2DEG;
        if (ang <= maxDeg || ang < 1e-3) {
            return wanted;
        }
        double k = maxDeg / ang;
        return cur.scale(1 - k).add(wanted.scale(k)).normalize();
    }

    private void detonate(Vector3d pos, @Nullable Entity direct) {
        if (!isAlive()) {
            return;
        }
        Vector3d dir = getDeltaMovement();
        remove();
        float punch = type.punch();
        Explosions.atgm(level, owner, pos, dir, direct, Config.ATGM_POWER.get().floatValue() * punch,
                Config.ATGM_DAMAGE.get().floatValue() * punch, Config.ATGM_PENETRATION.get() * punch);
    }

    private void clientTick() {
        if (!soundStarted) {
            soundStarted = true;
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onMissileSeen(this));
        }
        if (lerpSteps > 0) {
            setPos(getX() + (lerpX - getX()) / lerpSteps, getY() + (lerpY - getY()) / lerpSteps, getZ() + (lerpZ - getZ()) / lerpSteps);
            lerpSteps--;
        } else {
            Vector3d p = position().add(getDeltaMovement());
            setPos(p.x, p.y, p.z);
        }
        Vector3d back = Vector3d.directionFromRotation(xRot, yRot).scale(-0.6).add(position());
        level.addParticle(ParticleTypes.FLAME, back.x, back.y, back.z, 0, 0, 0);
        level.addParticle(ParticleTypes.CLOUD, back.x, back.y, back.z, 0, 0.01, 0);
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpSteps = 2;
        this.yRot = yRot;
        this.xRot = xRot;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSqr) {
        return distSqr < 256.0 * 256.0;
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        type = MissileType.byId(tag.getInt("Type"));
        life = tag.getInt("Life");
        speed = tag.getDouble("Speed");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        target = tag.hasUUID("Target") ? tag.getUUID("Target") : null;
        // the operator can't be holding the sight after a reload
        guide = null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        tag.putInt("Type", type.ordinal());
        tag.putInt("Life", life);
        tag.putDouble("Speed", speed);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (target != null) {
            tag.putUUID("Target", target);
        }
    }

    @Override
    public void writeSpawnData(PacketBuffer buf) {
        buf.writeByte(type.ordinal());
        Vector3d v = getDeltaMovement();
        buf.writeFloat((float) v.x);
        buf.writeFloat((float) v.y);
        buf.writeFloat((float) v.z);
    }

    @Override
    public void readSpawnData(PacketBuffer buf) {
        type = MissileType.byId(buf.readByte());
        setDeltaMovement(buf.readFloat(), buf.readFloat(), buf.readFloat());
        face();
        yRotO = yRot;
        xRotO = xRot;
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
