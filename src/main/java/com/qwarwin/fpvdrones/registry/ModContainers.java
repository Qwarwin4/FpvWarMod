package com.qwarwin.fpvdrones.registry;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.inventory.AssemblyContainer;
import net.minecraft.inventory.container.ContainerType;
import net.minecraftforge.common.extensions.IForgeContainerType;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class ModContainers {
    public static final DeferredRegister<ContainerType<?>> CONTAINERS = DeferredRegister.create(ForgeRegistries.CONTAINERS, FpvDrones.MODID);

    public static final RegistryObject<ContainerType<AssemblyContainer>> ASSEMBLY = CONTAINERS.register("assembly_bench",
            () -> IForgeContainerType.create((id, inv, buf) -> new AssemblyContainer(id, inv, null)));

    private ModContainers() {
    }
}
