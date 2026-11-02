package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.util.AirPicture;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;

/**
 * Search radar on a towed mast: a flat array that turns once every three seconds and sweeps the sky round it.
 * What it sees goes to the radar consoles of its side (see {@link AirPicture}). RMB switches it on and off.
 */
public class RadarEntity extends RadioMachineEntity {
    /** Antenna turn, degrees per tick, while it's on. */
    public static final float SPIN = 6F;
    public static final double ANTENNA_Y = 4.15;

    public RadarEntity(EntityType<? extends RadarEntity> type, World world) {
        super(type, world);
    }

    @Override
    public double jamRadius() {
        return 0;
    }

    @Override
    protected Item item() {
        return ModItems.RADAR.get();
    }

    @Override
    public float getMaxHealth() {
        return 70F;
    }

    @Override
    protected float damageFactor(DamageSource source) {
        // the array is thin sheet: fragments go through it
        return isShapedCharge(source) || source.isExplosion() ? 1F : isShell(source) ? 0.8F : 0.4F;
    }

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide || isWrecked()) {
            return;
        }
        if (isActive() && tickCount % 20 == 3) {
            AirPicture.sweep((ServerWorld) level, getUUID(), position().add(0, ANTENNA_Y, 0), owner,
                    Config.RADAR_RANGE.get());
        } else if (!isActive() && tickCount % 20 == 3) {
            AirPicture.off(getUUID());
        }
    }

    @Override
    protected void destroy(@Nullable DamageSource cause) {
        AirPicture.off(getUUID());
        super.destroy(cause);
    }

    @Override
    public void remove(boolean keepData) {
        if (!level.isClientSide) {
            AirPicture.off(getUUID());
        }
        super.remove(keepData);
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        if (isWrecked() || hand != Hand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()) {
            return ActionResultType.PASS;
        }
        if (!level.isClientSide) {
            toggle(player);
        }
        return ActionResultType.sidedSuccess(level.isClientSide);
    }
}
