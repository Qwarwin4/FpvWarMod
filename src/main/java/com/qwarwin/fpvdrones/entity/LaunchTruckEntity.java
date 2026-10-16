package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.client.ClientHooks;
import com.qwarwin.fpvdrones.item.LaunchRemoteItem;
import com.qwarwin.fpvdrones.item.StrikeDroneItem;
import com.qwarwin.fpvdrones.item.TabletItem;
import com.qwarwin.fpvdrones.launch.LaunchConfig;
import com.qwarwin.fpvdrones.launch.LaunchSite;
import com.qwarwin.fpvdrones.launch.Launches;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.util.Explosions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 6x6 truck with a five-rail rack on the bed. Driven like a boat: the driver's client moves it and
 * vanilla syncs the position. Drones leave the rack backwards and up.
 */
public class LaunchTruckEntity extends Entity implements LaunchSite {
    public static final int CAPACITY = 5;
    public static final float RACK_ANGLE = 22.5F;
    public static final Vector3d RACK_PIVOT = new Vector3d(0, 1.2, 1.2);
    public static final double RACK_DRONE_Z = -1.75;
    public static final double RACK_STEP = 0.5;
    public static final double RACK_FIRST = 0.328;
    public static final float DRONE_SCALE = 1.1F;
    public static final Vector3d SEAT = new Vector3d(0.55, 1.45, 2.3);
    public static final double[][] WHEELS = {
            {-1.059, 2.1}, {1.059, 2.1}, {-1.059, -0.956}, {1.059, -0.956}, {-1.059, -2.044}, {1.059, -2.044}};
    public static final double WHEEL_RADIUS = 0.55;

    private static final DataParameter<Integer> SLOTS = EntityDataManager.defineId(LaunchTruckEntity.class, DataSerializers.INT);
    private static final DataParameter<Float> HEALTH = EntityDataManager.defineId(LaunchTruckEntity.class, DataSerializers.FLOAT);
    private static final float MAX_HEALTH = 80F;
    private static final float DEG = (float) (Math.PI / 180.0);

    private final LaunchConfig config = new LaunchConfig();
    @Nullable
    private UUID lastLaunched;
    private int salvoLeft;
    private int salvoCooldown;
    @Nullable
    private UUID salvoOwner;

    private double speed;
    public float wheelAngle, wheelAngleO;
    private boolean soundStarted;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot;

    public LaunchTruckEntity(EntityType<? extends LaunchTruckEntity> type, World world) {
        super(type, world);
        maxUpStep = 1.1F;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(SLOTS, 0);
        entityData.define(HEALTH, MAX_HEALTH);
    }

    // ---- LaunchSite

    @Override
    public LaunchConfig getLaunchConfig() {
        return config;
    }

    @Override
    public int getCapacity() {
        return CAPACITY;
    }

    // 3 bits per slot, 0 = empty
    @Nullable
    @Override
    public StrikeType getLoaded(int slot) {
        int v = (entityData.get(SLOTS) >> (slot * 3)) & 7;
        return v == 0 ? null : StrikeType.byIdOrNull(v - 1);
    }

    @Override
    public void setLoaded(int slot, @Nullable StrikeType type) {
        int v = entityData.get(SLOTS);
        v &= ~(7 << (slot * 3));
        if (type != null) {
            v |= (type.ordinal() + 1) << (slot * 3);
        }
        entityData.set(SLOTS, v);
    }

    /** Rack slot position relative to the truck, before the truck's own yaw. */
    public static Vector3d rackLocal(int slot) {
        double a = RACK_ANGLE * DEG;
        double y = RACK_FIRST + slot * RACK_STEP;
        double z = RACK_DRONE_Z;
        return RACK_PIVOT.add(0, y * Math.cos(a) - z * Math.sin(a), y * Math.sin(a) + z * Math.cos(a));
    }

    public Vector3d toWorld(Vector3d local) {
        return position().add(local.yRot(-yRot * DEG));
    }

    @Override
    public Vector3d getLaunchPos(int slot) {
        return toWorld(rackLocal(slot));
    }

    @Override
    public float getLaunchYaw() {
        return yRot + 180F;
    }

    @Override
    public float getLaunchPitch() {
        return -RACK_ANGLE;
    }

    @Override
    public World getSiteLevel() {
        return level;
    }

    @Nullable
    @Override
    public UUID getLastLaunched() {
        return lastLaunched;
    }

    @Override
    public void setLastLaunched(UUID id) {
        lastLaunched = id;
    }

    @Override
    public void onSiteChanged() {
    }

