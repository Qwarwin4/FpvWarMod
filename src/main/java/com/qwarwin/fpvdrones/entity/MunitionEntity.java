package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.item.PayloadType;
import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.util.Explosions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MoverType;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** Something dropped from a Mavic. VOG goes off on impact, RGD-5 has a ~3.5 s fuse, a radio beacon just lands. */
public class MunitionEntity extends Entity {
    private static final DataParameter<Integer> TYPE = EntityDataManager.defineId(MunitionEntity.class, DataSerializers.INT);

    private int fuse = 400;
    private int beaconCode;
    @Nullable
    private UUID owner;

    public float spin, spinO;

    public MunitionEntity(EntityType<? extends MunitionEntity> type, World world) {
        super(type, world);
    }

    public void setup(PayloadType type, @Nullable UUID owner) {
        entityData.set(TYPE, type.ordinal());
        fuse = type == PayloadType.RGD5 ? 70 : 400;
        this.owner = owner;
    }

    public void setBeaconCode(int code) {
        beaconCode = code;
    }

    public PayloadType getPayloadType() {
        return PayloadType.byId(entityData.get(TYPE));
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(TYPE, PayloadType.VOG17M.ordinal());
    }

    @Override
    public void tick() {
        super.tick();
        Vector3d v = getDeltaMovement().add(0, -0.06, 0).scale(0.99);
        setDeltaMovement(v);
        move(MoverType.SELF, v);
        PayloadType type = getPayloadType();

        if (level.isClientSide) {
            spinO = spin;
            spin += type == PayloadType.RGD5 ? 25F : 6F;
            if (type == PayloadType.RGD5 && tickCount % 3 == 0) {
                level.addParticle(ParticleTypes.SMOKE, getX(), getY() + 0.15, getZ(), 0, 0.02, 0);
            }
            return;
        }

        if (type == PayloadType.RGD5) {
            if (verticalCollision) {
                setDeltaMovement(v.x * 0.4, -v.y * 0.25, v.z * 0.4);
                if (Math.abs(v.y) > 0.15) {
                    level.playSound(null, getX(), getY(), getZ(), SoundEvents.CHAIN_HIT, SoundCategory.NEUTRAL, 0.6F, 1.4F);
                }
            } else if (horizontalCollision) {
                setDeltaMovement(-v.x * 0.3, v.y, -v.z * 0.3);
            }
            if (--fuse <= 0) {
                explode();
            }
            return;
        }
        if (type == PayloadType.RADIO_BEACON) {
            List<Entity> hit = level.getEntities(this, getBoundingBox().inflate(0.15),
                    e -> e.isAlive() && !e.isSpectator() && (e instanceof LivingEntity || e instanceof LaunchTruckEntity));
            if (horizontalCollision || verticalCollision || !hit.isEmpty() || --fuse <= 0) {
                plantBeacon(hit.isEmpty() ? null : hit.get(0));
            }
            return;
        }
        boolean hitEntity = !level.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(0.1),
                e -> e.isAlive() && !e.isSpectator()).isEmpty();
        if (horizontalCollision || verticalCollision || hitEntity || --fuse <= 0) {
            explode();
        }
    }

    private void plantBeacon(@Nullable Entity stuckTo) {
        RadioBeaconEntity beacon = ModEntities.RADIO_BEACON.get().create(level);
        remove();
        if (beacon == null) {
            return;
        }
        beacon.moveTo(getX(), getY(), getZ(), yRot, 0F);
        beacon.setup(beaconCode, owner, stuckTo);
        level.addFreshEntity(beacon);
        beacon.reportToOwner();
    }

    private void explode() {
        if (!isAlive()) {
            return;
        }
        Vector3d pos = position().add(0, 0.1, 0);
        PayloadType type = getPayloadType();
        remove();
        Explosions.payload(level, owner, pos, type, new Vector3d(0, -1, 0));
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        entityData.set(TYPE, tag.getInt("Type"));
        fuse = tag.getInt("Fuse");
        beaconCode = tag.getInt("Beacon");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        tag.putInt("Type", entityData.get(TYPE));
        tag.putInt("Fuse", fuse);
        tag.putInt("Beacon", beaconCode);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSqr) {
        return distSqr < 128.0 * 128.0;
    }
}
