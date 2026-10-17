package com.qwarwin.fpvdrones.item;

import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

public class PartItem extends Item {
    private final String tooltip;

    public PartItem(Properties props) {
        this("tooltip.fpvdrones.part", props);
    }

    public PartItem(String tooltip, Properties props) {
        super(props);
        this.tooltip = tooltip;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> lines, ITooltipFlag flag) {
        lines.add(new TranslationTextComponent(tooltip).withStyle(TextFormatting.DARK_GRAY));
    }
}
