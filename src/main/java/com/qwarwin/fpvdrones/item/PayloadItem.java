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

public class PayloadItem extends Item {
    private final PayloadType type;

    public PayloadItem(PayloadType type, Properties props) {
        super(props);
        this.type = type;
    }

    public PayloadType getType() {
        return type;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        String key = type.fitsFpv() ? "tooltip.fpvdrones.payload_fpv" : "tooltip.fpvdrones.payload_mavic";
        tooltip.add(new TranslationTextComponent(key).withStyle(TextFormatting.GRAY));
    }
}
