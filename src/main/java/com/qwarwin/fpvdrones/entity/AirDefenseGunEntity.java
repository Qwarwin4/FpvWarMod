package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.registry.ModSounds;
import com.qwarwin.fpvdrones.util.Targeting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;

/**
 * Remote 30 mm weapon station on a pedestal, for shooting down drones. Programmable airburst rounds go off
 * next to a drone, so near misses still count. Left alone in auto mode it engages hostile drones by itself.
 */
public class AirDefenseGunEntity extends MountedWeaponEntity {
    public static final Vector3d PIVOT = new Vector3d(0, 1.15, 0);
    public static final Vector3d TRUNNION = new Vector3d(-0.05, 0.42, 0.15);
    public static final double MUZZLE = 2.55;
    public static final int ROUNDS_PER_BELT = 50;
    private static final double AUTO_RANGE = 140.0;

    private static final DataParameter<Boolean> AUTO = EntityDataManager.defineId(AirDefenseGunEntity.class, DataSerializers.BOOLEAN);

    private int targetId = -1;
    private int autoFireCooldown;

    public AirDefenseGunEntity(EntityType<? extends AirDefenseGunEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(AUTO, false);
    }

    public boolean isAuto() {
        return entityData.get(AUTO);
    }

    private void setAuto(boolean v) {
        entityData.set(AUTO, v);
    }

    @Override
    public float getMaxHealth() {
        return 60F;
    }

    @Override
    protected float damageFactor(DamageSource source) {
        if (isShapedCharge(source) || source.isExplosion()) {
            return 1F;
        }
        if (isShell(source)) {
            return 0.5F;
        }
        // taking it apart by hand is how you get it back
        return source.getDirectEntity() instanceof PlayerEntity ? 1F : 0.1F;
    }

    @Override
    protected Vector3d turretPivot() {
        return PIVOT;
    }

    @Override
    protected Vector3d sightOffset() {
        // the optics box on the left of the cradle
        return new Vector3d(0.52, 0.86, 0.05);
    }

    @Override
    protected float traverseSpeed() {
        return 7F;
    }

    @Override
    protected float minPitch() {
        return -80F;
    }

    @Override
    protected float maxPitch() {
        return 10F;
    }

    @Override
    protected int primaryInterval() {
        return 3;
    }

    @Override
    public int maxAmmo() {
        return 600;
    }