    @Override
    public ITextComponent getSiteName() {
        return new TranslationTextComponent("entity.fpvdrones.launch_truck");
    }

    @Override
    public CompoundNBT createLink() {
        CompoundNBT tag = new CompoundNBT();
        tag.putUUID("Truck", getUUID());
        return tag;
    }

    @Override
    public Launches.Result fireAll(@Nullable UUID owner) {
        if (salvoLeft > 0) {
            return Launches.Result.BUSY;
        }
        Launches.Result r = Launches.check(this);
        if (r == Launches.Result.OK) {
            salvoLeft = countLoaded();
            salvoCooldown = 0;
            salvoOwner = owner;
        }
        return r;
    }

    public boolean isFiring() {
        return salvoLeft > 0;
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
            if (!soundStarted) {
                soundStarted = true;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onTruckSeen(this));
            }
            tickLerp();
        }
        Vector3d before = position();
        if (isControlledByLocalInstance()) {
            Entity driver = getControllingPassenger();
            float fwd = 0F, turn = 0F;
            if (driver instanceof LivingEntity) {
                fwd = ((LivingEntity) driver).zza;
                turn = ((LivingEntity) driver).xxa;
            }
            drive(fwd, turn);
        }
        if (level.isClientSide) {
            spinWheels(position().subtract(before));
        } else {
            tickSalvo();
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

    private void spinWheels(Vector3d moved) {
        wheelAngleO = wheelAngle;
        double fwd = moved.x * -MathHelper.sin(yRot * DEG) + moved.z * MathHelper.cos(yRot * DEG);
        wheelAngle += (float) (fwd / WHEEL_RADIUS) * (180F / (float) Math.PI);
        if (Math.abs(wheelAngle) > 3600F) {
            float wrap = Math.signum(wheelAngle) * 3600F;
            wheelAngle -= wrap;
            wheelAngleO -= wrap;
        }
    }

    private void tickSalvo() {
        if (salvoLeft <= 0 || --salvoCooldown > 0) {
            return;
        }
        if (Launches.fireNext(this, salvoOwner) == Launches.Result.OK) {
            salvoLeft--;
            salvoCooldown = 14;
        } else {
            salvoLeft = 0;
        }
    }

    public double getSpeed() {
        return speed;
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

    // ---- interaction

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        boolean empty = stack.isEmpty();
        if (empty && hand != Hand.MAIN_HAND) {
            return ActionResultType.PASS;
        }
        if (level.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        if (stack.getItem() instanceof LaunchRemoteItem) {
            LaunchRemoteItem.toggleLink(stack, player, this);
        } else if (stack.getItem() instanceof TabletItem) {
            TabletItem.bindSite(stack, player, this);
        } else if (stack.getItem() instanceof StrikeDroneItem) {
            Launches.load(stack, player, this);
        } else if (player.isShiftKeyDown()) {
            if (player instanceof ServerPlayerEntity) {
                Launches.openMenu((ServerPlayerEntity) player, this);
            }
        } else if (canAddPassenger(player)) {
            player.startRiding(this);
        } else {
            return ActionResultType.PASS;
        }
        return ActionResultType.CONSUME;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level.isClientSide) {
            return true;
        }
        if (isInvulnerableTo(source) || !isAlive()) {
            return false;
        }
        if (source.getEntity() instanceof PlayerEntity && ((PlayerEntity) source.getEntity()).abilities.instabuild) {
            breakApart(false);
            return true;
        }
        float h = entityData.get(HEALTH) - amount;
        entityData.set(HEALTH, h);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.ANVIL_LAND, SoundCategory.NEUTRAL, 0.4F, 1.6F);
        if (h <= 0F) {
            breakApart(true);
        }
        return true;
    }

    private void breakApart(boolean survival) {
        int loaded = countLoaded();
        Vector3d center = position().add(0, 1.5, 0);
        if (survival) {
            spawnAtLocation(new ItemStack(ModItems.LAUNCH_TRUCK.get()));
        }
        remove();
        if (survival && loaded > 0) {
            // whatever was left on the rack cooks off
            Explosions.strike(level, null, center, 4F + loaded * 2F, true);
        }
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

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        config.load(tag);
        entityData.set(SLOTS, tag.getInt("Slots"));
        if (tag.contains("Health")) {
            entityData.set(HEALTH, tag.getFloat("Health"));
        }
        lastLaunched = tag.hasUUID("Last") ? tag.getUUID("Last") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        config.save(tag);
        tag.putInt("Slots", entityData.get(SLOTS));
        tag.putFloat("Health", entityData.get(HEALTH));
        if (lastLaunched != null) {
            tag.putUUID("Last", lastLaunched);
        }
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
