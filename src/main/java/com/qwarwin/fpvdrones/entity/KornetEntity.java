package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.registry.ModSounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;

/**
 * "Kornet" on its tripod. Laser beam-riding: after launch keep the crosshair on the target until it hits.
 * Takes one missile at a time; with spares in the gunner's inventory it reloads itself.
 */
public class KornetEntity extends MountedWeaponEntity {
    public static final Vector3d PIVOT = new Vector3d(0, 0.78, 0);
    public static final Vector3d TRUNNION = new Vector3d(0, 0.14, 0);
    public static final double TUBE_FRONT = 0.75;
    private static final int RELOAD_TICKS = 50;

    private int reload;

    public KornetEntity(EntityType<? extends KornetEntity> type, World world) {
        super(type, world);
    }

    @Override
    public float getMaxHealth() {
        return 24F;
    }

    @Override
    protected float damageFactor(DamageSource source) {
        return 1F;
    }

    @Override
    protected Vector3d turretPivot() {
        return PIVOT;
    }

    @Override
    protected Vector3d sightOffset() {
        // straight above the tube, so the crosshair and the missile's line agree
        return new Vector3d(0, 0.42, -0.25);
    }

    @Override
    protected float traverseSpeed() {
        // light launcher on a tripod head: follows the eye, the sight is what matters
        return 25F;
    }

    @Override
    protected float minPitch() {
        return -20F;
    }

    @Override
    protected float maxPitch() {
        return 12F;
    }

    @Override
    protected int primaryInterval() {
        return 20;
    }

    @Override
    public int maxAmmo() {
        return 1;
    }

    public int getReload() {
        return reload;
    }

    @Override
    protected void firePrimary() {
        Entity gunner = getGunner();
        MissileEntity m = ModEntities.MISSILE.get().create(level);
        if (m == null || gunner == null) {
            return;
        }
        Vector3d pos = gunToWorld(TRUNNION, new Vector3d(0, 0, TUBE_FRONT + 0.2));
        m.setup(MissileType.KORNET, gunner.getUUID(), this, pos, aimVector());
        level.addFreshEntity(m);
        setAmmo(0);
        reload = RELOAD_TICKS;
        level.playSound(null, pos.x, pos.y, pos.z, ModSounds.ATGM_LAUNCH.get(), SoundCategory.HOSTILE, 5F, 1F);
        Vector3d back = gunToWorld(TRUNNION, new Vector3d(0, 0, -0.6));
        ((ServerWorld) level).sendParticles(ParticleTypes.CLOUD, back.x, back.y, back.z, 10, 0.2, 0.2, 0.2, 0.08);
        ((ServerWorld) level).sendParticles(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, 6, 0.2, 0.2, 0.2, 0.05);
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide || getAmmo() > 0 || reload <= 0 || --reload > 0) {
            return;
        }
        Entity gunner = getGunner();
        if (gunner instanceof PlayerEntity && takeMissile((PlayerEntity) gunner)) {
            setAmmo(1);
            level.playSound(null, getX(), getY(), getZ(), ModSounds.RELOAD.get(), SoundCategory.BLOCKS, 1F, 0.8F);
        }
    }

    private static boolean takeMissile(PlayerEntity player) {
        if (player.abilities.instabuild) {
            return true;
        }
        for (ItemStack s : player.inventory.items) {
            if (s.getItem() == ModItems.KORNET_MISSILE.get()) {
                s.shrink(1);
                return true;
            }
        }
        return false;
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty() && hand != Hand.MAIN_HAND) {
            return ActionResultType.PASS;
        }
        if (level.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        if (stack.getItem() == ModItems.KORNET_MISSILE.get()) {
            if (getAmmo() > 0) {
                player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.ammo_full"), true);
            } else {
                setAmmo(1);
                if (!player.abilities.instabuild) {
                    stack.shrink(1);
                }
                level.playSound(null, getX(), getY(), getZ(), ModSounds.RELOAD.get(), SoundCategory.BLOCKS, 1F, 0.8F);
            }
        } else if (stack.isEmpty() && player.isShiftKeyDown() && getPassengers().isEmpty()) {
            ItemStack item = new ItemStack(ModItems.KORNET.get());
            if (!player.inventory.add(item)) {
                player.drop(item, false);
            }
            if (getAmmo() > 0) {
                ItemStack missile = new ItemStack(ModItems.KORNET_MISSILE.get());
                if (!player.inventory.add(missile)) {
                    player.drop(missile, false);
                }
            }
            remove();
        } else if (canAddPassenger(player)) {
            player.startRiding(this);
        } else {
            return ActionResultType.PASS;
        }
        return ActionResultType.CONSUME;
    }

    @Override
    protected void destroy(@Nullable DamageSource cause) {
        ejectPassengers();
        remove();
        if (isDismantled(cause)) {
            spawnAtLocation(new ItemStack(ModItems.KORNET.get()));
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        super.readAdditionalSaveData(tag);
        reload = tag.getInt("Reload");
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Reload", reload);
    }
}
