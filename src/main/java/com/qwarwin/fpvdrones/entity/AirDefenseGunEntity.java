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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;

/**
 * ZAK-30 "Tsitadel": automatic 30 mm anti-drone turret on a pedestal, with its own surveillance radar and TV/IR head.
 * The fire control works out where each round meets the target and programs the fuse to burst there in a cloud
 * of fragments. Its radar sees a Shahed out to {@link #AUTO_RANGE} blocks, a quadcopter much closer.
 */
public class AirDefenseGunEntity extends MountedWeaponEntity {
    public static final Vector3d PIVOT = new Vector3d(0, 1.15, 0);
    public static final Vector3d TRUNNION = new Vector3d(-0.05, 0.42, 0.15);
    public static final double MUZZLE = 2.55;
    public static final int ROUNDS_PER_BELT = 50;
    private static final double AUTO_RANGE = 450.0;
    private static final double ALL_RANGE = 450.0;
    /** How wide around the crosshair the gunner's laser rangefinder picks a target up, degrees. */
    private static final double RANGEFINDER_CONE = 3.0;

    /** Manual, auto against other people's drones, auto against anything in the air. */
    public static final int MANUAL = 0, AUTO = 1, ALL = 2;
    private static final String[] MODE_MSG = {"msg.fpvdrones.adgun_auto_off", "msg.fpvdrones.adgun_auto_on",
            "msg.fpvdrones.adgun_all_on"};

    private static final DataParameter<Integer> MODE = EntityDataManager.defineId(AirDefenseGunEntity.class, DataSerializers.INT);

    private int targetId = -1;
    private int autoFireCooldown;
    /** ticks since the current target was picked up: the fire control needs a moment to build a track */
    private int trackTicks;
    /** slowly wandering tracking error, degrees */
    private float errYaw, errPitch;

    public AirDefenseGunEntity(EntityType<? extends AirDefenseGunEntity> type, World world) {
        super(type, world);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(MODE, MANUAL);
    }

    public int getMode() {
        return entityData.get(MODE);
    }

    private void setMode(int m) {
        entityData.set(MODE, Math.floorMod(m, 3));
    }

