package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.FpvDrones;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.IArmorMaterial;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/** Just a helmet. Putting it on connects to whatever the drone remote in the inventory is bound to. */
public class FpvGogglesItem extends ArmorItem {

    public FpvGogglesItem(Properties props) {
        super(Material.INSTANCE, EquipmentSlotType.HEAD, props);
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlotType slot, String type) {
        return FpvDrones.MODID + ":textures/models/armor/fpv_goggles_layer_1.png";
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.goggles").withStyle(TextFormatting.GRAY));
    }

    private enum Material implements IArmorMaterial {
        INSTANCE;

        @Override
        public int getDurabilityForSlot(EquipmentSlotType slot) {
            return 0;
        }

        @Override
        public int getDefenseForSlot(EquipmentSlotType slot) {
            return 1;
        }

        @Override
        public int getEnchantmentValue() {
            return 5;
        }

        @Override
        public SoundEvent getEquipSound() {
            return SoundEvents.ARMOR_EQUIP_LEATHER;
        }

        @Override
        public Ingredient getRepairIngredient() {
            return Ingredient.EMPTY;
        }

        @Override
        public String getName() {
            return FpvDrones.MODID + ":fpv_goggles";
        }

        @Override
        public float getToughness() {
            return 0F;
        }

        @Override
        public float getKnockbackResistance() {
            return 0F;
        }
    }
}
