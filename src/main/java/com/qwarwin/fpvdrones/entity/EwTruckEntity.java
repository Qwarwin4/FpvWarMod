package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.registry.ModItems;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * Mobile jammer on a Ural-4320: a mast with jamming antennas over the cargo bed. Drives like the launch truck;
 * RMB gets in, Shift+RMB switches the jammer on and off.
 */
public class EwTruckEntity extends RadioMachineEntity {
    public static final Vector3d SEAT = new Vector3d(0.5, 1.45, 1.85);

    private double speed;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot;

    public EwTruckEntity(EntityType<? extends EwTruckEntity> type, World world) {
        super(type, world);
        maxUpStep = 1.1F;
    }

    @Override
    public double jamRadius() {
        return Config.EW_TRUCK_RADIUS.get();
    }

    @Override
    protected Item item() {
        return ModItems.EW_TRUCK.get();
    }

    @Override
    public float getMaxHealth() {
        return 100F;
    }

    @Override
    protected float damageFactor(DamageSource source) {
        return isShell(source) ? 0.6F : 1F;
    }

    // ---- driving

    @Nullable
    @Override
    public Entity getControllingPassenger() {
        return getPassengers().isEmpty() ? null : getPassengers().get(0);
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().isEmpty();
    }

    @Override
    public void positionRider(Entity passenger) {
        if (!hasPassenger(passenger)) {
            return;
        }
        Vector3d seat = toWorld(SEAT);
        passenger.setPos(seat.x, seat.y + passenger.getMyRidingOffset(), seat.z);
        float turn = MathHelper.wrapDegrees(yRot - yRotO);
        passenger.yRot += turn;
        passenger.setYHeadRot(passenger.getYHeadRot() + turn);
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide) {
            tickLerp();
        }
        if (isControlledByLocalInstance()) {
            Entity driver = getControllingPassenger();
            float fwd = 0F, turn = 0F;
            if (driver instanceof LivingEntity) {
                fwd = ((LivingEntity) driver).zza;
                turn = ((LivingEntity) driver).xxa;
            }
            drive(fwd, turn);
        }
    }

    private void drive(float fwd, float turn) {
        double max = 0.5 * Config.TRUCK_SPEED.get();
        if (fwd > 0) {
            speed = Math.min(max, speed + (speed < 0 ? 0.03 : 0.01));
        } else if (fwd < 0) {
            speed = Math.max(-0.18, speed - (speed > 0 ? 0.03 : 0.008));
        } else {
            speed *= 0.94;
            if (Math.abs(speed) < 0.003) {
                speed = 0;
            }
        }
        if (isOnGround() && speed != 0) {
            yRot -= turn * 2.6F * (float) MathHelper.clamp(speed / 0.15, -1.0, 1.0);
        }
        double vy = getDeltaMovement().y - 0.08;
        Vector3d horiz = new Vector3d(-MathHelper.sin(yRot * DEG), 0, MathHelper.cos(yRot * DEG)).scale(speed);
        setDeltaMovement(horiz.x, vy * 0.98, horiz.z);
        move(MoverType.SELF, getDeltaMovement());
        if (isOnGround()) {
            setDeltaMovement(getDeltaMovement().multiply(1, 0, 1));
        }
        if (horizontalCollision) {
            speed *= 0.5;
        }
        if (isInWater()) {
            speed *= 0.9;
        }
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpSteps = 5;
    }

    private void tickLerp() {
        if (isControlledByLocalInstance()) {
            lerpSteps = 0;
            return;
        }
        if (lerpSteps > 0) {
            double nx = getX() + (lerpX - getX()) / lerpSteps;
            double ny = getY() + (lerpY - getY()) / lerpSteps;
            double nz = getZ() + (lerpZ - getZ()) / lerpSteps;
            yRot += MathHelper.wrapDegrees(lerpYRot - yRot) / lerpSteps;
            --lerpSteps;
            setPos(nx, ny, nz);
        }
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()) {
            return ActionResultType.PASS;
        }
        if (level.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        if (player.isShiftKeyDown()) {
            toggle(player);
        } else if (!hasPassenger(player)) {
            player.startRiding(this);
        }
        return ActionResultType.CONSUME;
    }
}
