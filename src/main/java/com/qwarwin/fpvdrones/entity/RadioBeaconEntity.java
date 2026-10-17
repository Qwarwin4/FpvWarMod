package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.registry.ModSounds;
import com.qwarwin.fpvdrones.util.ReconData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.particles.RedstoneParticleData;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dropped radio beacon. Strike drones launched with its code steer onto it the whole way,
 * and if it landed on something that moves, it rides along with it.
 */
public class RadioBeaconEntity extends Entity {
    private static final DataParameter<Integer> CODE = EntityDataManager.defineId(RadioBeaconEntity.class, DataSerializers.INT);
    private static final int LIFETIME = 20 * 60 * 20;
    private static final Map<Integer, UUID> ACTIVE = new HashMap<>();

    @Nullable
    private UUID owner;
    @Nullable
    private UUID attached;
    private Vector3d attachOffset = Vector3d.ZERO;
    private int life;

    public RadioBeaconEntity(EntityType<? extends RadioBeaconEntity> type, World world) {
        super(type, world);
    }

    public void setup(int code, @Nullable UUID owner, @Nullable Entity stuckTo) {
        entityData.set(CODE, code);
        this.owner = owner;
        if (stuckTo != null) {
            attached = stuckTo.getUUID();
            attachOffset = position().subtract(stuckTo.position());
        }
    }

    public int getCode() {
        return entityData.get(CODE);
    }

    /** Current position of the beacon with this code, null if it's gone. */
    @Nullable
    public static Vector3d locate(ServerWorld world, int code) {
        UUID id = ACTIVE.get(code);
        Entity e = id == null ? null : world.getEntity(id);
        return e instanceof RadioBeaconEntity && e.isAlive() ? e.position() : null;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(CODE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide) {
            if (tickCount % 20 < 3) {
                level.addParticle(new RedstoneParticleData(1F, 0.1F, 0.1F, 0.7F), getX(), getY() + 0.25, getZ(), 0, 0, 0);
            }
            return;
        }
        ACTIVE.put(getCode(), getUUID());
        if (++life > LIFETIME) {
            remove();
            return;
        }
        Entity host = attached == null ? null : ((ServerWorld) level).getEntity(attached);
        if (host != null && host.isAlive()) {
            Vector3d p = host.position().add(attachOffset);
            setPos(p.x, p.y, p.z);
            setDeltaMovement(Vector3d.ZERO);
        } else {
            attached = null;
            Vector3d v = getDeltaMovement().add(0, -0.06, 0).scale(0.98);
            move(MoverType.SELF, v);
            setDeltaMovement(isOnGround() ? Vector3d.ZERO : v);
        }
        if (life % 40 == 0) {
            level.playSound(null, getX(), getY(), getZ(), ModSounds.BEEP.get(), SoundCategory.NEUTRAL, 0.15F, 2.0F);
        }
    }

    @Override
    public void remove(boolean keepData) {
        if (!level.isClientSide && !keepData && getUUID().equals(ACTIVE.get(getCode()))) {
            ACTIVE.remove(getCode());
        }
        super.remove(keepData);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!level.isClientSide && isAlive() && !isInvulnerableTo(source)) {
            remove();
        }
        return true;
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND || !player.isShiftKeyDown()) {
            return ActionResultType.PASS;
        }
        if (!level.isClientSide) {
            ItemStack s = new ItemStack(ModItems.RADIO_BEACON.get());
            if (!player.inventory.add(s)) {
                player.drop(s, false);
            }
            remove();
        }
        return ActionResultType.sidedSuccess(level.isClientSide);
    }

    /** Remember the drop point for the beacon's owner so the launcher menu can use it. */
    public void reportToOwner() {
        if (owner == null || level.getServer() == null) {
            return;
        }
        PlayerEntity p = level.getServer().getPlayerList().getPlayer(owner);
        if (p != null) {
            ReconData.set(p, new BlockPos(getX(), getY(), getZ()), getCode());
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.beacon_landed", getCode(),
                    (int) Math.floor(getX()) + " " + (int) Math.floor(getY()) + " " + (int) Math.floor(getZ())), false);
        }
    }

    @Override
    public boolean isPickable() {
        return isAlive();
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        entityData.set(CODE, tag.getInt("Code"));
        life = tag.getInt("Life");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        attached = tag.hasUUID("Attached") ? tag.getUUID("Attached") : null;
        attachOffset = new Vector3d(tag.getDouble("OX"), tag.getDouble("OY"), tag.getDouble("OZ"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        tag.putInt("Code", getCode());
        tag.putInt("Life", life);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (attached != null) {
            tag.putUUID("Attached", attached);
        }
        tag.putDouble("OX", attachOffset.x);
        tag.putDouble("OY", attachOffset.y);
        tag.putDouble("OZ", attachOffset.z);
    }

    @Override
    public IPacket<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
