package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.client.ClientHooks;
import com.qwarwin.fpvdrones.item.TabletItem;
import com.qwarwin.fpvdrones.session.SessionManager;
import com.qwarwin.fpvdrones.util.ChunkTickets;
import com.qwarwin.fpvdrones.util.Explosions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntitySize;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.Pose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shahed / Geran. Boost off the rail, cruise at a fixed height while hopping over terrain to its own
 * entry point, then dive onto the target (or onto a radio beacon). Keeps the chunks ahead loaded by itself.
 */
public class StrikeDroneEntity extends Entity implements IEntityAdditionalSpawnData {
    public static final int BOOST = 0, CRUISE = 1, DIVE = 2;
    public static final double CENTER_HEIGHT = 0.4;

    private static final DataParameter<Integer> PHASE = EntityDataManager.defineId(StrikeDroneEntity.class, DataSerializers.INT);
    private static final DataParameter<Float> HEALTH = EntityDataManager.defineId(StrikeDroneEntity.class, DataSerializers.FLOAT);
    private static final float RAD2DEG = (float) (180.0 / Math.PI);

    private StrikeType type = StrikeType.SHAHED_136;
    private double tx, ty, tz;
    private boolean autoY;
    private double cruiseY = 100;
    private int boostTicks;
    private int life;
    private int maxLife = 20 * 60 * 20;
    private double terrainAhead = -999;
    @Nullable
    private UUID owner;
    // which way it comes in from, so a salvo fans out instead of flying into each other
    private float approachYaw;
    private double approachRadius = 60;
    private int beacon;
    private long blastShieldUntil;

    private static final Map<UUID, StrikeDroneEntity> FLYING = new HashMap<>();

    public float roll, rollO, prop, propO;
    private float lastYaw;
    private boolean soundStarted;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot, lerpXRot;

    public StrikeDroneEntity(EntityType<? extends StrikeDroneEntity> type, World world) {
        super(type, world);
    }

    public void setup(StrikeType type, Vector3d target, boolean autoY, int beacon, double launchY, @Nullable UUID owner) {
        this.type = type;
        this.owner = owner;
        this.beacon = beacon;
        this.tx = target.x;
        this.tz = target.z;
        this.autoY = autoY && beacon == 0;
        this.ty = this.autoY ? launchY : target.y;
        entityData.set(HEALTH, type.getHealth());

        // every drone already heading for (roughly) the same point gets its own sector and altitude
        int index = 0;
        for (StrikeDroneEntity other : flyingIn(level)) {
            if (other != this && Math.abs(other.tx - tx) < 24 && Math.abs(other.tz - tz) < 24) {
                index++;
            }
        }
        double base = this.autoY ? launchY : Math.max(launchY, ty);
        cruiseY = Math.min(250, base + Config.CRUISE_ALTITUDE.get() + index * 6);
        float fromLaunch = (float) (MathHelper.atan2(getZ() - tz, getX() - tx) * RAD2DEG);
        int side = index % 2 == 1 ? 1 : -1;
        approachYaw = fromLaunch + side * ((index + 1) / 2) * 50F;
        approachRadius = (type.isJet() ? 90 : 60) + index * 10;

        double dx = tx - getX(), dz = tz - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        maxLife = (int) ((dist + approachRadius * 2) / type.speed() * 1.6) + 1200;
    }

    private double approachX() {
        return tx + 0.5 + Math.cos(approachYaw / RAD2DEG) * approachRadius;
    }

    private double approachZ() {
        return tz + 0.5 + Math.sin(approachYaw / RAD2DEG) * approachRadius;
    }

    /** Drones in the air in this world. Drops the ones whose chunk got unloaded under them. */
    private static List<StrikeDroneEntity> flyingIn(World world) {
        List<StrikeDroneEntity> list = new ArrayList<>();
        Iterator<StrikeDroneEntity> it = FLYING.values().iterator();
        while (it.hasNext()) {
            StrikeDroneEntity e = it.next();
            if (!e.isAlive() || !(e.level instanceof ServerWorld) || ((ServerWorld) e.level).getEntity(e.getUUID()) != e) {
                it.remove();
            } else if (e.level == world) {
                list.add(e);
            }
        }
        return list;
    }

