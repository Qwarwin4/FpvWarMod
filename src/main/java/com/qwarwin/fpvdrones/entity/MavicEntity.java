package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.item.PayloadType;
import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.registry.ModSounds;
import com.qwarwin.fpvdrones.util.ReconData;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds altitude on its own, two hooks under the belly for RGD-5 / VOG-17M / radio beacons.
 * Remembers where it took off and flies back there on H or at 20% battery.
 */
public class MavicEntity extends AbstractDroneEntity {
    public static final int SLOTS = 2;
    public static final double HOOK_X = 0.074;
    public static final float RTH_BATTERY = 0.2F;
    private static final double RTH_CLIMB = 20.0;

    private static final DataParameter<Float> HOME_X = EntityDataManager.defineId(MavicEntity.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> HOME_Y = EntityDataManager.defineId(MavicEntity.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> HOME_Z = EntityDataManager.defineId(MavicEntity.class, DataSerializers.FLOAT);
    private static final DataParameter<Boolean> HAS_HOME = EntityDataManager.defineId(MavicEntity.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Boolean> RETURNING = EntityDataManager.defineId(MavicEntity.class, DataSerializers.BOOLEAN);

    private boolean lowBatteryHandled;

    public MavicEntity(EntityType<? extends MavicEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(HOME_X, 0F);
        entityData.define(HOME_Y, 0F);
        entityData.define(HOME_Z, 0F);
        entityData.define(HAS_HOME, false);
        entityData.define(RETURNING, false);
    }

    public boolean hasHome() {
        return entityData.get(HAS_HOME);
    }

    public Vector3d getHome() {
        return new Vector3d(entityData.get(HOME_X), entityData.get(HOME_Y), entityData.get(HOME_Z));
    }

    private void setHome(Vector3d p) {
        entityData.set(HOME_X, (float) p.x);
        entityData.set(HOME_Y, (float) p.y);
        entityData.set(HOME_Z, (float) p.z);
        entityData.set(HAS_HOME, true);
    }

    public boolean isReturning() {
        return entityData.get(RETURNING);
    }

    private void setReturning(boolean v) {
        entityData.set(RETURNING, v);
    }

    /**
     * H key / low battery. Remembers where the drone turned back from (or what it was looking at)
     * so the launcher menu can pick it up as a target.
     */
    public void startReturn(@Nullable ServerPlayerEntity by, @Nullable BlockPos mark, String reason) {
        if (!hasHome() || isReturning()) {
            return;
        }
        setReturning(true);
        BlockPos point = mark != null ? mark : groundBelow();
        ServerPlayerEntity op = by != null ? by : operatorPlayer();
        if (op != null) {
            ReconData.set(op, point, 0);
            op.displayClientMessage(new TranslationTextComponent(reason, point.getX() + " " + point.getY() + " " + point.getZ())
                    .withStyle(TextFormatting.YELLOW), false);
        }
        level.playSound(null, getX(), getY(), getZ(), ModSounds.BEEP.get(), SoundCategory.NEUTRAL, 1.0F, 0.8F);
    }

    public void cancelReturn(ServerPlayerEntity by) {
        if (isReturning()) {
            setReturning(false);
            by.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.rth_cancel"), true);
        }
    }

    private BlockPos groundBelow() {
        BlockPos.Mutable p = new BlockPos.Mutable(getX(), getY(), getZ());
        while (p.getY() > 0 && level.isEmptyBlock(p)) {
            p.move(0, -1, 0);
        }
        return p.immutable();
    }

    @Nullable
    private ServerPlayerEntity operatorPlayer() {
        return operator == null || level.getServer() == null ? null : level.getServer().getPlayerList().getPlayer(operator);
    }

    @Override
    protected void serverUpdate(boolean piloted, boolean resting) {
        if (resting) {
            if (isReturning() && hasHome() && position().distanceToSqr(getHome()) < 9) {
                setReturning(false);
                ServerPlayerEntity op = operatorPlayer();
                if (op != null) {
                    op.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.rth_landed"), true);
                }
            }
            if (!isReturning()) {
                // home point is wherever it last sat before taking off
                setHome(position());
            }
            return;
        }
        float bat = getBattery();
        if (bat > 0.3F) {
            lowBatteryHandled = false;
        } else if (bat <= RTH_BATTERY && !lowBatteryHandled) {
            lowBatteryHandled = true;
            startReturn(null, null, "msg.fpvdrones.rth_battery");
        }
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
        if (isReturning() && hasHome()) {
            // autopilot owns the sticks, the camera stays free
            Vector3d home = getHome();
            double hx = home.x - getX(), hz = home.z - getZ();
            double hd = Math.sqrt(hx * hx + hz * hz);
            double safeY = home.y + RTH_CLIMB;
            float fx = -MathHelper.sin(yaw * DEG), fz = MathHelper.cos(yaw * DEG);
            double gain = hd < 0.05 ? 0 : Math.min(1.0, hd / 6.0) / hd;
            double wx = hx * gain, wz = hz * gain;
            if (hd > 2.0) {
                vertical = getY() < safeY - 0.5 || horizontalCollision ? 1F : 0F;
                if (getY() < safeY - 6 && !horizontalCollision) {
                    // climb out first, then go
                    wx *= 0.15;
                    wz *= 0.15;
                }
            } else {
                double above = getY() - home.y;
                vertical = above > 4 ? -0.8F : -0.3F;
            }
            forward = (float) (wx * fx + wz * fz);
            strafe = (float) (wx * fz - wz * fx);
            boost = hd > 40;
            if (getBattery() < 0.05F) {
                // critical: just put it down where it is
                forward = 0;
                strafe = 0;
                vertical = -0.35F;
            }
        }
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
        if (isReturning()) {
            flightStep(0, 0, 0, false, yRot, xRot, hasBattery);
            return;
        }
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
        if (t == PayloadType.RADIO_BEACON) {
            int code = 1000 + random.nextInt(9000);
            m.setBeaconCode(code);
            ServerPlayerEntity op = operatorPlayer();
            if (op != null) {
                op.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.beacon_dropped", code), true);
            }
        }
        double side = slot == 0 ? -HOOK_X : HOOK_X;
        float yawRad = yRot * DEG;
        m.moveTo(getX() + MathHelper.cos(yawRad) * side, getY(), getZ() + MathHelper.sin(yawRad) * side, yRot, 0F);
        Vector3d v = getDeltaMovement();
        m.setDeltaMovement(v.x, Math.min(v.y, 0) - 0.05, v.z);
        level.addFreshEntity(m);
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Home")) {
            CompoundNBT h = tag.getCompound("Home");
            setHome(new Vector3d(h.getDouble("X"), h.getDouble("Y"), h.getDouble("Z")));
        }
        setReturning(tag.getBoolean("RTH"));
        lowBatteryHandled = tag.getBoolean("LowBatRTH");
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        super.addAdditionalSaveData(tag);
        if (hasHome()) {
            Vector3d home = getHome();
            CompoundNBT h = new CompoundNBT();
            h.putDouble("X", home.x);
            h.putDouble("Y", home.y);
            h.putDouble("Z", home.z);
            tag.put("Home", h);
        }
        tag.putBoolean("RTH", isReturning());
        tag.putBoolean("LowBatRTH", lowBatteryHandled);
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
