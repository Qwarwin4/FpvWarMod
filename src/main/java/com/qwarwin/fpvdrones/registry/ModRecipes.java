package com.qwarwin.fpvdrones.registry;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.crafting.AssemblyRecipe;
import net.minecraft.item.crafting.IRecipeSerializer;
import net.minecraft.item.crafting.IRecipeType;
import net.minecraft.util.registry.Registry;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class ModRecipes {
    public static final DeferredRegister<IRecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, FpvDrones.MODID);

    public static final IRecipeType<AssemblyRecipe> ASSEMBLY = new IRecipeType<AssemblyRecipe>() {
        @Override
        public String toString() {
            return FpvDrones.MODID + ":assembly";
        }
    };

    // the type has no forge registry in 1.16, so it goes into the vanilla one together with the serializer
    public static final RegistryObject<IRecipeSerializer<AssemblyRecipe>> ASSEMBLY_SERIALIZER = SERIALIZERS.register("assembly", () -> {
        Registry.register(Registry.RECIPE_TYPE, FpvDrones.id("assembly"), ASSEMBLY);
        return new AssemblyRecipe.Serializer();
    });

    private ModRecipes() {
    }
}
