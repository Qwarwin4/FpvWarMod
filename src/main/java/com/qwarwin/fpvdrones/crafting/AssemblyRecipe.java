package com.qwarwin.fpvdrones.crafting;

import com.google.gson.JsonObject;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.registry.ModRecipes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipeSerializer;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.item.crafting.ShapedRecipe;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistryEntry;

/**
 * Shaped recipe for the assembly bench. Same JSON as crafting_shaped plus
 * {@code "soldering": true} when the soldering iron has to be in its slot.
 */
public class AssemblyRecipe extends ShapedRecipe {
    private final boolean soldering;

    public AssemblyRecipe(ShapedRecipe base, boolean soldering) {
        super(base.getId(), base.getGroup(), base.getRecipeWidth(), base.getRecipeHeight(), base.getIngredients(), base.getResultItem());
        this.soldering = soldering;
    }

    public boolean needsSoldering() {
        return soldering;
    }

    @Override
    public IRecipeType<?> getType() {
        return ModRecipes.ASSEMBLY;
    }

    @Override
    public IRecipeSerializer<?> getSerializer() {
        return ModRecipes.ASSEMBLY_SERIALIZER.get();
    }

    @Override
    public ItemStack getToastSymbol() {
        return new ItemStack(ModItems.ASSEMBLY_BENCH.get());
    }

    public static class Serializer extends ForgeRegistryEntry<IRecipeSerializer<?>> implements IRecipeSerializer<AssemblyRecipe> {
        @Override
        public AssemblyRecipe fromJson(ResourceLocation id, JsonObject json) {
            return new AssemblyRecipe(IRecipeSerializer.SHAPED_RECIPE.fromJson(id, json), JSONUtils.getAsBoolean(json, "soldering", false));
        }

        @Override
        public AssemblyRecipe fromNetwork(ResourceLocation id, PacketBuffer buf) {
            ShapedRecipe base = IRecipeSerializer.SHAPED_RECIPE.fromNetwork(id, buf);
            return new AssemblyRecipe(base, buf.readBoolean());
        }

        @Override
        public void toNetwork(PacketBuffer buf, AssemblyRecipe recipe) {
            IRecipeSerializer.SHAPED_RECIPE.toNetwork(buf, recipe);
            buf.writeBoolean(recipe.soldering);
        }
    }
}
