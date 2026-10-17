package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.block.LaunchPadBlock;
import com.qwarwin.fpvdrones.client.ClientHooks;
import com.qwarwin.fpvdrones.control.ControlState;
import com.qwarwin.fpvdrones.item.DroneItem;
import com.qwarwin.fpvdrones.item.DroneRemoteItem;
import com.qwarwin.fpvdrones.item.MavicRemoteItem;
import com.qwarwin.fpvdrones.item.PayloadItem;
import com.qwarwin.fpvdrones.item.PayloadType;
import com.qwarwin.fpvdrones.network.DroneControlPacket;
import com.qwarwin.fpvdrones.network.DroneCorrectionPacket;
import com.qwarwin.fpvdrones.network.Network;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.registry.ModSounds;
import com.qwarwin.fpvdrones.session.SessionManager;
import com.qwarwin.fpvdrones.util.ChunkTickets;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntitySize;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.Pose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Shared quadcopter logic. While someone flies the drone the physics run on their client
 * (same idea as vanilla boats) and the server only sanity checks the positions it gets.
 */
public abstract class AbstractDroneEntity extends Entity {
    private static final DataParameter<Float> BATTERY = EntityDataManager.defineId(AbstractDroneEntity.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> HEALTH = EntityDataManager.defineId(AbstractDroneEntity.class, DataSerializers.FLOAT);
    protected static final DataParameter<Integer> PAYLOAD = EntityDataManager.defineId(AbstractDroneEntity.class, DataSerializers.INT);
    private static final DataParameter<Boolean> POWERED = EntityDataManager.defineId(AbstractDroneEntity.class, DataSerializers.BOOLEAN);

    protected static final float DEG = (float) (Math.PI / 180.0);

    @Nullable
    private UUID pilot;
    // whoever flew it last, gets the kill credit
    @Nullable
    protected UUID operator;

    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot, lerpXRot;

    public float tiltPitch, tiltPitchO, tiltRoll, tiltRollO;
    public float propAngle, propAngleO;
    private float propSpeed;
    private boolean soundStarted;
    private boolean impactSent;

    protected AbstractDroneEntity(EntityType<? extends AbstractDroneEntity> type, World world) {
        super(type, world);
    }

    public abstract DroneKind kind();

    protected abstract float maxHealth();

    protected abstract int batteryTicks();

    public abstract double signalRange();

    public abstract double maxSpeed();

    protected abstract float baseWidth();

    protected abstract float baseHeight();

    /** Distance between the lowest point of the payload and the frame. */
    public abstract float getHang();

    protected abstract void flightStep(float forward, float strafe, float vertical, boolean boost,
                                       float yaw, float pitch, boolean powered);

    protected abstract void autonomousStep(boolean hasBattery);

    public abstract Item droneItem();

    public abstract List<PayloadType> getPayloads();

    public abstract boolean tryAttach(PayloadType type);

    protected abstract void clearPayloads();

    /** G key: drop for the Mavic, detonate for the FPV. */
    public abstract void primaryAction(ServerPlayerEntity player);

    protected boolean checkLocalImpact(Vector3d motionBefore) {
        return false;
    }

    protected void onPilotImpact() {
    }

    protected void serverPilotedTick() {
    }

    /** Every server tick, after the battery bookkeeping. */
    protected void serverUpdate(boolean piloted, boolean resting) {
    }

    protected void onDestroyed(DamageSource source) {
        SessionManager.endAllFor(this, "reason.fpvdrones.target_lost");
        if (level instanceof ServerWorld) {
            ((ServerWorld) level).sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 0.2, getZ(), 12, 0.2, 0.2, 0.2, 0.02);
            ((ServerWorld) level).sendParticles(ParticleTypes.CRIT, getX(), getY() + 0.2, getZ(), 10, 0.3, 0.2, 0.3, 0.2);
        }
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_BREAK, SoundCategory.NEUTRAL, 1.0F, 0.6F);
        remove();
    }

    protected float tiltFactor() {
        return 28F;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(BATTERY, 1F);
        entityData.define(HEALTH, maxHealth());
        entityData.define(PAYLOAD, 0);
        entityData.define(POWERED, false);
    }

    @Override
    public void onSyncedDataUpdated(DataParameter<?> key) {
        super.onSyncedDataUpdated(key);
        if (PAYLOAD.equals(key)) {
            refreshDimensions();
        }
    }

    // the payload counts as part of the hitbox so the drone stands on it
    @Override
    public EntitySize getDimensions(Pose pose) {
        return EntitySize.scalable(baseWidth(), baseHeight() + getHang());
    }

    @Override
    protected float getEyeHeight(Pose pose, EntitySize size) {
        return getHang() + 0.12F;
    }

    public float getBattery() {
        return entityData.get(BATTERY);
    }

    public void setBattery(float v) {
        entityData.set(BATTERY, MathHelper.clamp(v, 0F, 1F));
    }

    public float getHealth() {
        return entityData.get(HEALTH);
    }

    public void setHealth(float v) {
        entityData.set(HEALTH, MathHelper.clamp(v, 0F, maxHealth()));
    }

    public boolean isPowered() {
        return entityData.get(POWERED);
    }

    protected void setPowered(boolean v) {
        if (entityData.get(POWERED) != v) {
            entityData.set(POWERED, v);
        }
    }

    @Nullable
    public UUID getOperatorId() {
        return operator;
    }

    @Nullable
    public UUID getPilot() {
        return pilot;
    }

    public void setPilot(@Nullable UUID uuid) {
        pilot = uuid;
        if (uuid != null) {
            operator = uuid;
        }
    }

    public void clearPilot(UUID uuid) {
        if (uuid.equals(pilot)) {
            pilot = null;
        }
    }

    public String shortName() {
        String code = getUUID().toString().substring(0, 4).toUpperCase();
        return (kind() == DroneKind.MAVIC ? "Mavic-3 #" : "FPV #") + code;
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        if (tag.contains("Battery")) {
            setBattery(tag.getFloat("Battery"));
        }
        if (tag.contains("Health")) {
            setHealth(tag.getFloat("Health"));
        }
        entityData.set(PAYLOAD, tag.getInt("Payload"));
        operator = tag.hasUUID("Operator") ? tag.getUUID("Operator") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        tag.putFloat("Battery", getBattery());
        tag.putFloat("Health", getHealth());
        tag.putInt("Payload", entityData.get(PAYLOAD));
        if (operator != null) {
            tag.putUUID("Operator", operator);
        }
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
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
    public boolean canChangeDimensions() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSqr) {
        return distSqr < 256.0 * 256.0;
    }

    public boolean isLocallyControlled() {
        return level.isClientSide && ControlState.controlledId == getId();
    }

    /**
     * Height the drone sits at when it's on a launch pad, NaN if there's no pad under it.
     * Pads have no collision on the forks, so the flight code uses this as a floor.
     */
    protected double padSupport() {
        BlockPos at = new BlockPos(getX(), getY() + 0.01, getZ());
        for (int i = 0; i < 2; i++) {
            BlockPos p = at.below(i);
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof LaunchPadBlock) {
                return p.getY() + LaunchPadBlock.FORK_TOP - getHang();
            }
        }
        return Double.NaN;
    }

    /** Keeps the drone from sinking through a pad. Returns the corrected motion. */
    protected Vector3d applyPad(Vector3d v) {
        double support = padSupport();
        if (!Double.isNaN(support) && getY() >= support - 0.05 && getY() + v.y <= support) {
            return new Vector3d(v.x * 0.5, support - getY(), v.z * 0.5);
        }
        return v;
    }

    protected boolean isOnPad() {
        double support = padSupport();
        return !Double.isNaN(support) && Math.abs(getY() - support) < 0.02;
    }

    protected boolean isResting() {
        if (getDeltaMovement().distanceToSqr(Vector3d.ZERO) > 0.01) {
            return false;
        }
        return isOnPad() || !level.noCollision(this, getBoundingBox().move(0, -0.08, 0));
    }

    /** move() that also zeroes vertical speed after landing on a pad */
    protected void moveWithPad(Vector3d v) {
        Vector3d fixed = applyPad(v);
        setDeltaMovement(fixed);
        move(MoverType.SELF, fixed);
        if (fixed != v) {
            setDeltaMovement(fixed.x, 0, fixed.z);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide) {
            clientTick();
        } else {
            serverTick();
        }
    }

    private void clientTick() {
        if (!soundStarted) {
            soundStarted = true;
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onDroneSeen(this));
        }
        Vector3d prev = position();
        if (isLocallyControlled()) {
            lerpSteps = 0;
            Vector3d before = getDeltaMovement();
            flightStep(ControlState.forward, ControlState.strafe, ControlState.vertical, ControlState.boost,
                    ControlState.yaw, ControlState.pitch, getBattery() > 0F);
            boolean impact = !impactSent && checkLocalImpact(before);
            if (impact) {
                impactSent = true;
            }
            Network.CHANNEL.sendToServer(new DroneControlPacket(getId(), getX(), getY(), getZ(), yRot, xRot,
                    getDeltaMovement(), impact));
        } else {
            impactSent = false;
            tickLerp();
        }
        updateVisuals(position().subtract(prev));
    }

    private void updateVisuals(Vector3d d) {
        tiltPitchO = tiltPitch;
        tiltRollO = tiltRoll;
        propAngleO = propAngle;
        float yawRad = yRot * DEG;
        double fx = -MathHelper.sin(yawRad);
        double fz = MathHelper.cos(yawRad);
        double fwd = d.x * fx + d.z * fz;
        double left = d.x * fz - d.z * fx;
        float k = tiltFactor();
        tiltPitch += ((float) MathHelper.clamp(fwd * k, -40, 40) - tiltPitch) * 0.3F;
        tiltRoll += ((float) MathHelper.clamp(-left * k, -40, 40) - tiltRoll) * 0.3F;
        propSpeed += ((isPowered() ? 1F : 0F) - propSpeed) * 0.08F;
        propAngle += propSpeed * 87F;
        if (propAngle > 3600F) {
            propAngle -= 3600F;
            propAngleO -= 3600F;
        }
    }

    private void serverTick() {
        ServerPlayerEntity p = pilotPlayer();
        if (pilot != null && (p == null || !SessionManager.isPiloting(p, this))) {
            pilot = null;
        }
        boolean piloted = pilot != null;
        float bat = getBattery();
        boolean resting = isResting();
        if (bat > 0F && (piloted || !resting)) {
            setBattery(bat - 1F / batteryTicks());
        }
        setPowered(bat > 0F && (piloted || !resting));
        if (!resting && tickCount % 20 == 0) {
            // keep the area loaded while it's in the air, otherwise it freezes mid flight
            ChunkTickets.keep((ServerWorld) level, this, position(), 2);
        }
        serverUpdate(piloted, resting);
        if (piloted) {
            serverPilotedTick();
        } else {
            autonomousStep(bat > 0F);
        }
        if (getY() < -64) {
            remove();
        }
    }

    @Nullable
    private ServerPlayerEntity pilotPlayer() {
        if (pilot == null || level.getServer() == null) {
            return null;
        }
        return level.getServer().getPlayerList().getPlayer(pilot);
    }

    public void acceptControl(ServerPlayerEntity player, DroneControlPacket msg) {
        if (!player.getUUID().equals(pilot)) {
            return;
        }
        double max = maxSpeed() * 3.0 + 2.0;
        if (new Vector3d(msg.x, msg.y, msg.z).distanceToSqr(position()) > max * max) {
            Network.sendTo(player, new DroneCorrectionPacket(getId(), getX(), getY(), getZ()));
            return;
        }
        setPos(msg.x, msg.y, msg.z);
        yRot = msg.yaw;
        xRot = msg.pitch;
        setDeltaMovement(msg.vx, msg.vy, msg.vz);
        if (msg.impact) {
            onPilotImpact();
        }
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        if (ControlState.controlledId == getId()) {
            return;
        }
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpXRot = xRot;
        lerpSteps = 3;
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void lerpMotion(double x, double y, double z) {
        if (ControlState.controlledId != getId()) {
            setDeltaMovement(x, y, z);
        }
    }

    private void tickLerp() {
        if (lerpSteps > 0) {
            double nx = getX() + (lerpX - getX()) / lerpSteps;
            double ny = getY() + (lerpY - getY()) / lerpSteps;
            double nz = getZ() + (lerpZ - getZ()) / lerpSteps;
            yRot += MathHelper.wrapDegrees(lerpYRot - yRot) / lerpSteps;
            xRot += (lerpXRot - xRot) / lerpSteps;
            --lerpSteps;
            setPos(nx, ny, nz);
        }
    }

    /** Called on the pilot's client when control starts. */
    public void snapToServerPosition() {
        if (lerpSteps > 0) {
            setPos(lerpX, lerpY, lerpZ);
            yRot = lerpYRot;
            xRot = lerpXRot;
            lerpSteps = 0;
        }
        impactSent = false;
    }

    public void applyCorrection(double x, double y, double z) {
        setPos(x, y, z);
        setDeltaMovement(Vector3d.ZERO);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) {
            return false;
        }
        if (level.isClientSide) {
            return true;
        }
        if (!isAlive()) {
            return false;
        }
        float h = getHealth() - amount;
        setHealth(h);
        if (h <= 0F) {
            onDestroyed(source);
        } else {
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.SHIELD_BLOCK, SoundCategory.NEUTRAL, 0.6F, 1.6F);
        }
        return true;
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Item item = stack.getItem();
        boolean handled = (item instanceof DroneRemoteItem && player.isShiftKeyDown())
                || item instanceof PayloadItem
                || item == Items.REDSTONE
                || item == ModItems.BATTERY.get()
                || (stack.isEmpty() && player.isShiftKeyDown() && hand == Hand.MAIN_HAND);
        if (!handled) {
            return ActionResultType.PASS;
        }
        if (level.isClientSide) {
            return ActionResultType.SUCCESS;
        }

        if (item instanceof MavicRemoteItem && kind() != DroneKind.MAVIC) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.rc_mavic_only"), true);
        } else if (item instanceof DroneRemoteItem) {
            DroneRemoteItem.bind(stack, this);
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.remote_bound_drone", shortName()), true);
            level.playSound(null, getX(), getY(), getZ(), ModSounds.BEEP.get(), SoundCategory.PLAYERS, 0.8F, 1.0F);
        } else if (item instanceof PayloadItem) {
            attachFrom(player, stack);
        } else if (item == Items.REDSTONE) {
            if (getBattery() < 1F) {
                setBattery(getBattery() + 0.25F);
                if (!player.abilities.instabuild) {
                    stack.shrink(1);
                }
                level.playSound(null, getX(), getY(), getZ(), ModSounds.BEEP.get(), SoundCategory.PLAYERS, 0.5F, 1.4F);
            }
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.charged", Math.round(getBattery() * 100F)), true);
        } else if (item == ModItems.BATTERY.get()) {
            swapBattery(player, stack);
        } else {
            pickUp(player);
        }
        return ActionResultType.CONSUME;
    }

    /** Fresh pack in, the old one comes back as a depleted battery (or a fresh one if it was barely used). */
    private void swapBattery(PlayerEntity player, ItemStack stack) {
        if (getBattery() >= 0.99F) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.battery_full"), true);
            return;
        }
        boolean wasEmpty = getBattery() < 0.5F;
        setBattery(1F);
        if (!player.abilities.instabuild) {
            stack.shrink(1);
            ItemStack old = new ItemStack(wasEmpty ? ModItems.DEPLETED_BATTERY.get() : ModItems.BATTERY.get());
            if (!player.inventory.add(old)) {
                player.drop(old, false);
            }
        }
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 0.8F, 1.3F);
        level.playSound(null, getX(), getY(), getZ(), ModSounds.BEEP.get(), SoundCategory.PLAYERS, 0.6F, 1.6F);
        player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.battery_swapped"), true);
    }

    private void attachFrom(PlayerEntity player, ItemStack stack) {
        PayloadType type = PayloadType.fromItem(stack.getItem());
        boolean fits = kind() == DroneKind.MAVIC ? type.fitsMavic() : type.fitsFpv();
        if (!fits) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.payload_incompatible"), true);
        } else if (!tryAttach(type)) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.payload_full"), true);
        } else {
            if (!player.abilities.instabuild) {
                stack.shrink(1);
            }
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.payload_attached",
                    new TranslationTextComponent(type.translationKey())), true);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 0.8F, 1.3F);
        }
    }

    private void pickUp(PlayerEntity player) {
        if (pilot != null) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.pickup_flying"), true);
            return;
        }
        ItemStack drop = new ItemStack(droneItem());
        CompoundNBT tag = drop.getOrCreateTag();
        tag.putFloat(DroneItem.TAG_BATTERY, getBattery());
        tag.putFloat(DroneItem.TAG_HEALTH, getHealth());
        if (hasCustomName()) {
            drop.setHoverName(getCustomName());
        }
        give(player, drop);
        for (PayloadType t : getPayloads()) {
            give(player, new ItemStack(t.item()));
        }
        clearPayloads();
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP, SoundCategory.PLAYERS, 0.6F, 1.0F);
        remove();
    }

    protected static void give(PlayerEntity player, ItemStack stack) {
        if (!player.inventory.add(stack)) {
            player.drop(stack, false);
        }
    }
}
