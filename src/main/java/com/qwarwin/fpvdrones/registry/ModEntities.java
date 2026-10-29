package com.qwarwin.fpvdrones.registry;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.entity.AirDefenseGunEntity;
import com.qwarwin.fpvdrones.entity.EwStationEntity;
import com.qwarwin.fpvdrones.entity.EwTruckEntity;
import com.qwarwin.fpvdrones.entity.FpvDroneEntity;
import com.qwarwin.fpvdrones.entity.KornetEntity;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.entity.MavicEntity;
import com.qwarwin.fpvdrones.entity.MissileEntity;
import com.qwarwin.fpvdrones.entity.MunitionEntity;
import com.qwarwin.fpvdrones.entity.OperatorEntity;
import com.qwarwin.fpvdrones.entity.RadioBeaconEntity;
import com.qwarwin.fpvdrones.entity.RelayEntity;
import com.qwarwin.fpvdrones.entity.ShellEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityClassification;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITIES, FpvDrones.MODID);

    public static final RegistryObject<EntityType<FpvDroneEntity>> FPV_DRONE = register("fpv_drone",
            EntityType.Builder.<FpvDroneEntity>of(FpvDroneEntity::new, EntityClassification.MISC)
                    .sized(0.55F, 0.25F).setTrackingRange(16).setUpdateInterval(1).setShouldReceiveVelocityUpdates(true));

    public static final RegistryObject<EntityType<MavicEntity>> MAVIC = register("mavic3",
            EntityType.Builder.<MavicEntity>of(MavicEntity::new, EntityClassification.MISC)
                    .sized(0.5F, 0.22F).setTrackingRange(16).setUpdateInterval(1).setShouldReceiveVelocityUpdates(true));

    public static final RegistryObject<EntityType<StrikeDroneEntity>> STRIKE_DRONE = register("strike_drone",
            EntityType.Builder.<StrikeDroneEntity>of(StrikeDroneEntity::new, EntityClassification.MISC)
                    .sized(2.2F, 0.8F).setTrackingRange(20).setUpdateInterval(1).setShouldReceiveVelocityUpdates(true)
                    .fireImmune());

    public static final RegistryObject<EntityType<LaunchTruckEntity>> LAUNCH_TRUCK = register("launch_truck",
            EntityType.Builder.<LaunchTruckEntity>of(LaunchTruckEntity::new, EntityClassification.MISC)
                    .sized(3.0F, 3.1F).setTrackingRange(16).setUpdateInterval(1));

    public static final RegistryObject<EntityType<MunitionEntity>> MUNITION = register("munition",
            EntityType.Builder.<MunitionEntity>of(MunitionEntity::new, EntityClassification.MISC)
                    .sized(0.25F, 0.25F).setTrackingRange(10).setUpdateInterval(1).setShouldReceiveVelocityUpdates(true));

    public static final RegistryObject<EntityType<RadioBeaconEntity>> RADIO_BEACON = register("radio_beacon",
            EntityType.Builder.<RadioBeaconEntity>of(RadioBeaconEntity::new, EntityClassification.MISC)
                    .sized(0.3F, 0.3F).setTrackingRange(10).setUpdateInterval(2));

    public static final RegistryObject<EntityType<AirDefenseGunEntity>> AIR_DEFENSE_GUN = register("air_defense_gun",
            EntityType.Builder.<AirDefenseGunEntity>of(AirDefenseGunEntity::new, EntityClassification.MISC)
                    .sized(1.4F, 2.0F).setTrackingRange(16).setUpdateInterval(1));

    public static final RegistryObject<EntityType<EwTruckEntity>> EW_TRUCK = register("ew_truck",
            EntityType.Builder.<EwTruckEntity>of(EwTruckEntity::new, EntityClassification.MISC)
                    .sized(2.6F, 3.0F).setTrackingRange(16).setUpdateInterval(1));

    public static final RegistryObject<EntityType<EwStationEntity>> EW_STATION = register("ew_station",
            EntityType.Builder.<EwStationEntity>of(EwStationEntity::new, EntityClassification.MISC)
                    .sized(2.0F, 9.5F).setTrackingRange(16).setUpdateInterval(20));

    public static final RegistryObject<EntityType<RelayEntity>> RELAY = register("relay",
            EntityType.Builder.<RelayEntity>of(RelayEntity::new, EntityClassification.MISC)
                    .sized(1.2F, 5.0F).setTrackingRange(16).setUpdateInterval(20));

    public static final RegistryObject<EntityType<KornetEntity>> KORNET = register("kornet",
            EntityType.Builder.<KornetEntity>of(KornetEntity::new, EntityClassification.MISC)
                    .sized(0.9F, 1.1F).setTrackingRange(12).setUpdateInterval(1));

    public static final RegistryObject<EntityType<ShellEntity>> SHELL = register("shell",
            EntityType.Builder.<ShellEntity>of(ShellEntity::new, EntityClassification.MISC)
                    .sized(0.2F, 0.2F).setTrackingRange(16).setUpdateInterval(20).setShouldReceiveVelocityUpdates(false)
                    .noSave());

    public static final RegistryObject<EntityType<MissileEntity>> MISSILE = register("missile",
            EntityType.Builder.<MissileEntity>of(MissileEntity::new, EntityClassification.MISC)
                    .sized(0.3F, 0.3F).setTrackingRange(16).setUpdateInterval(1).setShouldReceiveVelocityUpdates(true)
                    .fireImmune());

    public static final RegistryObject<EntityType<OperatorEntity>> OPERATOR = register("operator",
            EntityType.Builder.<OperatorEntity>of(OperatorEntity::new, EntityClassification.MISC)
                    .sized(0.6F, 1.8F).setTrackingRange(10).noSummon());

    private static <T extends Entity> RegistryObject<EntityType<T>> register(String name, EntityType.Builder<T> builder) {
        return ENTITIES.register(name, () -> builder.build(FpvDrones.MODID + ":" + name));
    }

    public static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(OPERATOR.get(), LivingEntity.createLivingAttributes().build());
    }

    private ModEntities() {
    }
}
