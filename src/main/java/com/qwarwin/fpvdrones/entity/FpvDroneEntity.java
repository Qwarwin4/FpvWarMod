package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.item.PayloadType;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.session.SessionManager;
import com.qwarwin.fpvdrones.util.Explosions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.List;

public class FpvDroneEntity extends AbstractDroneEntity {
    private static final double GRAVITY = 0.045;
    private static final double IMPACT_SPEED = 0.3;

    public FpvDroneEntity(EntityType<? extends FpvDroneEntity> type, World world) {
        super(type, world);
    }

    @Override
    public DroneKind kind() {
        return DroneKind.FPV;
    }

    @Override
    protected float maxHealth() {
        return 6F;
    }

    @Override
    protected int batteryTicks() {
        return Config.FPV_BATTERY_SECONDS.get() * 20;
    }

    @Override
    public double signalRange() {
        return Config.FPV_RANGE.get();
    }

    @Override
    public double maxSpeed() {
        return 2.6 * Config.FPV_SPEED.get();
    }

    @Override
    protected float baseWidth() {
        return 0.55F;
    }

    @Override
    protected float baseHeight() {
        return 0.25F;
    }

    @Override
    public float getHang() {
        return getPayload().getHang();
    }

    @Override
    public Item droneItem() {
        return ModItems.FPV_DRONE.get();
    }

    public PayloadType getPayload() {
        return PayloadType.byId(entityData.get(PAYLOAD));
    }

    public boolean isArmed() {
        return getPayload() != PayloadType.NONE;
    }

    @Override
    public List<PayloadType> getPayloads() {
        return isArmed() ? Collections.singletonList(getPayload()) : Collections.emptyList();
    }

    @Override
    public boolean tryAttach(PayloadType type) {
        if (isArmed() || !type.fitsFpv()) {
            return false;
        }
        entityData.set(PAYLOAD, type.ordinal());
        return true;
    }

    @Override
    protected void clearPayloads() {
        entityData.set(PAYLOAD, 0);
    }

    /*
     * No self levelling: motors only push while a key is held, let go and it drops.
     * W flies where the camera looks, A/D strafe, space climbs, shift dives, ctrl boosts.
     */
    @Override
    protected void flightStep(float forward, float strafe, float vertical, boolean boost, float yaw, float pitch, boolean powered) {
        Vector3d v = getDeltaMovement().add(0, -GRAVITY, 0);
        boolean throttle = forward != 0 || strafe != 0 || vertical > 0;
        if (powered && (throttle || vertical < 0)) {
            Vector3d look = calculateViewVector(pitch, yaw);
            Vector3d left = new Vector3d(MathHelper.cos(yaw * DEG), 0, MathHelper.sin(yaw * DEG));
            Vector3d thrust = look.scale(forward).add(left.scale(strafe));
            if (thrust.distanceToSqr(Vector3d.ZERO) > 1.0) {
                thrust = thrust.normalize();
            }
            double mul = Config.FPV_SPEED.get();
            v = v.add(thrust.scale((boost ? 0.16 : 0.1) * mul));
            if (throttle) {
                v = v.add(0, GRAVITY * 0.85, 0);
            }
            if (vertical > 0) {
                v = v.add(0, 0.085 * mul, 0);
            } else if (vertical < 0) {
                v = v.add(0, -0.03, 0);
            }
        }
        v = new Vector3d(v.x * 0.95, v.y * 0.96, v.z * 0.95);
        moveWithPad(v);
        if (isOnGround()) {
            setDeltaMovement(getDeltaMovement().multiply(0.6, 0, 0.6));
        }
        yRot = yaw;
        xRot = MathHelper.clamp(pitch, -90F, 90F);
    }

    @Override
    protected boolean checkLocalImpact(Vector3d before) {
        if (!isArmed()) {
            return false;
        }
        if ((horizontalCollision || verticalCollision) && before.length() > IMPACT_SPEED) {
            return true;
        }
        return hitsEntity();
    }

    private boolean hitsEntity() {
        return !level.getEntities(this, getBoundingBox().inflate(0.25),
                e -> e.isAlive() && !e.isSpectator() && (e instanceof LivingEntity || e instanceof AbstractDroneEntity
                        || e instanceof StrikeDroneEntity || e instanceof LaunchTruckEntity)).isEmpty();
    }

    @Override
    protected void onPilotImpact() {
        if (isArmed()) {
            detonate();
        }
    }

    @Override
    protected void serverPilotedTick() {
        // backup in case the client missed a mob
        if (isArmed() && getDeltaMovement().distanceToSqr(Vector3d.ZERO) > 0.04 && hitsEntity()) {
            detonate();
        }
    }

    @Override
    protected void autonomousStep(boolean hasBattery) {
        Vector3d before = getDeltaMovement();
        if (isResting() && before.distanceToSqr(Vector3d.ZERO) < 0.0001) {
            return;
        }
        flightStep(0, 0, 0, false, yRot, xRot, false);
        if (isArmed() && ((horizontalCollision || verticalCollision) && before.length() > IMPACT_SPEED || hitsEntity())) {
            detonate();
        }
    }

    @Override
    public void primaryAction(ServerPlayerEntity player) {
        if (isArmed()) {
            detonate();
        }
    }

    @Override
    protected void onDestroyed(DamageSource source) {
        if (isArmed()) {
            detonate();
        } else {
            super.onDestroyed(source);
        }
    }

    public void detonate() {
        if (level.isClientSide || !isAlive()) {
            return;
        }
        PayloadType type = getPayload();
        Vector3d pos = position().add(0, getHang() * 0.5 + 0.05, 0);
        Vector3d motion = getDeltaMovement();
        Vector3d dir = motion.distanceToSqr(Vector3d.ZERO) > 0.001 ? motion.normalize() : calculateViewVector(xRot, yRot);
        // send the pilot home first so the blast doesn't catch them in spectator
        SessionManager.endAllFor(this, "reason.fpvdrones.detonated");
        clearPayloads();
        remove();
        Explosions.payload(level, operator, pos, type, dir);
    }
}