    /** Called right before a strike drone blows up so the blast doesn't take out its neighbours. */
    public static void shieldNear(World world, Vector3d pos, double radius) {
        for (StrikeDroneEntity e : flyingIn(world)) {
            if (e.position().distanceToSqr(pos) < radius * radius) {
                e.blastShieldUntil = world.getGameTime() + 3;
            }
        }
    }

    public StrikeType getStrikeType() {
        return type;
    }

    public Vector3d getTarget() {
        return new Vector3d(tx, ty, tz);
    }

    public int getPhase() {
        return entityData.get(PHASE);
    }

    private void setPhase(int p) {
        entityData.set(PHASE, p);
    }

    public int getLife() {
        return life;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(PHASE, BOOST);
        entityData.define(HEALTH, 14F);
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
            lastYaw = yRot;
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.onStrikeDroneSeen(this));
        }
        life++;
        if (lerpSteps > 0) {
            double nx = getX() + (lerpX - getX()) / lerpSteps;
            double ny = getY() + (lerpY - getY()) / lerpSteps;
            double nz = getZ() + (lerpZ - getZ()) / lerpSteps;
            yRot += MathHelper.wrapDegrees(lerpYRot - yRot) / lerpSteps;
            xRot += (lerpXRot - xRot) / lerpSteps;
            --lerpSteps;
            setPos(nx, ny, nz);
        }
        rollO = roll;
        propO = prop;
        float dyaw = MathHelper.wrapDegrees(yRot - lastYaw);
        lastYaw = yRot;
        roll += (MathHelper.clamp(-dyaw * 9F, -45F, 45F) - roll) * 0.15F;
        prop += 75F;
        if (prop > 3600F) {
            prop -= 3600F;
            propO -= 3600F;
        }

        Vector3d fwd = calculateViewVector(xRot, yRot);
        Vector3d rear = position().add(0, CENTER_HEIGHT, 0).subtract(fwd.scale(1.9));
        if (getPhase() == BOOST) {
            for (int i = 0; i < 3; i++) {
                level.addParticle(ParticleTypes.FLAME, rear.x, rear.y, rear.z,
                        -fwd.x * 0.3 + random.nextGaussian() * 0.03, -fwd.y * 0.3, -fwd.z * 0.3 + random.nextGaussian() * 0.03);
                level.addParticle(ParticleTypes.LARGE_SMOKE, rear.x, rear.y, rear.z, -fwd.x * 0.1, 0.02, -fwd.z * 0.1);
            }
        } else if (type.isJet()) {
            Vector3d nozzle = rear.add(0, 0.35, 0);
            level.addParticle(ParticleTypes.FLAME, nozzle.x, nozzle.y, nozzle.z, -fwd.x * 0.2, -fwd.y * 0.2, -fwd.z * 0.2);
            if (tickCount % 2 == 0) {
                level.addParticle(ParticleTypes.SMOKE, nozzle.x, nozzle.y, nozzle.z, 0, 0.01, 0);
            }
        } else if (tickCount % 2 == 0) {
            level.addParticle(ParticleTypes.SMOKE, rear.x, rear.y, rear.z, 0, 0.01, 0);
        }
    }

    private void serverTick() {
        ServerWorld sw = (ServerWorld) level;
        FLYING.put(getUUID(), this);
        life++;
        if (beacon != 0 && life % 10 == 0) {
            Vector3d b = RadioBeaconEntity.locate(sw, beacon);
            if (b != null) {
                tx = b.x - 0.5;
                ty = b.y;
                tz = b.z - 0.5;
            }
        }
        Vector3d vel = getDeltaMovement();
        if (life % 4 == 1) {
            // generating fresh terrain takes a moment, so ask for it a few seconds before arriving
            ChunkTickets.follow(sw, this, vel, 80);
        }
        if (life % 20 == 3 && ChunkTickets.watched(sw, this)) {
            // someone flies it from the tablet: a wide swath ahead, not just the strip under it
            ChunkTickets.preload(sw, this, vel, 12, 2);
        }

        double speed = type.speed();
        Vector3d v;
        switch (getPhase()) {
            case BOOST: {
                boostTicks++;
                double s = Math.min(speed, 0.35 + boostTicks * (type.isJet() ? 0.1 : 0.06));
                v = calculateViewVector(xRot, yRot).scale(s);
                if (boostTicks >= 25) {
                    setPhase(CRUISE);
                }
                break;
            }
            case CRUISE: {
                // fly to this drone's entry point first, then turn in on the target
                double ax = approachX() - getX(), az = approachZ() - getZ();
                double toEntry = Math.sqrt(ax * ax + az * az);
                double dx = tx + 0.5 - getX(), dz = tz + 0.5 - getZ();
                double hd = Math.sqrt(dx * dx + dz * dz);
                yRot = approachDeg(yRot, (float) (MathHelper.atan2(az, ax) * RAD2DEG) - 90F, type.isJet() ? 2.5F : 3.5F);
                if (life % 10 == 0) {
                    sampleTerrain();
                }
                double desiredY = Math.min(250, Math.max(cruiseY, terrainAhead + 18));
                xRot = approach(xRot, (float) -MathHelper.clamp((desiredY - getY()) * 1.5, -20, 25), 1.5F);
                if (autoY) {
                    int h = heightAt(tx, tz);
                    if (h != Integer.MIN_VALUE) {
                        ty = h;
                    }
                }
                if (toEntry < (type.isJet() ? 20 : 12) || hd < approachRadius * 0.6) {
                    setPhase(DIVE);
                    level.playSound(null, getX(), getY(), getZ(), SoundEvents.PHANTOM_SWOOP, SoundCategory.HOSTILE, 4.0F, 0.6F);
                }
                v = calculateViewVector(xRot, yRot).scale(speed);
                break;
            }
            default: {
                Vector3d to = new Vector3d(tx + 0.5, ty + 0.3, tz + 0.5).subtract(position());
                double hd = Math.sqrt(to.x * to.x + to.z * to.z);
                yRot = approachDeg(yRot, (float) (MathHelper.atan2(to.z, to.x) * RAD2DEG) - 90F, 10F);
                xRot = approach(xRot, (float) (-MathHelper.atan2(to.y, hd) * RAD2DEG), 6F);
                v = calculateViewVector(xRot, yRot).scale(speed * 1.35);
                if (to.length() < 1.6) {
                    explode(1F);
                    return;
                }
                break;
            }
        }

        // let it clear the rail / truck before collisions count
        noPhysics = life < 10;
        setDeltaMovement(v);
        move(MoverType.SELF, v);

        if (!noPhysics && (horizontalCollision || verticalCollision)) {
            explode(1F);
            return;
        }
        if (life > 10 && hitsSomething()) {
            explode(1F);
            return;
        }
        if (life > maxLife) {
            explode(1F);
            return;
        }
        if (getY() < -64) {
            SessionManager.endAllFor(this, "reason.fpvdrones.target_lost");
            remove();
        }
    }

    private boolean hitsSomething() {
        return !level.getEntities(this, getBoundingBox().inflate(0.2), this::isTargetable).isEmpty();
    }

    private boolean isTargetable(Entity e) {
        if (!e.isAlive() || e.isSpectator()) {
            return false;
        }
        if (e instanceof StrikeDroneEntity) {
            return false;
        }
        if (e instanceof LaunchTruckEntity) {
            return life > 60;
        }
        if (e instanceof OperatorEntity) {
            return life > 40;
        }
        return e instanceof LivingEntity || e instanceof AbstractDroneEntity;
    }

    private void sampleTerrain() {
        Vector3d fwd = calculateViewVector(0F, yRot);
        int best = Integer.MIN_VALUE;
        int reach = type.isJet() ? 2 : 1;
        for (int d : new int[]{12, 28, 48}) {
            best = Math.max(best, heightAt(getX() + fwd.x * d * reach, getZ() + fwd.z * d * reach));
        }
        if (best != Integer.MIN_VALUE) {
            terrainAhead = best;
        }
    }

    private int heightAt(double x, double z) {
        int bx = MathHelper.floor(x), bz = MathHelper.floor(z);
        Chunk ch = ((ServerWorld) level).getChunkSource().getChunk(bx >> 4, bz >> 4, false);
        if (ch == null) {
            return Integer.MIN_VALUE;
        }
        return ch.getHeight(Heightmap.Type.MOTION_BLOCKING, bx & 15, bz & 15);
    }

    private static float approachDeg(float cur, float target, float max) {
        return cur + MathHelper.clamp(MathHelper.wrapDegrees(target - cur), -max, max);
    }

    private static float approach(float cur, float target, float max) {
        return cur + MathHelper.clamp(target - cur, -max, max);
    }

    public void explode(float mul) {
        if (level.isClientSide || !isAlive()) {
            return;
        }
        SessionManager.endAllFor(this, "reason.fpvdrones.target_lost");
        Vector3d pos = position().add(0, CENTER_HEIGHT, 0);
        remove();
        shieldNear(level, pos, 24);
        Explosions.strike(level, owner, pos, type.power() * mul, Config.STRIKE_FIRE.get());
    }

    @Override
    public void remove(boolean keepData) {
        FLYING.remove(getUUID());
        super.remove(keepData);
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    public void primeChunks(ServerWorld world) {
        ChunkTickets.follow(world, this, calculateViewVector(xRot, yRot).scale(type.speed()), 80);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) {
            return false;
        }
        if (level.isClientSide) {
            return true;
        }
        if (source.isExplosion() && level.getGameTime() <= blastShieldUntil) {
            return false;
        }
        float h = entityData.get(HEALTH) - amount;
        entityData.set(HEALTH, h);
        if (h <= 0F) {
            explode(0.6F);
        }
        return true;
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof TabletItem) || !player.isShiftKeyDown()) {
            return ActionResultType.PASS;
        }
        if (!level.isClientSide) {
            TabletItem.bindFlying(stack, getUUID());
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.bound_tablet_flying"), true);
        }
        return ActionResultType.sidedSuccess(level.isClientSide);
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
    protected float getEyeHeight(Pose pose, EntitySize size) {
        return 0.45F;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSqr) {
        return distSqr < 512.0 * 512.0;
    }

    @Override
    public AxisAlignedBB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(1.5);
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        lerpX = x;
        lerpY = y;
        lerpZ = z;
        lerpYRot = yRot;
        lerpXRot = xRot;
        lerpSteps = 3;
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        type = StrikeType.byId(tag.getInt("Type"));
        tx = tag.getDouble("TX");
        ty = tag.getDouble("TY");
        tz = tag.getDouble("TZ");
        autoY = tag.getBoolean("AutoY");
        cruiseY = tag.getDouble("CruiseY");
        boostTicks = tag.getInt("Boost");
        life = tag.getInt("Life");
        if (tag.contains("MaxLife")) {
            maxLife = tag.getInt("MaxLife");
        }
        setPhase(tag.getInt("Phase"));
        if (tag.contains("Health")) {
            entityData.set(HEALTH, tag.getFloat("Health"));
        }
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        approachYaw = tag.getFloat("ApproachYaw");
        if (tag.contains("ApproachR")) {
            approachRadius = tag.getDouble("ApproachR");
        }
        beacon = tag.getInt("Beacon");
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        tag.putInt("Type", type.ordinal());
        tag.putDouble("TX", tx);
        tag.putDouble("TY", ty);
        tag.putDouble("TZ", tz);
        tag.putBoolean("AutoY", autoY);
        tag.putDouble("CruiseY", cruiseY);
        tag.putInt("Boost", boostTicks);
        tag.putInt("Life", life);
        tag.putInt("MaxLife", maxLife);
        tag.putInt("Phase", getPhase());
        tag.putFloat("Health", entityData.get(HEALTH));
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.putFloat("ApproachYaw", approachYaw);
        tag.putDouble("ApproachR", approachRadius);
        tag.putInt("Beacon", beacon);
    }

    @Override
    public void writeSpawnData(PacketBuffer buf) {
        buf.writeByte(type.ordinal());
        buf.writeDouble(tx);
        buf.writeDouble(ty);
        buf.writeDouble(tz);
    }

    @Override
    public void readSpawnData(PacketBuffer buf) {
        type = StrikeType.byId(buf.readByte());
        tx = buf.readDouble();
        ty = buf.readDouble();
        tz = buf.readDouble();
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
