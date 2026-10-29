package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.util.Explosions;
import com.qwarwin.fpvdrones.util.ModDamageSources;
import com.qwarwin.fpvdrones.util.Targeting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.PacketBuffer;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 30 mm round. Too fast for normal entity movement, so it ray-casts its whole step every tick.
 * The client flies its own copy from the spawn velocity, the server decides what it hits.
 */
public class ShellEntity extends Entity implements IEntityAdditionalSpawnData {
    public static final double SPEED = 9.0;
    public static final double GRAVITY = 0.03;
    public static final double DRAG = 0.995;
    private static final int LIFE = 100;
    /** Fragment cloud of a round burst by its programmed fuse at the computed point. */
    private static final double CLOUD = 4.0;
    /** The fuse sees further around a big target than around a small quadcopter. */
    private static final double FUSE_BASE = 0.7;
    private static final double FUSE_PER_WIDTH = 0.8;
    private static final double FUSE_MAX = 3.0;

    @Nullable
    private UUID owner;
    private int shooterId = -1;
    private boolean airburst;
    // "everything" mode: the fuse doesn't care whose it is
    private boolean fuseAll;
    private int life;
    /** Programmed fuse: burst after this many blocks of flight (0: none, proximity only). */
    private double burstAt;
    private double travelled;

    public ShellEntity(EntityType<? extends ShellEntity> type, World world) {
        super(type, world);
        noPhysics = true;
    }

    public void setup(Entity shooter, @Nullable UUID owner, Vector3d pos, Vector3d velocity, boolean airburst) {
        this.shooterId = shooter.getId();
        this.owner = owner;
        // a fair share of programmable fuses is set wrong or simply fails: that round can only score a direct hit
        this.airburst = airburst && shooter.level.random.nextDouble() < Config.AIRBURST_RELIABILITY.get();
        setPos(pos.x, pos.y, pos.z);
        setDeltaMovement(velocity);
        faceMotion();
        yRotO = yRot;
        xRotO = xRot;
    }

    /** The fire control sets the fuse to go off where the round meets the target's predicted track. */
    public void programBurst(double distance) {
        burstAt = airburst ? distance : 0;
    }

    public void setFuseAll(boolean v) {
        fuseAll = v;
    }

    public boolean isAirburst() {
        return airburst;
    }

    @Override
    protected void defineSynchedData() {
    }

    private void faceMotion() {
        Vector3d v = getDeltaMovement();
        yRot = Targeting.yawTo(v);
        xRot = Targeting.pitchTo(v);
    }

    @Override
    public void tick() {
        super.tick();
        if (++life > LIFE) {
            remove();
            return;
        }
        Vector3d v = getDeltaMovement();
        Vector3d from = position();
        Vector3d to = from.add(v);

        if (!level.isClientSide) {
            BlockRayTraceResult block = level.clip(new RayTraceContext(from, to, RayTraceContext.BlockMode.COLLIDER,
                    RayTraceContext.FluidMode.NONE, this));
            Vector3d end = block.getType() != RayTraceResult.Type.MISS ? block.getLocation() : to;
            Targeting.Hit hit = Targeting.firstEntity(level, this, from, end, 0.2, this::canHit);
            if (hit != null) {
                impact(hit.pos, hit.entity);
                return;
            }
            if (airburst) {
                Vector3d burst = proximity(from, end);
                if (burst != null) {
                    impact(burst, null);
                    return;
                }
                double step = end.distanceTo(from);
                if (burstAt > 0 && travelled + step >= burstAt) {
                    Vector3d at = from.add(end.subtract(from).scale((burstAt - travelled) / Math.max(step, 1e-6)));
                    remove();
                    Explosions.shell(level, this, owner, at, true, CLOUD);
                    return;
                }
                travelled += step;
            }
            if (block.getType() != RayTraceResult.Type.MISS) {
                impact(block.getLocation(), null);
                return;
            }
        } else if (life > 1 && life % 2 == 0) {
            level.addParticle(ParticleTypes.SMOKE, from.x, from.y, from.z, 0, 0, 0);
        }

        setPos(to.x, to.y, to.z);
        setDeltaMovement(v.scale(DRAG).add(0, -GRAVITY, 0));
        yRotO = yRot;
        xRotO = xRot;
        faceMotion();
    }

    private boolean canHit(Entity e) {
        if (e.getId() == shooterId || !Targeting.isShootable(e)) {
            return false;
        }
        Entity shooter = level.getEntity(shooterId);
        return shooter == null || !shooter.hasPassenger(e);
    }

    /** Nearest point of this step to a hostile drone within fuse range. */
    @Nullable
    private Vector3d proximity(Vector3d from, Vector3d to) {
        Vector3d seg = to.subtract(from);
        double len2 = seg.dot(seg);
        Vector3d best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : level.getEntities(this, new AxisAlignedBB(from, to).inflate(FUSE_MAX),
                fuseAll ? Targeting::isAirborne : Targeting::isAirTarget)) {
            if (fuseAll ? e.getUUID().equals(owner) : Targeting.isFriendly(level, owner, Targeting.ownerOf(e))) {
                continue;
            }
            Vector3d c = e.getBoundingBox().getCenter();
            double t = len2 < 1e-6 ? 0 : MathHelper.clamp(c.subtract(from).dot(seg) / len2, 0, 1);
            Vector3d p = from.add(seg.scale(t));
            double fuse = Math.min(FUSE_MAX, FUSE_BASE + FUSE_PER_WIDTH * e.getBbWidth());
            double d = p.distanceToSqr(c);
            if (d < fuse * fuse && d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private void impact(Vector3d pos, @Nullable Entity direct) {
        remove();
        if (direct != null) {
            if (direct instanceof LivingEntity) {
                direct.invulnerableTime = 0;
            }
            direct.hurt(ModDamageSources.shell(this, Explosions.player(level, owner)), 9F);
        }
        Explosions.shell(level, this, owner, pos, airburst);
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        // the client flies its own copy
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSqr) {
        return distSqr < 256.0 * 256.0;
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        life = tag.getInt("Life");
        airburst = tag.getBoolean("Airburst");
        burstAt = tag.getDouble("BurstAt");
        travelled = tag.getDouble("Travelled");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        tag.putInt("Life", life);
        tag.putBoolean("Airburst", airburst);
        tag.putDouble("BurstAt", burstAt);
        tag.putDouble("Travelled", travelled);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
    }

    @Override
    public void writeSpawnData(PacketBuffer buf) {
        Vector3d v = getDeltaMovement();
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
        buf.writeBoolean(airburst);
        buf.writeVarInt(shooterId);
    }

    @Override
    public void readSpawnData(PacketBuffer buf) {
        setDeltaMovement(buf.readDouble(), buf.readDouble(), buf.readDouble());
        airburst = buf.readBoolean();
        shooterId = buf.readVarInt();
        faceMotion();
        yRotO = yRot;
        xRotO = xRot;
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
