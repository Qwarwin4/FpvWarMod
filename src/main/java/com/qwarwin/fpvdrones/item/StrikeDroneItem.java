package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.entity.StrikeType;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

public class StrikeDroneItem extends Item {
    private final StrikeType type;

    public StrikeDroneItem(StrikeType type, Properties props) {
        super(props);
        this.type = type;
    }

    public StrikeType getType() {
        return type;
    }

    @Nullable
    public static StrikeType typeOf(ItemStack stack) {
        return stack.getItem() instanceof StrikeDroneItem ? ((StrikeDroneItem) stack.getItem()).type : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.strike").withStyle(TextFormatting.GRAY));
    }
}
