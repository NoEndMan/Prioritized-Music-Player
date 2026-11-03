package net.flameslight.prioritizedmusicplayer.network;

import net.flameslight.prioritizedmusicplayer.PrioritizedMusicPlayer;
import net.flameslight.prioritizedmusicplayer.network.packets.S2CServerModEntriesPacket;
import net.flameslight.prioritizedmusicplayer.network.packets.S2CSetPrioritizedMusicConcealedPacket;
import net.flameslight.prioritizedmusicplayer.network.packets.S2CUnpauseSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/**
 * The mod's network channel. Prioritized Music Player is required on both sides, so the protocol version must match
 * exactly. Every message goes from the server to a client.
 */
public final class PMPNetwork {
    private static final String PROTOCOL_VERSION = "1";
    private static SimpleChannel channel;

    /**
     * Creates the channel and registers every message. Called once from the mod constructor, on both sides.
     */
    @SuppressWarnings("removal")
    public static void register() {
        channel = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(PrioritizedMusicPlayer.MOD_ID, "main"))
                .networkProtocolVersion(() -> PROTOCOL_VERSION)
                .clientAcceptedVersions(PROTOCOL_VERSION::equals)
                .serverAcceptedVersions(PROTOCOL_VERSION::equals)
                .simpleChannel();

        int id = 0;
        channel.registerMessage(id++, S2CUnpauseSoundPacket.class,
                S2CUnpauseSoundPacket::encode,
                S2CUnpauseSoundPacket::decode,
                S2CUnpauseSoundPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        channel.registerMessage(id++, S2CSetPrioritizedMusicConcealedPacket.class,
                S2CSetPrioritizedMusicConcealedPacket::encode,
                S2CSetPrioritizedMusicConcealedPacket::decode,
                S2CSetPrioritizedMusicConcealedPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));

        channel.registerMessage(id++, S2CServerModEntriesPacket.class,
                S2CServerModEntriesPacket::encode,
                S2CServerModEntriesPacket::decode,
                S2CServerModEntriesPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    /**
     * Sends a message to one player's client.
     *
     * @param player  the receiving player
     * @param message a registered message
     */
    public static void sendToPlayer(ServerPlayer player, Object message) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
