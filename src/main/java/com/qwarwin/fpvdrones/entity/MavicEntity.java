package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.item.PayloadType;
import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.registry.ModSounds;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/** Holds altitude on its own, two hooks under the belly for RGD-5 / VOG-17M. */
public class MavicEntity extends AbstractDroneEntity {
    public static final int SLOTS = 2;
    public static final double HOOK_X = 0.074;

    public MavicEntity(EntityType<? extends MavicEntity> type, World world) {
        super(type, world);
    }

    @Override
    public DroneKind kind() {
        return DroneKind.MAVIC;
    }

    @Override
    protected float maxHealth() {
        return 8F;
    }

    @Override
    protected int batteryTicks() {
        return Config.MAVIC_BATTERY_SECONDS.get() * 20;
    }

    @Override
    public double signalRange() {
        return Config.MAVIC_RANGE.get();
    }

    @Override
    public double maxSpeed() {
        return 1.2 * Config.MAVIC_SPEED.get();
    }

    @Override
    protected float baseWidth() {
        return 0.7F;
    }

    @Override
    protected float baseHeight() {
        return 0.3F;
    }

    @Override
    protected float tiltFactor() {
        return 32F;
    }

    @Override
    public float getHang() {
        float h = 0F;
        for (int i = 0; i < SLOTS; i++) {
            h = Math.max(h, getSlot(i).getHang());
        }
        return h;
    }

    @Override
    public Item droneItem() {
        return ModItems.MAVIC.get();
    }

    // 4 bits per slot
    public PayloadType getSlot(int i) {
        return PayloadType.byId((entityData.get(PAYLOAD) >> (i * 4)) & 0xF);
    }

    private void setSlot(int i, PayloadType t) {
        int v = entityData.get(PAYLOAD);
        v &= ~(0xF << (i * 4));
        v |= (t.ordinal() & 0xF) << (i * 4);
        entityData.set(PAYLOAD, v);
    }

    @Override
    public List<PayloadType> getPayloads() {
        List<PayloadType> list = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            if (getSlot(i) != PayloadType.NONE) {
                list.add(getSlot(i));
            }
        }
        return list;
    }

    @Override
    public boolean tryAttach(PayloadType type) {
        if (!type.fitsMavic()) {
            return false;
        }
        for (int i = 0; i < SLOTS; i++) {
            if (getSlot(i) == PayloadType.NONE) {
                setSlot(i, type);
                return true;
            }
        }
        return false;
    }

    @Override
    protected void clearPayloads() {
        entityData.set(PAYLOAD, 0);
    }

    @Override
    protected void flightStep(float forward, float strafe, float vertical, boolean boost, float yaw, float pitch, boolean powered) {
        Vector3d v = getDeltaMovement();
        if (powered) {
            double fx = -MathHelper.sin(yaw * DEG), fz = MathHelper.cos(yaw * DEG);
            double hx = fx * forward + fz * strafe;
            double hz = fz * forward - fx * strafe;
            double len = Math.sqrt(hx * hx + hz * hz);
            if (len > 1.0) {
                hx /= len;
                hz /= len;
            }
            double mul = Config.MAVIC_SPEED.get();
            double acc = (boost ? 0.19 : 0.11) * mul;
            v = new Vector3d(v.x * 0.82 + hx * acc, v.y * 0.78 + vertical * 0.085 * mul, v.z * 0.82 + hz * acc);
        } else {
            v = new Vector3d(v.x * 0.95, v.y - 0.04, v.z * 0.95);
        }
        moveWithPad(v);
        yRot = yaw;
        xRot = MathHelper.clamp(pitch, -30F, 90F);
    }

    @Override
    protected void autonomousStep(boolean hasBattery) {
        if (isResting() && getDeltaMovement().distanceToSqr(Vector3d.ZERO) < 0.0001) {
            return;
        }
        // lost link: hover, and land by itself when the battery is almost flat
        float vertical = getBattery() < 0.1F ? -0.35F : 0F;
        flightStep(0, 0, vertical, false, yRot, xRot, hasBattery);
    }

    @Override
    public void primaryAction(ServerPlayerEntity player) {
        for (int i = 0; i < SLOTS; i++) {
            PayloadType t = getSlot(i);
            if (t != PayloadType.NONE) {
                setSlot(i, PayloadType.NONE);
                drop(t, i);
                level.playSound(null, getX(), getY(), getZ(), ModSounds.RELEASE.get(), SoundCategory.NEUTRAL, 1.0F, 1.0F);
                return;
            }
        }
    }

    private void drop(PayloadType t, int slot) {
        MunitionEntity m = ModEntities.MUNITION.get().create(level);
        if (m == null) {
            return;
        }
        m.setup(t, operator);
        double side = slot == 0 ? -HOOK_X : HOOK_X;
        float yawRad = yRot * DEG;
        m.moveTo(getX() + MathHelper.cos(yawRad) * side, getY(), getZ() + MathHelper.sin(yawRad) * side, yRot, 0F);
        Vector3d v = getDeltaMovement();
        m.setDeltaMovement(v.x, Math.min(v.y, 0) - 0.05, v.z);
        level.addFreshEntity(m);
    }

    @Override
    protected void onDestroyed(DamageSource source) {
        for (int i = 0; i < SLOTS; i++) {
            if (getSlot(i) != PayloadType.NONE) {
                drop(getSlot(i), i);
            }
        }
        clearPayloads();
        super.onDestroyed(source);
    }
}
