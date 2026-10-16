package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.block.LaunchPadBlock;
import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.DroneKind;
import com.qwarwin.fpvdrones.registry.ModEntities;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUseContext;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

public class DroneItem extends Item {
    public static final String TAG_BATTERY = "Battery";
    public static final String TAG_HEALTH = "Health";

    private final DroneKind kind;

    public DroneItem(DroneKind kind, Properties props) {
        super(props);
        this.kind = kind;
    }

    public DroneKind getKind() {
        return kind;
    }

    @Override
    public ActionResultType useOn(ItemUseContext ctx) {
        World world = ctx.getLevel();
        if (world.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        ItemStack stack = ctx.getItemInHand();
        AbstractDroneEntity drone = kind == DroneKind.MAVIC
                ? ModEntities.MAVIC.get().create(world)
                : ModEntities.FPV_DRONE.get().create(world);
        if (drone == null) {
            return ActionResultType.FAIL;
        }
        float yaw = ctx.getPlayer() != null ? ctx.getPlayer().yRot : 0F;
        BlockPos clicked = ctx.getClickedPos();
        if (world.getBlockState(clicked).getBlock() instanceof LaunchPadBlock) {
            drone.moveTo(clicked.getX() + 0.5, clicked.getY() + LaunchPadBlock.FORK_TOP, clicked.getZ() + 0.5, yaw, 0F);
        } else {
            Vector3d hit = ctx.getClickLocation();
            drone.moveTo(hit.x, hit.y + 0.05, hit.z, yaw, 0F);
        }
        CompoundNBT tag = stack.getTag();
        if (tag != null) {
            if (tag.contains(TAG_BATTERY)) {
                drone.setBattery(tag.getFloat(TAG_BATTERY));
            }
            if (tag.contains(TAG_HEALTH)) {
                drone.setHealth(tag.getFloat(TAG_HEALTH));
            }
        }
        if (stack.hasCustomHoverName()) {
            drone.setCustomName(stack.getHoverName());
        }
        world.addFreshEntity(drone);
        PlayerEntity player = ctx.getPlayer();
        if (player == null || !player.abilities.instabuild) {
            stack.shrink(1);
        }
        return ActionResultType.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        tooltip.add(new TranslationTextComponent(kind == DroneKind.MAVIC ? "tooltip.fpvdrones.mavic" : "tooltip.fpvdrones.fpv")
                .withStyle(TextFormatting.GRAY));
        CompoundNBT tag = stack.getTag();
        int pct = tag != null && tag.contains(TAG_BATTERY) ? Math.round(tag.getFloat(TAG_BATTERY) * 100F) : 100;
        tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.battery", pct)
                .withStyle(pct > 25 ? TextFormatting.GREEN : TextFormatting.RED));
    }
}
