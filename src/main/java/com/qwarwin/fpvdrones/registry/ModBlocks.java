package com.qwarwin.fpvdrones.registry;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.block.LaunchPadBlock;
import com.qwarwin.fpvdrones.block.LauncherBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, FpvDrones.MODID);

    public static final RegistryObject<Block> LAUNCHER = BLOCKS.register("launcher",
            () -> new LauncherBlock(AbstractBlock.Properties.of(Material.METAL)
                    .strength(3.5F, 8.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    public static final RegistryObject<Block> LAUNCH_PAD = BLOCKS.register("launch_pad",
            () -> new LaunchPadBlock(AbstractBlock.Properties.of(Material.DECORATION)
                    .strength(0.6F)
                    .sound(SoundType.SCAFFOLDING)
                    .noOcclusion()));

    private ModBlocks() {
    }
}
