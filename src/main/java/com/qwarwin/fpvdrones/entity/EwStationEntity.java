package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.registry.ModItems;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/** A lattice tower with jamming antennas on top: covers a wide area, but it stays where it was put. */
public class EwStationEntity extends RadioMachineEntity {
    public EwStationEntity(EntityType<? extends EwStationEntity> type, World world) {
        super(type, world);
    }

    @Override
    public double jamRadius() {
        return Config.EW_STATION_RADIUS.get();
    }

    @Override
    protected Item item() {
        return ModItems.EW_STATION.get();
    }

    @Override
    public float getMaxHealth() {
        return 90F;
    }

    @Override
    protected float damageFactor(DamageSource source) {
        // steel lattice: shrapnel and bullets mostly fly through
        return isShapedCharge(source) || source.isExplosion() ? 1F : isShell(source) ? 0.6F : 0.3F;
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()) {
            return ActionResultType.PASS;
        }
        if (!level.isClientSide) {
            toggle(player);
        }
        return ActionResultType.sidedSuccess(level.isClientSide);
    }
}
