package com.qwarwin.fpvdrones.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A turret somebody sits behind. The gunner just looks around: the turret follows their view at its own
 * traverse speed, LMB fires the main weapon, RMB the secondary, G the extra action.
 *
 * Turret yaw is kept in world space, so on a moving hull the guns stay on target.
 */
public abstract class MountedWeaponEntity extends MachineEntity {
    private static final DataParameter<Float> TURRET_YAW = EntityDataManager.defineId(MountedWeaponEntity.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> GUN_PITCH = EntityDataManager.defineId(MountedWeaponEntity.class, DataSerializers.FLOAT);
    private static final DataParameter<Integer> AMMO = EntityDataManager.defineId(MountedWeaponEntity.class, DataSerializers.INT);
    private static final DataParameter<Integer> AMMO2 = EntityDataManager.defineId(MountedWeaponEntity.class, DataSerializers.INT);

    public float turretYawO, gunPitchO;
    private float turretYawC, gunPitchC;
    private boolean clientAimInit;

    protected boolean triggerHeld;
    protected int fireCooldown;
    @Nullable
    protected UUID owner;

    protected MountedWeaponEntity(EntityType<?> type, World world) {
        super(type, world);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(TURRET_YAW, 0F);
        entityData.define(GUN_PITCH, 0F);
        entityData.define(AMMO, 0);
        entityData.define(AMMO2, 0);
    }

    // ---- what each mount has to say about itself

    /** Turret ring centre, hull-local. */
    protected abstract Vector3d turretPivot();

    /** Where the gunner's eye goes, turret-local (relative to the pivot, before turret yaw). */
    protected abstract Vector3d sightOffset();

    /** Degrees per tick. */
    protected abstract float traverseSpeed();

    /** Elevation limits in xRot terms (negative = up). */
    protected abstract float minPitch();

    protected abstract float maxPitch();

    protected abstract void firePrimary();

    protected void fireSecondary(ServerPlayerEntity gunner) {
    }

    protected void onAction(ServerPlayerEntity gunner) {
    }

    /** Ticks between shots of the main weapon. */
    protected abstract int primaryInterval();

    public abstract int maxAmmo();

    public int maxAmmo2() {
        return 0;
    }

    // ---- turret state

    public float getTurretYaw() {
        return entityData.get(TURRET_YAW);
    }

    public float getGunPitch() {
        return entityData.get(GUN_PITCH);
    }

    /** Smoothed on the client, raw on the server. */
    public float getTurretYaw(float partial) {
        if (!level.isClientSide) {
            return getTurretYaw();
        }
        return turretYawO + MathHelper.wrapDegrees(turretYawC - turretYawO) * partial;
    }

    public float getGunPitch(float partial) {
        if (!level.isClientSide) {
            return getGunPitch();
        }
        return MathHelper.lerp(partial, gunPitchO, gunPitchC);
    }

    protected void setAim(float yaw, float pitch) {
        entityData.set(TURRET_YAW, MathHelper.wrapDegrees(yaw));
        entityData.set(GUN_PITCH, MathHelper.clamp(pitch, minPitch(), maxPitch()));
    }

    /** Turn towards a yaw/pitch at traverse speed. Returns the remaining error in degrees. */
    protected float slewTo(float yaw, float pitch) {
        float cy = getTurretYaw(), cp = getGunPitch();
        float dy = MathHelper.wrapDegrees(yaw - cy);
        float dp = MathHelper.clamp(pitch, minPitch(), maxPitch()) - cp;
        float sp = traverseSpeed();
        float ny = cy + MathHelper.clamp(dy, -sp, sp);
        float np = cp + MathHelper.clamp(dp, -sp * 0.75F, sp * 0.75F);
        setAim(ny, np);
        return Math.abs(MathHelper.wrapDegrees(yaw - ny)) + Math.abs(np - MathHelper.clamp(pitch, minPitch(), maxPitch()));
    }

    public int getAmmo() {
        return entityData.get(AMMO);
    }

    protected void setAmmo(int v) {
        entityData.set(AMMO, MathHelper.clamp(v, 0, maxAmmo()));
    }

    public int getAmmo2() {
        return entityData.get(AMMO2);
    }

    protected void setAmmo2(int v) {
        entityData.set(AMMO2, MathHelper.clamp(v, 0, maxAmmo2()));
    }

    public void resetAim(float yaw) {
        setAim(yaw, 0F);
    }

    /** Placed by a creative player: comes fully loaded. */
    public void fillAmmo() {
        setAmmo(maxAmmo());
        setAmmo2(maxAmmo2());
    }

    public Vector3d aimVector() {
        return Vector3d.directionFromRotation(getGunPitch(), getTurretYaw());
    }

    public Vector3d pivotWorld() {
        return toWorld(turretPivot());
    }

    /** Turret-local point (relative to the pivot, gun elevation not applied) to world. */
    public Vector3d turretToWorld(Vector3d local) {
        return pivotWorld().add(local.yRot(-getTurretYaw() * DEG));
    }

    /** Point on the gun (relative to the trunnion) to world, elevation applied. */
    public Vector3d gunToWorld(Vector3d trunnion, Vector3d local) {
        Vector3d v = local.xRot(-getGunPitch() * DEG).add(trunnion);
        return turretToWorld(v);
    }

    public Vector3d sightWorld() {
        return turretToWorld(sightOffset());
    }

    // ---- crew

    /** Second seat if somebody's in it, otherwise the first one does both. */
    @Nullable
    public Entity getGunner() {
        if (getPassengers().isEmpty()) {
            return null;
        }
        return getPassengers().size() > 1 ? getPassengers().get(1) : getPassengers().get(0);
    }

    public boolean isGunner(Entity e) {
        return e != null && e == getGunner();
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        if (isWrecked()) {
            return false;
        }
        return getPassengers().isEmpty();
    }

    @Override
    public void positionRider(Entity passenger) {
        if (!hasPassenger(passenger)) {
            return;
        }
        Vector3d eye = sightWorld();
        passenger.setPos(eye.x, eye.y - passenger.getEyeHeight(), eye.z);
    }

    public void handleInput(ServerPlayerEntity player, boolean firing, boolean secondary, boolean action) {
        if (!isGunner(player)) {
            return;
        }
        triggerHeld = firing;
        if (secondary) {
            fireSecondary(player);
        }
        if (action) {
            onAction(player);
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (getGunner() == null) {
            triggerHeld = false;
        }
    }

    // ---- ticking

    @Override
    public void tick() {
        super.tick();
        if (isWrecked()) {
            return;
        }
        if (level.isClientSide) {
            if (!clientAimInit) {
                clientAimInit = true;
                turretYawC = getTurretYaw();
                gunPitchC = getGunPitch();
            }
            turretYawO = turretYawC;
            gunPitchO = gunPitchC;
            turretYawC = getTurretYaw();
            gunPitchC = getGunPitch();
            if (Math.abs(MathHelper.wrapDegrees(turretYawC - turretYawO)) > 90F) {
                turretYawO = turretYawC;
            }
            return;
        }
        Entity gunner = getGunner();
        if (gunner instanceof PlayerEntity) {
            slewTo(gunner.yRot, gunner.xRot);
        } else {
            triggerHeld = false;
            idleAim();
        }
        if (fireCooldown > 0) {
            fireCooldown--;
        }
        if (triggerHeld && fireCooldown <= 0 && getAmmo() > 0) {
            firePrimary();
            fireCooldown = primaryInterval();
        }
    }

    /** Nobody at the controls. */
    protected void idleAim() {
    }

    // ---- save

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        super.readAdditionalSaveData(tag);
        setAim(tag.getFloat("TurretYaw"), tag.getFloat("GunPitch"));
        setAmmo(tag.getInt("Ammo"));
        setAmmo2(tag.getInt("Ammo2"));
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("TurretYaw", getTurretYaw());
        tag.putFloat("GunPitch", getGunPitch());
        tag.putInt("Ammo", getAmmo());
        tag.putInt("Ammo2", getAmmo2());
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }
}
