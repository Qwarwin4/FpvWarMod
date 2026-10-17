package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.session.SessionManager;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/** DJI RC: binds to a Mavic only and has its own screen, so it flies without goggles. */
public class MavicRemoteItem extends DroneRemoteItem {
    public MavicRemoteItem(Properties props) {
        super(props);
    }

    @Override
    public ActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!world.isClientSide) {
            SessionManager.connectHandheld((ServerPlayerEntity) player, stack);
        }
        return ActionResult.sidedSuccess(stack, world.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        super.appendHoverText(stack, world, tooltip, flag);
        tooltip.remove(tooltip.size() - 1);
        tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.mavic_remote").withStyle(TextFormatting.GRAY));
    }
}
