package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.registry.ModEntities;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUseContext;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

public class TruckItem extends Item {
    public TruckItem(Properties props) {
        super(props);
    }

    @Override
    public ActionResultType useOn(ItemUseContext ctx) {
        World world = ctx.getLevel();
        if (world.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        LaunchTruckEntity truck = ModEntities.LAUNCH_TRUCK.get().create(world);
        if (truck == null) {
            return ActionResultType.FAIL;
        }
        Vector3d hit = ctx.getClickLocation();
        PlayerEntity player = ctx.getPlayer();
        truck.moveTo(hit.x, hit.y + 0.1, hit.z, player != null ? player.yRot : 0F, 0F);
        world.addFreshEntity(truck);
        if (player == null || !player.abilities.instabuild) {
            ctx.getItemInHand().shrink(1);
        }
        return ActionResultType.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.truck").withStyle(TextFormatting.GRAY));
    }
}
