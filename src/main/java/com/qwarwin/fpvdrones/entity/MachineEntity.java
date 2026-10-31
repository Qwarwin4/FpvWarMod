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
    private static final DataParameter<Boolean> WRECK = EntityDataManager.defineId(MachineEntity.class, DataSerializers.BOOLEAN);
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

    /** Taken apart by a player's own hands (not his drone's jet or his shells, which are attacks like any). */
    protected static boolean isDismantled(@Nullable DamageSource cause) {
        return cause != null && isMelee(cause);
    }

    private static boolean isMelee(DamageSource source) {
        return "player".equals(source.getMsgId()) && source.getDirectEntity() instanceof PlayerEntity
                && source.getDirectEntity() == source.getEntity();
    }

    // ---- wrecks: what's left after it's blown up, dark and smoking, until someone clears it away by hand

    public boolean isWrecked() {
        return entityData.get(WRECK);
    }

    /** Instead of vanishing when blown up: stays where it is as a burnt-out wreck. */
    protected void removeOrWreck(@Nullable DamageSource cause) {
        if (cause == null || isDismantled(cause) || cause == DamageSource.OUT_OF_WORLD) {
            remove();
            return;
        }
        setHealth(0F);
        entityData.set(WRECK, true);
    }

    private int wreckAge;

    @Override
    public void tick() {
        super.tick();
        if (!isWrecked()) {
            return;
        }
        wreckAge++;
        if (level.isClientSide) {
            if (wreckAge < 6000 && random.nextInt(wreckAge < 1200 ? 1 : 4) == 0) {
                double r = getBbWidth() * 0.35;
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX() + (random.nextDouble() - 0.5) * r,
                        getY() + getBbHeight() * 0.8, getZ() + (random.nextDouble() - 0.5) * r, 0, 0.05, 0);
                if (wreckAge < 1200 && random.nextInt(3) == 0) {
                    level.addParticle(ParticleTypes.FLAME, getX() + (random.nextDouble() - 0.5) * r,
                            getY() + getBbHeight() * 0.5, getZ() + (random.nextDouble() - 0.5) * r, 0, 0.02, 0);
                }
            }
            return;
        }
        Vector3d m = getDeltaMovement();
        setDeltaMovement(m.x * 0.8, m.y - 0.08, m.z * 0.8);
        move(net.minecraft.entity.MoverType.SELF, getDeltaMovement());
        if (isOnGround()) {
            setDeltaMovement(Vector3d.ZERO);
        }
        if (getY() < -64) {
            remove();
        }
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(HEALTH, getMaxHealth());
        entityData.define(WRECK, false);
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
        if (isWrecked()) {
            // a wreck only goes when someone clears it away by hand
            if (isMelee(source)) {
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_DESTROY, SoundCategory.NEUTRAL, 0.7F, 0.8F);
                if (!((PlayerEntity) source.getDirectEntity()).abilities.instabuild) {
                    spawnAtLocation(new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_INGOT, 1 + random.nextInt(3)));
                }
                remove();
                return true;
            }
            return false;
        }
        if (isMelee(source) && ((PlayerEntity) source.getEntity()).abilities.instabuild) {
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
        entityData.set(WRECK, tag.getBoolean("Wreck"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        tag.putFloat("Health", getHealth());
        tag.putBoolean("Wreck", isWrecked());
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