    @Override
    protected void firePrimary() {
        ShellEntity shell = ModEntities.SHELL.get().create(level);
        if (shell == null) {
            return;
        }
        Vector3d muzzle = gunToWorld(TRUNNION, new Vector3d(0, 0, MUZZLE));
        float spread = 0.35F;
        Vector3d dir = Vector3d.directionFromRotation(getGunPitch() + (random.nextFloat() - 0.5F) * spread,
                getTurretYaw() + (random.nextFloat() - 0.5F) * spread);
        Entity gunner = getGunner();
        shell.setup(this, gunner != null ? gunner.getUUID() : owner, muzzle, dir.scale(ShellEntity.SPEED), true);
        level.addFreshEntity(shell);
        setAmmo(getAmmo() - 1);
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, ModSounds.CANNON_30.get(), SoundCategory.HOSTILE, 5F,
                0.95F + random.nextFloat() * 0.1F);
        ((ServerWorld) level).sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 3, 0.05, 0.05, 0.05, 0.05);
        ((ServerWorld) level).sendParticles(ParticleTypes.POOF, muzzle.x, muzzle.y, muzzle.z, 2, 0.1, 0.1, 0.1, 0.02);
    }

    @Override
    protected void onAction(ServerPlayerEntity gunner) {
        toggleAuto(gunner);
    }

    private void toggleAuto(PlayerEntity player) {
        setAuto(!isAuto());
        player.displayClientMessage(new TranslationTextComponent(isAuto() ? "msg.fpvdrones.adgun_auto_on" : "msg.fpvdrones.adgun_auto_off")
                .withStyle(isAuto() ? TextFormatting.RED : TextFormatting.GRAY), true);
        level.playSound(null, getX(), getY(), getZ(), ModSounds.BEEP.get(), SoundCategory.BLOCKS, 0.8F, isAuto() ? 1.3F : 0.8F);
    }

    // ---- automatic mode

    @Override
    protected void idleAim() {
        if (!isAuto() || getAmmo() <= 0) {
            targetId = -1;
            return;
        }
        Vector3d eye = pivotWorld().add(0, 0.5, 0);
        Entity target = targetId < 0 ? null : level.getEntity(targetId);
        if (target == null || !target.isAlive() || tickCount % 10 == 0 && !isValidTarget(target, eye)) {
            target = tickCount % 4 == 0 ? findTarget(eye) : null;
            targetId = target == null ? -1 : target.getId();
        }
        if (target == null) {
            return;
        }
        Vector3d muzzle = gunToWorld(TRUNNION, new Vector3d(0, 0, MUZZLE));
        Vector3d aim = Targeting.lead(muzzle, target, ShellEntity.SPEED, ShellEntity.GRAVITY).subtract(muzzle);
        float err = slewTo(Targeting.yawTo(aim), Targeting.pitchTo(aim));
        if (autoFireCooldown > 0) {
            autoFireCooldown--;
        } else if (err < 2.5F) {
            firePrimary();
            autoFireCooldown = primaryInterval();
        }
    }

    private boolean isValidTarget(Entity e, Vector3d eye) {
        if (!e.isAlive() || !Targeting.isAirTarget(e) || e.distanceToSqr(eye) > AUTO_RANGE * AUTO_RANGE) {
            return false;
        }
        if (e instanceof AbstractDroneEntity && !((AbstractDroneEntity) e).isPowered()) {
            return false;
        }
        return !Targeting.isFriendly(level, owner, Targeting.ownerOf(e)) && Targeting.canSee(level, eye, e);
    }

    @Nullable
    private Entity findTarget(Vector3d eye) {
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : level.getEntities(this, new AxisAlignedBB(eye, eye).inflate(AUTO_RANGE), Targeting::isAirTarget)) {
            double d = e.distanceToSqr(eye);
            if (d < bestD && isValidTarget(e, eye)) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    // ---- interaction

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty() && hand != Hand.MAIN_HAND) {
            return ActionResultType.PASS;
        }
        if (level.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        if (stack.getItem() == ModItems.AMMO_30MM.get()) {
            if (getAmmo() + ROUNDS_PER_BELT > maxAmmo()) {
                player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.ammo_full"), true);
            } else {
                setAmmo(getAmmo() + ROUNDS_PER_BELT);
                if (!player.abilities.instabuild) {
                    stack.shrink(1);
                }
                level.playSound(null, getX(), getY(), getZ(), ModSounds.RELOAD.get(), SoundCategory.BLOCKS, 1F, 1F);
                player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.ammo", getAmmo(), maxAmmo()), true);
            }
        } else if (stack.isEmpty() && player.isShiftKeyDown()) {
            if (owner == null) {
                owner = player.getUUID();
            }
            toggleAuto(player);
        } else if (canAddPassenger(player)) {
            player.startRiding(this);
        } else {
            return ActionResultType.PASS;
        }
        return ActionResultType.CONSUME;
    }

    @Override
    protected void destroy(@Nullable DamageSource cause) {
        Vector3d c = position().add(0, 1, 0);
        ejectPassengers();
        remove();
        if (isDismantled(cause)) {
            spawnAtLocation(new ItemStack(ModItems.AIR_DEFENSE_GUN.get()));
            int belts = getAmmo() / ROUNDS_PER_BELT;
            if (belts > 0) {
                spawnAtLocation(new ItemStack(ModItems.AMMO_30MM.get(), belts));
            }
        } else if (cause != null) {
            level.explode(null, c.x, c.y, c.z, 2F + getAmmo() / 200F, Explosion.Mode.NONE);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        super.readAdditionalSaveData(tag);
        setAuto(tag.getBoolean("Auto"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Auto", isAuto());
    }
}
