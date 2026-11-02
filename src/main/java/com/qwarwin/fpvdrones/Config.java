package com.qwarwin.fpvdrones;

import net.minecraftforge.common.ForgeConfigSpec;

// world/serverconfig/fpvdrones-server.toml
public final class Config {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue EXPLOSIONS_BREAK_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue SIGNAL_OBSTRUCTION;

    public static final ForgeConfigSpec.DoubleValue FPV_RANGE;
    public static final ForgeConfigSpec.DoubleValue MAVIC_RANGE;
    public static final ForgeConfigSpec.DoubleValue FPV_SPEED;
    public static final ForgeConfigSpec.DoubleValue MAVIC_SPEED;
    public static final ForgeConfigSpec.IntValue FPV_BATTERY_SECONDS;
    public static final ForgeConfigSpec.IntValue MAVIC_BATTERY_SECONDS;

    public static final ForgeConfigSpec.DoubleValue POWER_PG7VS;
    public static final ForgeConfigSpec.DoubleValue POWER_OG7;
    public static final ForgeConfigSpec.DoubleValue POWER_CANISTER;
    public static final ForgeConfigSpec.DoubleValue POWER_RGD5;
    public static final ForgeConfigSpec.DoubleValue POWER_VOG17M;
    public static final ForgeConfigSpec.DoubleValue FRAGMENT_DAMAGE;

    public static final ForgeConfigSpec.DoubleValue SHAHED_POWER;
    public static final ForgeConfigSpec.DoubleValue GERAN2_POWER;
    public static final ForgeConfigSpec.DoubleValue GERAN5_POWER;
    public static final ForgeConfigSpec.BooleanValue STRIKE_FIRE;
    public static final ForgeConfigSpec.DoubleValue SHAHED_SPEED;
    public static final ForgeConfigSpec.DoubleValue GERAN5_SPEED;
    public static final ForgeConfigSpec.DoubleValue SHAHED238_SPEED;
    public static final ForgeConfigSpec.DoubleValue STRIKE_MAX_RANGE;
    public static final ForgeConfigSpec.IntValue CRUISE_ALTITUDE;

    public static final ForgeConfigSpec.DoubleValue TRUCK_SPEED;
    public static final ForgeConfigSpec.BooleanValue SHELLS_BREAK_BLOCKS;
    public static final ForgeConfigSpec.DoubleValue AIRBURST_RELIABILITY;
    public static final ForgeConfigSpec.DoubleValue ATGM_POWER;
    public static final ForgeConfigSpec.DoubleValue ATGM_DAMAGE;
    public static final ForgeConfigSpec.DoubleValue ATGM_PENETRATION;
    public static final ForgeConfigSpec.DoubleValue INTERCEPTOR_SPEED;
    public static final ForgeConfigSpec.IntValue INTERCEPTOR_BATTERY;
    public static final ForgeConfigSpec.DoubleValue INTERCEPTOR_REACH;
    public static final ForgeConfigSpec.DoubleValue RADAR_RANGE;
    public static final ForgeConfigSpec.DoubleValue RADAR_LINK;
    public static final ForgeConfigSpec.DoubleValue EW_TRUCK_RADIUS;
    public static final ForgeConfigSpec.DoubleValue EW_STATION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue EW_LINK_LOSS;
    public static final ForgeConfigSpec.DoubleValue EW_GNSS_LOSS;
    public static final ForgeConfigSpec.DoubleValue RELAY_RANGE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        EXPLOSIONS_BREAK_BLOCKS = b.define("explosionsBreakBlocks", true);
        SIGNAL_OBSTRUCTION = b.comment("Blocks between operator and drone cut the link range")
                .define("signalObstruction", true);
        b.pop();

        b.push("drones");
        FPV_RANGE = b.defineInRange("fpvRange", 700.0, 32.0, 10000.0);
        MAVIC_RANGE = b.defineInRange("mavicRange", 900.0, 32.0, 10000.0);
        FPV_SPEED = b.defineInRange("fpvSpeed", 1.0, 0.2, 3.0);
        MAVIC_SPEED = b.defineInRange("mavicSpeed", 1.0, 0.2, 3.0);
        FPV_BATTERY_SECONDS = b.defineInRange("fpvBatterySeconds", 300, 10, 36000);
        MAVIC_BATTERY_SECONDS = b.defineInRange("mavicBatterySeconds", 1200, 10, 36000);
        b.pop();

