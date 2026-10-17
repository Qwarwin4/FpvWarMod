package com.qwarwin.fpvdrones.network;

import com.qwarwin.fpvdrones.FpvDrones;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.fml.network.NetworkDirection;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.simple.SimpleChannel;

import java.util.Optional;

public final class Network {
    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            FpvDrones.id("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static boolean registered;

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        int id = 0;
        CHANNEL.registerMessage(id++, DroneControlPacket.class, DroneControlPacket::encode, DroneControlPacket::decode,
                DroneControlPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, DroneActionPacket.class, DroneActionPacket::encode, DroneActionPacket::decode,
                DroneActionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, LauncherUpdatePacket.class, LauncherUpdatePacket::encode, LauncherUpdatePacket::decode,
                LauncherUpdatePacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, SessionStatePacket.class, SessionStatePacket::encode, SessionStatePacket::decode,
                SessionStatePacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, DroneCorrectionPacket.class, DroneCorrectionPacket::encode, DroneCorrectionPacket::decode,
                DroneCorrectionPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(id++, WeaponInputPacket.class, WeaponInputPacket::encode, WeaponInputPacket::decode,
                WeaponInputPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(id++, OpenLauncherPacket.class, OpenLauncherPacket::encode, OpenLauncherPacket::decode,
                OpenLauncherPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendTo(ServerPlayerEntity player, Object msg) {
        if (player.connection != null) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
        }
    }

    private Network() {
    }
}
