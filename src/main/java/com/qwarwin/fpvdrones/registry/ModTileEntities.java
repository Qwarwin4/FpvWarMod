package com.qwarwin.fpvdrones.registry;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.block.LauncherTileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class ModTileEntities {
    public static final DeferredRegister<TileEntityType<?>> TILE_ENTITIES =
            DeferredRegister.create(ForgeRegistries.TILE_ENTITIES, FpvDrones.MODID);

    @SuppressWarnings("ConstantConditions")
    public static final RegistryObject<TileEntityType<LauncherTileEntity>> LAUNCHER = TILE_ENTITIES.register("launcher",
            () -> TileEntityType.Builder.of(LauncherTileEntity::new, ModBlocks.LAUNCHER.get()).build(null));

    private ModTileEntities() {
    }
}