        b.comment("Explosion power, TNT = 4").push("warheads");
        POWER_PG7VS = b.defineInRange("pg7vs", 6.0, 0.5, 20.0);
        POWER_OG7 = b.defineInRange("og7", 5.0, 0.5, 20.0);
        POWER_CANISTER = b.defineInRange("explosiveCanister", 6.5, 0.5, 20.0);
        POWER_RGD5 = b.defineInRange("rgd5", 2.0, 0.5, 20.0);
        POWER_VOG17M = b.defineInRange("vog17m", 2.8, 0.5, 20.0);
        FRAGMENT_DAMAGE = b.comment("Multiplier for shrapnel and shaped charge damage")
                .defineInRange("fragmentDamage", 1.0, 0.0, 10.0);
        b.pop();

        b.push("strike");
        SHAHED_POWER = b.defineInRange("shahedPower", 9.0, 1.0, 30.0);
        GERAN2_POWER = b.defineInRange("geran2Power", 11.0, 1.0, 30.0);
        GERAN5_POWER = b.defineInRange("geran5Power", 14.0, 1.0, 30.0);
        STRIKE_FIRE = b.define("fire", true);
        SHAHED_SPEED = b.comment("Blocks per tick, 1.5 = 108 km/h").defineInRange("shahedSpeed", 1.5, 0.3, 5.0);
        GERAN5_SPEED = b.defineInRange("geran5Speed", 3.2, 0.3, 8.0);
        SHAHED238_SPEED = b.defineInRange("shahed238Speed", 2.8, 0.3, 8.0);
        STRIKE_MAX_RANGE = b.defineInRange("maxRange", 25000.0, 50.0, 200000.0);
        CRUISE_ALTITUDE = b.comment("Cruise height above launch point").defineInRange("cruiseAltitude", 45, 10, 200);
        b.pop();

        b.push("truck");
        TRUCK_SPEED = b.defineInRange("speed", 1.0, 0.2, 3.0);
        b.pop();

        b.push("armour");
        SHELLS_BREAK_BLOCKS = b.comment("30 mm impact rounds dig small holes").define("shellsBreakBlocks", false);
        AIRBURST_RELIABILITY = b.comment("Share of 30 mm airburst rounds whose fuse goes off next to a target. Small, fast drones are hard to hit: lower is more realistic, 1.0 is the old arcade behaviour")
                .defineInRange("airburstReliability", 0.45, 0.0, 1.0);
        ATGM_POWER = b.comment("Base blast of an ATGM warhead (TNT = 4). Kornet x1.8, Javelin x1.7")
                .defineInRange("atgmPower", 5.0, 0.5, 20.0);
        ATGM_DAMAGE = b.comment("Base damage of a direct ATGM hit (same multipliers)")
                .defineInRange("atgmDamage", 250.0, 1.0, 2000.0);
        ATGM_PENETRATION = b.comment("How deep the shaped-charge jet bores through blocks, base value (same multipliers)")
                .defineInRange("atgmPenetration", 5.0, 0.0, 30.0);
        b.pop();

        b.push("ew");
        EW_TRUCK_RADIUS = b.comment("Reach of the jammer on the Ural, blocks").defineInRange("truckRadius", 160.0, 16.0, 2000.0);
        EW_STATION_RADIUS = b.comment("Reach of the jamming tower, blocks").defineInRange("stationRadius", 240.0, 16.0, 3000.0);
        EW_LINK_LOSS = b.comment("Chance per second that an FPV or Mavic deep in a jammer's reach loses its link (less at the edge and close to the pilot)")
                .defineInRange("linkLoss", 0.12, 0.0, 1.0);
        EW_GNSS_LOSS = b.comment("Chance per second that a Shahed deep in a jammer's reach has its satellite navigation spoofed and drifts off the target (less for Geran-2/-5 with anti-jam antennas and under a relay)")
                .defineInRange("gnssLoss", 0.08, 0.0, 1.0);
        RELAY_RANGE = b.comment("Reach of a relay mast: strike drones can be sent this far past it, and relays this far apart chain up")
                .defineInRange("relayRange", 12000.0, 100.0, 100000.0);
        b.pop();

        b.push("airDefence");
        INTERCEPTOR_SPEED = b.comment("Top speed of the interceptor drone, blocks per tick (4 = 290 km/h)")
                .defineInRange("interceptorSpeed", 4.0, 1.0, 10.0);
        INTERCEPTOR_BATTERY = b.comment("How long an interceptor stays up, seconds").defineInRange("interceptorBattery", 180, 20, 1800);
        INTERCEPTOR_REACH = b.comment("How far from the target an interceptor can be and still be sent after it, blocks")
                .defineInRange("interceptorReach", 2500.0, 50.0, 20000.0);
        RADAR_RANGE = b.comment("Search radar reach for Shahed-size targets, blocks (quadcopters show at 230 at most)")
                .defineInRange("radarRange", 2000.0, 100.0, 10000.0);
        RADAR_LINK = b.comment("A radar console sees the radars of its side this far away").defineInRange("radarLink", 4000.0, 50.0, 50000.0);
        b.pop();

        SPEC = b.build();
    }

    private Config() {
    }
}
