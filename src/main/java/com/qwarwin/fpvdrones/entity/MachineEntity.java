package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.util.ModDamageSources;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;

/**
 * Anything big and steel that gets shot at: the launch truck, the gun mount, the Kornet.
 * Takes damage by type, so a rifle does next to nothing to armour and a shaped charge does a lot.
 */
public abstract class MachineEntity extends Entity {
    private static final DataParameter<Float> HEALTH = EntityDataManager.defineId(MachineEntity.class, DataSerializers.FLOAT);
    protected static final float DEG = (float) (Math.PI / 180.0);

    protected MachineEntity(EntityType<?> type, World world) {
        super(type, world);
    }

    public abstract float getMaxHealth();

    /** How much of a hit gets through. */
    protected float damageFactor(DamageSource source) {
        return 1F;
    }

    /**
     * Health hit zero. cause is null when a creative player removed it; {@link #isDismantled} tells a player
     * taking it apart with their hands from it being blown up.
     */
    protected abstract void destroy(@Nullable DamageSource cause);

    protected static boolean isDismantled(@Nullable DamageSource cause) {
        return cause != null && !cause.isExplosion() && cause.getDirectEntity() instanceof PlayerEntity;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(HEALTH, getMaxHealth());
    }

    public float getHealth() {
        return entityData.get(HEALTH);
    }

    protected void setHealth(float h) {
        entityData.set(HEALTH, Math.min(getMaxHealth(), h));
    }

    public static boolean isShapedCharge(DamageSource source) {
        return source.getMsgId().startsWith("fpvdrones.cumulative");
    }

    public static boolean isShell(DamageSource source) {
        return source.getMsgId().startsWith(ModDamageSources.SHELL);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level.isClientSide) {
            return true;
        }
        if (isInvulnerableTo(source) || !isAlive()) {
            return false;
        }
        if (source.getEntity() instanceof PlayerEntity && ((PlayerEntity) source.getEntity()).abilities.instabuild
                && source.getDirectEntity() == source.getEntity()) {
            destroy(null);
            return true;
        }
        float dealt = amount * damageFactor(source);
        if (dealt <= 0.05F) {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.SHIELD_BLOCK, SoundCategory.NEUTRAL, 0.5F, 0.7F);
            return false;
        }
        float h = getHealth() - dealt;
        setHealth(h);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_LAND, SoundCategory.NEUTRAL, 0.4F, 1.6F);
        if (h < getMaxHealth() * 0.3F) {
            ((ServerWorld) level).sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + getBbHeight(), getZ(),
                    4, 0.4, 0.2, 0.4, 0.02);
        }
        if (h <= 0F) {
            destroy(source);
        }
        return true;
    }

    @Override
    public boolean isPickable() {
        return isAlive();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public boolean canChangeDimensions() {
        return false;
    }

    @Override
    public AxisAlignedBB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(3.5, 2.5, 3.5);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSqr) {
        return distSqr < 192.0 * 192.0;
    }

    /** Local (x right, y up, z forward) to world, using the body yaw. */
    public Vector3d toWorld(Vector3d local) {
        return position().add(local.yRot(-yRot * DEG));
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        if (tag.contains("Health")) {
            setHealth(tag.getFloat("Health"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        tag.putFloat("Health", getHealth());
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
