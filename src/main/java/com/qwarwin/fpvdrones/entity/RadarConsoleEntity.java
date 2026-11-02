package com.qwarwin.fpvdrones.entity;

import com.qwarwin.fpvdrones.network.Network;
import com.qwarwin.fpvdrones.network.RadarPicturePacket;
import com.qwarwin.fpvdrones.registry.ModItems;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

/**
 * The radar's computer: a field table with two monitors. Shows the air picture from every radar of its side
 * within link range, and sends the nearest ready interceptor after a contact picked on the scope. RMB sits
 * the operator down in the chair facing the monitors and opens the scope; Shift gets up.
 */
public class RadarConsoleEntity extends RadioMachineEntity {
    /** The operator's chair in front of the monitors (tools/gen_air.py). */
    public static final Vector3d SEAT = new Vector3d(0, 0.48, 0.8);

    public RadarConsoleEntity(EntityType<? extends RadarConsoleEntity> type, World world) {
        super(type, world);
    }

    @Override
    public double jamRadius() {
        return 0;
    }

    @Override
    protected Item item() {
        return ModItems.RADAR_CONSOLE.get();
    }

    @Override
    public float getMaxHealth() {
        return 30F;
    }

    @Override
    protected float damageFactor(DamageSource source) {
        return isShell(source) || source.isExplosion() || isShapedCharge(source) ? 1F : 0.5F;
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return !isWrecked() && getPassengers().isEmpty();
    }

    @Override
    public void positionRider(Entity passenger) {
        if (!hasPassenger(passenger)) {
            return;
        }
        Vector3d s = toWorld(SEAT);
        passenger.setPos(s.x, s.y + passenger.getMyRidingOffset() + 0.1, s.z);
    }

    @Override
    public ActionResultType interact(PlayerEntity player, Hand hand) {
        if (isWrecked() || hand != Hand.MAIN_HAND || !player.getItemInHand(hand).isEmpty()) {
            return ActionResultType.PASS;
        }
        if (!level.isClientSide) {
            if (owner == null) {
                owner = player.getUUID();
            }
            if (player.getVehicle() != this && canAddPassenger(player)) {
                player.startRiding(this);
                player.yRot = yRot + 180F;
                player.setYHeadRot(player.yRot);
            }
            Network.sendTo((ServerPlayerEntity) player, RadarPicturePacket.of(this, player, true));
        }
        return ActionResultType.sidedSuccess(level.isClientSide);
    }
}
