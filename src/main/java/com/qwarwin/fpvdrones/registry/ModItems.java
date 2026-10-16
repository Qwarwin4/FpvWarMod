package com.qwarwin.fpvdrones.registry;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.entity.DroneKind;
import com.qwarwin.fpvdrones.entity.StrikeType;
import com.qwarwin.fpvdrones.item.DroneItem;
import com.qwarwin.fpvdrones.item.DroneRemoteItem;
import com.qwarwin.fpvdrones.item.FpvGogglesItem;
import com.qwarwin.fpvdrones.item.LaunchRemoteItem;
import com.qwarwin.fpvdrones.item.PartItem;
import com.qwarwin.fpvdrones.item.PayloadItem;
import com.qwarwin.fpvdrones.item.PayloadType;
import com.qwarwin.fpvdrones.item.SolderingIronItem;
import com.qwarwin.fpvdrones.item.StrikeDroneItem;
import com.qwarwin.fpvdrones.item.TabletItem;
import com.qwarwin.fpvdrones.item.TruckItem;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Rarity;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, FpvDrones.MODID);

    public static final RegistryObject<Item> FPV_GOGGLES = ITEMS.register("fpv_goggles",
            () -> new FpvGogglesItem(props().stacksTo(1)));
    public static final RegistryObject<Item> DRONE_REMOTE = ITEMS.register("drone_remote",
            () -> new DroneRemoteItem(props().stacksTo(1)));
    public static final RegistryObject<Item> FPV_DRONE = ITEMS.register("fpv_drone",
            () -> new DroneItem(DroneKind.FPV, props().stacksTo(1)));
    public static final RegistryObject<Item> MAVIC = ITEMS.register("mavic3",
            () -> new DroneItem(DroneKind.MAVIC, props().stacksTo(1).rarity(Rarity.UNCOMMON)));

    public static final RegistryObject<Item> SHAHED = ITEMS.register("shahed136",
            () -> new StrikeDroneItem(StrikeType.SHAHED_136, props().stacksTo(5).rarity(Rarity.RARE)));
    public static final RegistryObject<Item> SHAHED238 = ITEMS.register("shahed238",
            () -> new StrikeDroneItem(StrikeType.SHAHED_238, props().stacksTo(5).rarity(Rarity.EPIC)));
    public static final RegistryObject<Item> GERAN2 = ITEMS.register("geran2",
            () -> new StrikeDroneItem(StrikeType.GERAN_2, props().stacksTo(5).rarity(Rarity.RARE)));
    public static final RegistryObject<Item> GERAN5 = ITEMS.register("geran5",
            () -> new StrikeDroneItem(StrikeType.GERAN_5, props().stacksTo(5).rarity(Rarity.EPIC)));
    public static final RegistryObject<Item> LAUNCH_TRUCK = ITEMS.register("launch_truck",
            () -> new TruckItem(props().stacksTo(1).rarity(Rarity.RARE)));

    public static final RegistryObject<Item> TABLET = ITEMS.register("tablet", () -> new TabletItem(props().stacksTo(1)));
    public static final RegistryObject<Item> LAUNCH_REMOTE = ITEMS.register("launch_remote",
            () -> new LaunchRemoteItem(props().stacksTo(1)));

    public static final RegistryObject<Item> PG7VS = payload("pg7vs", PayloadType.PG7VS, 8);
    public static final RegistryObject<Item> OG7 = payload("og7", PayloadType.OG7, 8);
    public static final RegistryObject<Item> CANISTER = payload("explosive_canister", PayloadType.CANISTER, 4);
    public static final RegistryObject<Item> RGD5 = payload("rgd5", PayloadType.RGD5, 16);
    public static final RegistryObject<Item> VOG17M = payload("vog17m", PayloadType.VOG17M, 32);

    // parts
    public static final RegistryObject<Item> FRAME = part("frame");
    public static final RegistryObject<Item> MOTOR = part("motor");
    public static final RegistryObject<Item> PROPELLER = part("propeller");
    public static final RegistryObject<Item> ESC = part("esc");
    public static final RegistryObject<Item> FLIGHT_CONTROLLER = part("flight_controller");
    public static final RegistryObject<Item> FLIGHT_STACK = part("flight_stack");
    public static final RegistryObject<Item> BATTERY = part("battery");
    public static final RegistryObject<Item> FPV_CAMERA = part("fpv_camera");
    public static final RegistryObject<Item> VTX = part("vtx");
    public static final RegistryObject<Item> RECEIVER = part("receiver");
    public static final RegistryObject<Item> ANTENNA = part("antenna");
    public static final RegistryObject<Item> GIMBAL_CAMERA = part("gimbal_camera");
    public static final RegistryObject<Item> GNSS_MODULE = part("gnss_module");
    public static final RegistryObject<Item> DISPLAY = part("display");
    public static final RegistryObject<Item> AIRFRAME = part("airframe");
    public static final RegistryObject<Item> PISTON_ENGINE = part("piston_engine");
    public static final RegistryObject<Item> SMALL_JET_ENGINE = part("small_jet_engine");
    public static final RegistryObject<Item> WARHEAD = part("warhead");
    public static final RegistryObject<Item> BOOSTER = part("booster");

    public static final RegistryObject<Item> SOLDERING_IRON = ITEMS.register("soldering_iron",
            () -> new SolderingIronItem(props().durability(256)));
    public static final RegistryObject<Item> ASSEMBLY_BENCH = ITEMS.register("assembly_bench",
            () -> new BlockItem(ModBlocks.ASSEMBLY_BENCH.get(), props()));

    public static final RegistryObject<Item> LAUNCHER = ITEMS.register("launcher",
            () -> new BlockItem(ModBlocks.LAUNCHER.get(), props()));
    public static final RegistryObject<Item> LAUNCH_PAD = ITEMS.register("launch_pad",
            () -> new BlockItem(ModBlocks.LAUNCH_PAD.get(), props()) {
                @Override
                public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
                    tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.launch_pad").withStyle(TextFormatting.GRAY));
                }
            });

    private static RegistryObject<Item> payload(String name, PayloadType type, int stack) {
        return ITEMS.register(name, () -> new PayloadItem(type, props().stacksTo(stack)));
    }

    private static RegistryObject<Item> part(String name) {
        return ITEMS.register(name, () -> new PartItem(props()));
    }

    private static Item.Properties props() {
        return new Item.Properties().tab(FpvDrones.TAB);
    }

    private ModItems() {
    }
}
