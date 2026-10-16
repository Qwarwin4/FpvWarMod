package com.qwarwin.fpvdrones.registry;

import com.qwarwin.fpvdrones.FpvDrones;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, FpvDrones.MODID);

    public static final RegistryObject<SoundEvent> FPV_MOTOR = reg("fpv_motor");
    public static final RegistryObject<SoundEvent> MAVIC_MOTOR = reg("mavic_motor");
    public static final RegistryObject<SoundEvent> SHAHED_ENGINE = reg("shahed_engine");
    public static final RegistryObject<SoundEvent> JET_ENGINE = reg("jet_engine");
    public static final RegistryObject<SoundEvent> SHAHED_LAUNCH = reg("shahed_launch");
    public static final RegistryObject<SoundEvent> TRUCK_ENGINE = reg("truck_engine");
    public static final RegistryObject<SoundEvent> RELEASE = reg("release");
    public static final RegistryObject<SoundEvent> BEEP = reg("beep");
    public static final RegistryObject<SoundEvent> STATIC = reg("static");

    private static RegistryObject<SoundEvent> reg(String name) {
        return SOUNDS.register(name, () -> new SoundEvent(FpvDrones.id(name)));
    }

    private ModSounds() {
    }
}
