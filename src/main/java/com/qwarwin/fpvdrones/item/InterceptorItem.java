package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.entity.InterceptorEntity;
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

/** Put down on the ground it waits, ready, until a remote or a radar console sends it up. */
public class InterceptorItem extends Item {
    public InterceptorItem(Properties props) {
        super(props);
    }

    @Override
    public ActionResultType useOn(ItemUseContext ctx) {
        World world = ctx.getLevel();
        if (world.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        InterceptorEntity i = ModEntities.INTERCEPTOR.get().create(world);
        if (i == null) {
            return ActionResultType.FAIL;
        }
        PlayerEntity player = ctx.getPlayer();
        Vector3d hit = ctx.getClickLocation();
        i.moveTo(hit.x, hit.y + 0.02, hit.z, player != null ? player.yRot : 0F, 0F);
        i.setOwner(player != null ? player.getUUID() : null);
        world.addFreshEntity(i);
        if (player == null || !player.abilities.instabuild) {
            ctx.getItemInHand().shrink(1);
        }
        return ActionResultType.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> lines, ITooltipFlag flag) {
        lines.add(new TranslationTextComponent("tooltip.fpvdrones.interceptor").withStyle(TextFormatting.GRAY));
    }
}