    @Override
    public float getMaxHealth() {
        return 120F;
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
        return 2;
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
        // barrel whip and mount vibration in a burst
        float spread = 0.6F;
        Vector3d dir = Vector3d.directionFromRotation(getGunPitch() + (random.nextFloat() - 0.5F) * spread,
                getTurretYaw() + (random.nextFloat() - 0.5F) * spread);
        Entity gunner = getGunner();
        shell.setup(this, gunner != null ? gunner.getUUID() : owner, muzzle, dir.scale(ShellEntity.SPEED), true);
        shell.setFuseAll(getMode() == ALL);
        double fuse = fuseDistance(muzzle, gunner);
        if (fuse > 0) {
            // the fuse setter isn't perfect either
            shell.programBurst(fuse * (1 + (random.nextDouble() - 0.5) * 0.02));
        }
        level.addFreshEntity(shell);
        setAmmo(getAmmo() - 1);
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z, ModSounds.CANNON_30.get(), SoundCategory.HOSTILE, 5F,
                0.95F + random.nextFloat() * 0.1F);
        ((ServerWorld) level).sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 3, 0.05, 0.05, 0.05, 0.05);
        ((ServerWorld) level).sendParticles(ParticleTypes.POOF, muzzle.x, muzzle.y, muzzle.z, 2, 0.1, 0.1, 0.1, 0.02);
    }

    /** Distance to program into the fuse: to the tracked target's intercept point, 0 when nothing is tracked. */
    private double fuseDistance(Vector3d muzzle, @Nullable Entity gunner) {
        Entity target = null;
        if (gunner == null) {
            target = targetId < 0 ? null : level.getEntity(targetId);
        } else {
            // laser rangefinder: whatever flies closest to the line of the barrel
            Vector3d dir = Vector3d.directionFromRotation(getGunPitch(), getTurretYaw());
            double best = Math.cos(Math.toRadians(RANGEFINDER_CONE));
            boolean all = getMode() == ALL;
            for (Entity e : level.getEntities(this, new AxisAlignedBB(muzzle, muzzle).inflate(range()),
                    all ? Targeting::isAirborne : Targeting::isAirTarget)) {
                Vector3d to = e.getBoundingBox().getCenter().subtract(muzzle);
                double c = to.normalize().dot(dir);
                if (c > best && to.length() < range() && !hasPassenger(e)) {
                    best = c;
                    target = e;
                }
            }
        }
        if (target == null || !target.isAlive()) {
            return 0;
        }
        return Targeting.lead(muzzle, target, ShellEntity.SPEED, ShellEntity.GRAVITY, ShellEntity.DRAG).distanceTo(muzzle);
    }

    @Override
    protected void onAction(ServerPlayerEntity gunner) {
        cycleMode(gunner);
    }

    private void cycleMode(PlayerEntity player) {
        setMode(getMode() + 1);
        int m = getMode();
        player.displayClientMessage(new TranslationTextComponent(MODE_MSG[m])
                .withStyle(m == MANUAL ? TextFormatting.GRAY : m == AUTO ? TextFormatting.GOLD : TextFormatting.RED), true);
        level.playSound(null, getX(), getY(), getZ(), ModSounds.BEEP.get(), SoundCategory.BLOCKS, 0.8F, 0.8F + m * 0.3F);
        targetId = -1;
    }

    // ---- automatic mode

    @Override
    protected void idleAim() {
        if (getMode() == MANUAL || getAmmo() <= 0) {
            targetId = -1;
            return;
        }
        Vector3d eye = pivotWorld().add(0, 0.5, 0);
        Entity target = targetId < 0 ? null : level.getEntity(targetId);
        if (target == null || !target.isAlive() || tickCount % 10 == 0 && !isValidTarget(target, eye)) {
            target = tickCount % 4 == 0 ? findTarget(eye) : null;
            int id = target == null ? -1 : target.getId();
            if (id != targetId) {
                trackTicks = 0;
            }
            targetId = id;
        }
        if (target == null) {
            return;
        }
        trackTicks++;
        // radar plus the TV/IR tracker: the error wanders more against small, jinking targets and settles as the
        // track matures
        double size = Math.max(0.3, target.getBbWidth());
        float wander = (float) (0.9 / size) * (trackTicks < 40 ? 1.0F : 0.6F);
        errYaw = MathHelper.clamp(errYaw * 0.9F + (random.nextFloat() - 0.5F) * wander, -3F * wander, 3F * wander);
        errPitch = MathHelper.clamp(errPitch * 0.9F + (random.nextFloat() - 0.5F) * wander, -3F * wander, 3F * wander);
        Vector3d muzzle = gunToWorld(TRUNNION, new Vector3d(0, 0, MUZZLE));
        Vector3d aim = Targeting.lead(muzzle, target, ShellEntity.SPEED, ShellEntity.GRAVITY, ShellEntity.DRAG).subtract(muzzle);
        float err = slewTo(Targeting.yawTo(aim) + errYaw, Targeting.pitchTo(aim) + errPitch);
        if (autoFireCooldown > 0) {
            autoFireCooldown--;
        } else if (err < 2.5F && trackTicks > 15) {
            firePrimary();
            autoFireCooldown = primaryInterval();
        }
    }

    private double range() {
        return getMode() == ALL ? ALL_RANGE : AUTO_RANGE;
    }

    private boolean isValidTarget(Entity e, Vector3d eye) {
        double r = Targeting.detectRange(level, e, range());
        if (!e.isAlive() || e.distanceToSqr(eye) > r * r) {
            return false;
        }
        if (getMode() == ALL) {
            // everything up there except the owner himself
            return Targeting.isAirborne(e) && !e.getUUID().equals(owner) && Targeting.canSee(level, eye, e);
        }
        if (!Targeting.isAirTarget(e) || e instanceof AbstractDroneEntity && !((AbstractDroneEntity) e).isPowered()) {
            return false;
        }
        return !Targeting.isFriendly(level, owner, Targeting.ownerOf(e)) && Targeting.canSee(level, eye, e);
    }

    /** Incoming missiles first, then Shaheds, then drones, then whatever else flies. */
    private static double priority(Entity e) {
        if (e instanceof MissileEntity) {
            return 0.25;
        }
        if (e instanceof StrikeDroneEntity) {
            return 0.5;
        }
        return e instanceof AbstractDroneEntity ? 0.75 : 1.0;
    }

    @Nullable
    private Entity findTarget(Vector3d eye) {
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        boolean all = getMode() == ALL;
        for (Entity e : level.getEntities(this, new AxisAlignedBB(eye, eye).inflate(range()),
                all ? Targeting::isAirborne : Targeting::isAirTarget)) {
            double score = e.distanceToSqr(eye) * priority(e);
            if (score < bestScore && isValidTarget(e, eye) && random.nextDouble() < Targeting.scanChance(e)) {
                bestScore = score;
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
            cycleMode(player);
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
        setMode(tag.contains("Mode") ? tag.getInt("Mode") : tag.getBoolean("Auto") ? AUTO : MANUAL);
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Mode", getMode());
    }
}
