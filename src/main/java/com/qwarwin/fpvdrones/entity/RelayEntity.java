package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.util.RelayNetwork;
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
 * Relay mast for Shaheds and Gerans. Optional: they fly without it. With relays switched on, strike drones can
 * be sent past their own range (as far again as a relay reaches, and on along a chain of them), and a drone
 * under a relay checks its position against it, so jamming it off course works far worse.
 */
public class RelayEntity extends RadioMachineEntity {
    public RelayEntity(EntityType<? extends RelayEntity> type, World world) {
        super(type, world);
    }

    @Override
    public double jamRadius() {
        return 0;
    }

    @Override
    protected Item item() {
        return ModItems.RELAY.get();
    }

    @Override
    public float getMaxHealth() {
        return 40F;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level.isClientSide && tickCount % 40 == 1) {
            RelayNetwork net = RelayNetwork.get((ServerWorld) level);
            if (isActive()) {
                net.put(getUUID(), position(), owner);
            } else {
                net.remove(getUUID());
            }
        }
    }

    @Override
    protected void destroy(@Nullable DamageSource cause) {
        if (!level.isClientSide) {
            RelayNetwork.get((ServerWorld) level).remove(getUUID());
        }
        super.destroy(cause);
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        if (hand != Hand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()) {
            return ActionResultType.PASS;
        }
        if (!level.isClientSide) {
            toggle(player);
            RelayNetwork net = RelayNetwork.get((ServerWorld) level);
            if (isActive()) {
                net.put(getUUID(), position(), owner);
            } else {
                net.remove(getUUID());
            }
        }
        return ActionResultType.sidedSuccess(level.isClientSide);
    }
}
