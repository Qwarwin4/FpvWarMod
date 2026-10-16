package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.session.SessionManager;
import com.qwarwin.fpvdrones.util.ChunkTickets;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.DamageSource;
import net.minecraft.util.HandSide;
import net.minecraft.util.NonNullList;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import java.util.Optional;
import java.util.UUID;

// Stand-in for the player while they look through a camera. Hitting it kicks the player back into their body.
public class OperatorEntity extends LivingEntity {
    private static final DataParameter<Optional<UUID>> OWNER =
            EntityDataManager.defineId(OperatorEntity.class, DataSerializers.OPTIONAL_UUID);

    private final NonNullList<ItemStack> hands = NonNullList.withSize(2, ItemStack.EMPTY);
    private final NonNullList<ItemStack> armor = NonNullList.withSize(4, ItemStack.EMPTY);

    public OperatorEntity(EntityType<? extends OperatorEntity> type, World world) {
        super(type, world);
    }

    public void copyFrom(ServerPlayerEntity player) {
        moveTo(player.getX(), player.getY(), player.getZ(), player.yRot, player.xRot);
        yBodyRot = player.yBodyRot;
        yHeadRot = player.yHeadRot;
        entityData.set(OWNER, Optional.of(player.getUUID()));
        setCustomName(player.getDisplayName());
        setCustomNameVisible(true);
        for (EquipmentSlotType slot : EquipmentSlotType.values()) {
            setItemSlot(slot, player.getItemBySlot(slot).copy());
        }
        setNoGravity(player.abilities.flying);
    }

    public Optional<UUID> getOwner() {
        return entityData.get(OWNER);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(OWNER, Optional.empty());
    }

    @Override
    public void tick() {
        super.tick();
        if (!level.isClientSide) {
            if (tickCount > 5 && !SessionManager.isBodyActive(getUUID())) {
                remove();
                return;
            }
            if (tickCount % 20 == 1) {
                ChunkTickets.keep((ServerWorld) level, this, position(), 2);
            }
        }
        yBodyRot = yRot;
        yHeadRot = yRot;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level.isClientSide) {
            return false;
        }
        if (isInvulnerableTo(source) || source == DamageSource.IN_WALL || source == DamageSource.CRAMMING
                || source == DamageSource.FALL || source == DamageSource.OUT_OF_WORLD) {
            return false;
        }
        SessionManager.onBodyHurt(this, source, amount);
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldShowName() {
        return true;
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return armor;
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlotType slot) {
        switch (slot.getType()) {
            case HAND:
                return hands.get(slot.getIndex());
            case ARMOR:
                return armor.get(slot.getIndex());
            default:
                return ItemStack.EMPTY;
        }
    }

    @Override
    public void setItemSlot(EquipmentSlotType slot, ItemStack stack) {
        switch (slot.getType()) {
            case HAND:
                hands.set(slot.getIndex(), stack);
                break;
            case ARMOR:
                armor.set(slot.getIndex(), stack);
                break;
            default:
                break;
        }
    }

    @Override
    public HandSide getMainArm() {
        return HandSide.RIGHT;
    }

    @Override
    public void readAdditionalSaveData(CompoundNBT tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) {
            entityData.set(OWNER, Optional.of(tag.getUUID("Owner")));
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundNBT tag) {
        super.addAdditionalSaveData(tag);
        getOwner().ifPresent(u -> tag.putUUID("Owner", u));
    }
}
