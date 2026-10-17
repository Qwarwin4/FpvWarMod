package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.client.ClientHooks;
import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.registry.ModSounds;
import com.qwarwin.fpvdrones.util.Explosions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;

/**
 * BMPT "Terminator": T-72 hull, a turret with twin 2A42 30 mm guns and four "Ataka" ATGMs in pods.
 * Seat 1 drives, seat 2 shoots. Alone, the driver also aims with the mouse while driving with WASD.
 */
public class TerminatorEntity extends MountedWeaponEntity {
    public static final Vector3d PIVOT = new Vector3d(0, 1.4, -0.35);
    public static final Vector3d TRUNNION = new Vector3d(0, 0.78, 0.55);
    public static final double GUN_X = 0.2;
    public static final double MUZZLE = 2.45;
    // ATGM tubes on the cradle, relative to the trunnion: left/right, lower/upper
    public static final double POD_X = 1.12;
    public static final double[] POD_Y = {-0.12, 0.2};
    public static final double POD_FRONT = 1.2;
    public static final double[] ROAD_WHEELS = {2.25, 1.35, 0.45, -0.45, -1.35, -2.25};
    public static final double TRACK_X = 1.38;
    public static final double WHEEL_R = 0.42;
    private static final Vector3d DRIVER_EYE = new Vector3d(0, 2.0, 2.05);

    private int barrel;
    private int missileCooldown;
    private double speed;
    public float trackAngle, trackAngleO;
    private boolean soundStarted;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot;

    public TerminatorEntity(EntityType<? extends TerminatorEntity> type, World world) {
        super(type, world);
        maxUpStep = 1.1F;
    }

    @Override
    public float getMaxHealth() {
        return 400F;
    }

    @Override
    protected float damageFactor(DamageSource source) {
        if (isShapedCharge(source)) {
            return 1F;
        }
        if (source.isExplosion()) {
            return 0.3F;
        }
        if (isShell(source)) {
            return 0.06F;
        }
        return 0.02F;
    }

    @Override
    protected Vector3d turretPivot() {
        return PIVOT;
    }

    @Override
    protected Vector3d sightOffset() {
        // gunner's sight block on the right of the weapon module
        return new Vector3d(-0.62, 1.12, 0.35);
    }

    @Override
    protected float traverseSpeed() {
        return 4.5F;
    }

    @Override
    protected float minPitch() {
        return -45F;
    }

    @Override
    protected float maxPitch() {
        return 5F;
    }

    @Override
    protected int primaryInterval() {
        return 2;
    }

    @Override
    public int maxAmmo() {
        return 900;
    }

    @Override
    public int maxAmmo2() {
        return 4;
    }

    public double getSpeed() {
        return speed;
    }

