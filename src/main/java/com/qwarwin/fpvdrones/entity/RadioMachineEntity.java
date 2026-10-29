package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.util.Explosions;
import com.qwarwin.fpvdrones.util.Jamming;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A machine with transmitters on it: the jammers (the Ural, the tower) and the relay mast. Switched on and off
 * by hand; its owner's and his team's drones are on frequencies it leaves alone.
 */
public abstract class RadioMachineEntity extends MachineEntity {
    private static final DataParameter<Boolean> ACTIVE = EntityDataManager.defineId(RadioMachineEntity.class, DataSerializers.BOOLEAN);

    @Nullable
    protected UUID owner;

    protected RadioMachineEntity(EntityType<?> type, World world) {
        super(type, world);
    }

    /** Jamming reach in blocks, 0 for a relay. */
    public abstract double jamRadius();

    protected abstract Item item();

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(ACTIVE, true);
    }

    public boolean isActive() {
        return entityData.get(ACTIVE);
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    protected void toggle(PlayerEntity player) {
        boolean on = !isActive();
        entityData.set(ACTIVE, on);
        if (owner == null) {
            owner = player.getUUID();
        }
        player.displayClientMessage(new TranslationTextComponent(on ? "msg.fpvdrones.radio_on" : "msg.fpvdrones.radio_off")
                .withStyle(on ? TextFormatting.GOLD : TextFormatting.GRAY), true);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 1F, on ? 1.2F : 0.7F);
        if (!on) {
            Jamming.off(getUUID());
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!level.isClientSide && isActive() && jamRadius() > 0) {
            Jamming.report(level, getUUID(), position().add(0, getBbHeight(), 0), jamRadius(), owner);
        }
    }

    @Override
    protected void destroy(@Nullable DamageSource cause) {
        Jamming.off(getUUID());
        ejectPassengers();
        remove();
        if (cause == null) {
            return;
        }
        if (isDismantled(cause)) {
            spawnAtLocation(new ItemStack(item()));
        } else {
            Explosions.strike(level, null, position().add(0, 1, 0), 2.5F, true);
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundNBT tag) {
        super.readAdditionalSaveData(tag);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        if (tag.contains("Active")) {
            entityData.set(ACTIVE, tag.getBoolean("Active"));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundNBT tag) {
        super.addAdditionalSaveData(tag);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.putBoolean("Active", isActive());
    }
}