    // ---- crew

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return getPassengers().size() < 2;
    }

    @Nullable
    @Override
    public Entity getControllingPassenger() {
        return getPassengers().isEmpty() ? null : getPassengers().get(0);
    }

    @Override
    public void positionRider(Entity passenger) {
        if (!hasPassenger(passenger)) {
            return;
        }
        if (getPassengers().size() > 1 && getPassengers().get(0) == passenger) {
            Vector3d eye = toWorld(DRIVER_EYE);
            passenger.setPos(eye.x, eye.y - passenger.getEyeHeight(), eye.z);
            return;
        }
        super.positionRider(passenger);
    }

    // ---- weapons

    @Override
    protected void firePrimary() {
        ShellEntity shell = ModEntities.SHELL.get().create(level);
        if (shell == null) {
            return;
        }
        barrel ^= 1;
        Vector3d muzzle = gunToWorld(TRUNNION, new Vector3d(barrel == 0 ? GUN_X : -GUN_X, 0, MUZZLE));
        float spread = 0.5F;
        Vector3d dir = Vector3d.directionFromRotation(getGunPitch() + (random.nextFloat() - 0.5F) * spread,
                getTurretYaw() + (random.nextFloat() - 0.5F) * spread);
        Entity gunner = getGunner();
        shell.setup(this, gunner != null ? gunner.getUUID() : owner, muzzle, dir.scale(ShellEntity.SPEED), false);
        level.addFreshEntity(shell);
        setAmmo(getAmmo() - 1);
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, ModSounds.CANNON_30.get(), SoundCategory.HOSTILE, 5F,
                0.9F + random.nextFloat() * 0.1F);
        ((ServerWorld) level).sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 3, 0.05, 0.05, 0.05, 0.05);
    }

    @Override
    protected void fireSecondary(ServerPlayerEntity gunner) {
        if (missileCooldown > 0) {
            return;
        }
        int left = getAmmo2();
        if (left <= 0) {
            gunner.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.no_missiles"), true);
            return;
        }
        MissileEntity m = ModEntities.MISSILE.get().create(level);
        if (m == null) {
            return;
        }
        int tube = maxAmmo2() - left;
        Vector3d pos = gunToWorld(TRUNNION, podTube(tube).add(0, 0, POD_FRONT + 0.3));
        m.setup(MissileType.ATAKA, gunner.getUUID(), this, pos, aimVector());
        level.addFreshEntity(m);
        setAmmo2(left - 1);
        missileCooldown = 30;
        level.playSound(null, pos.x, pos.y, pos.z, ModSounds.ATGM_LAUNCH.get(), SoundCategory.HOSTILE, 5F, 1.1F);
        ((ServerWorld) level).sendParticles(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, 12, 0.3, 0.3, 0.3, 0.05);
    }

    /** Tube centre relative to the trunnion: 0,1 on the left pod, 2,3 on the right, lower first. */
    public static Vector3d podTube(int i) {
        return new Vector3d(i < 2 ? POD_X : -POD_X, POD_Y[i % 2], 0);
    }

    // ---- driving

    @Override
    public void tick() {
        super.tick();
        if (missileCooldown > 0) {
            missileCooldown--;
        }
        if (level.isClientSide) {
            if (!soundStarted) {
                soundStarted = true;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onTerminatorSeen(this));
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
            Vector3d moved = position().subtract(before);
            trackAngleO = trackAngle;
            double fwd = moved.x * -MathHelper.sin(yRot * DEG) + moved.z * MathHelper.cos(yRot * DEG);
            trackAngle += (float) (fwd / WHEEL_R) * (180F / (float) Math.PI);
            if (Math.abs(trackAngle) > 3600F) {
                float wrap = Math.signum(trackAngle) * 3600F;
                trackAngle -= wrap;
                trackAngleO -= wrap;
            }
        }
    }

    private void drive(float fwd, float turn) {
        double max = 0.4 * Config.BMPT_SPEED.get();
        if (fwd > 0) {
            speed = Math.min(max, speed + (speed < 0 ? 0.03 : 0.012));
        } else if (fwd < 0) {
            speed = Math.max(-0.2, speed - (speed > 0 ? 0.03 : 0.01));
        } else {
            speed *= 0.9;
            if (Math.abs(speed) < 0.003) {
                speed = 0;
            }
        }
        if (isOnGround()) {
            // tracks turn on the spot
            float rate = speed < -0.01 ? -2.4F : 2.4F;
            yRot -= turn * rate;
        }
        double vy = getDeltaMovement().y - 0.08;
        Vector3d horiz = new Vector3d(-MathHelper.sin(yRot * DEG), 0, MathHelper.cos(yRot * DEG)).scale(speed);
        setDeltaMovement(horiz.x, vy * 0.98, horiz.z);
        move(MoverType.SELF, getDeltaMovement());
        if (isOnGround()) {
            setDeltaMovement(getDeltaMovement().multiply(1, 0, 1));
        }
        if (horizontalCollision) {
            speed *= 0.6;
        }
        if (isInWater()) {
            speed *= 0.92;
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
            loadBelt(player, stack);
        } else if (stack.getItem() == ModItems.ATAKA_MISSILE.get()) {
            if (getAmmo2() >= maxAmmo2()) {
                player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.ammo_full"), true);
            } else {
                setAmmo2(getAmmo2() + 1);
                if (!player.abilities.instabuild) {
                    stack.shrink(1);
                }
                level.playSound(null, getX(), getY(), getZ(), ModSounds.RELOAD.get(), SoundCategory.BLOCKS, 1F, 0.7F);
                player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.missiles", getAmmo2(), maxAmmo2()), true);
            }
        } else if (stack.isEmpty() && player.isShiftKeyDown() && getPassengers().isEmpty()) {
            pickUp(player);
        } else if (canAddPassenger(player)) {
            if (owner == null) {
                owner = player.getUUID();
            }
            player.startRiding(this);
        } else {
            return ActionResultType.PASS;
        }
        return ActionResultType.CONSUME;
    }

    private void loadBelt(PlayerEntity player, ItemStack stack) {
        if (getAmmo() + AirDefenseGunEntity.ROUNDS_PER_BELT > maxAmmo()) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.ammo_full"), true);
            return;
        }
        setAmmo(getAmmo() + AirDefenseGunEntity.ROUNDS_PER_BELT);
        if (!player.abilities.instabuild) {
            stack.shrink(1);
        }
        level.playSound(null, getX(), getY(), getZ(), ModSounds.RELOAD.get(), SoundCategory.BLOCKS, 1F, 1F);
        player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.ammo", getAmmo(), maxAmmo()), true);
    }

    private void pickUp(PlayerEntity player) {
        giveOrDrop(player, new ItemStack(ModItems.TERMINATOR.get()));
        int belts = getAmmo() / AirDefenseGunEntity.ROUNDS_PER_BELT;
        if (belts > 0) {
            giveOrDrop(player, new ItemStack(ModItems.AMMO_30MM.get(), belts));
        }
        if (getAmmo2() > 0) {
            giveOrDrop(player, new ItemStack(ModItems.ATAKA_MISSILE.get(), getAmmo2()));
        }
        remove();
    }

    private static void giveOrDrop(PlayerEntity player, ItemStack stack) {
        if (!player.inventory.add(stack)) {
            player.drop(stack, false);
        }
    }

    @Override
    protected void destroy(@Nullable DamageSource cause) {
        Vector3d c = position().add(0, 1.5, 0);
        int missiles = getAmmo2();
        ejectPassengers();
        remove();
        if (cause != null) {
            // ammo cook-off
            Explosions.strike(level, null, c, 4F + missiles * 1.5F + getAmmo() / 300F, true);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        super.readAdditionalSaveData(tag);
        speed = 0;
    }
}
