package net.flameslight.prioritizedmusicplayer.network;

import net.flameslight.prioritizedmusicplayer.PrioritizedMusicPlayer;
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
 * The mod's network channel. Every message goes from the server to a client.
 * <p>
 * PMP is optional on both sides. A client with PMP joins servers without it, with its client features only. A server
 * with PMP accepts clients without it, and sends PMP messages only to clients that have it. When both sides have PMP,
 * the protocol version must match.
 */
public final class PMPNetwork {
    /** The channel's protocol version, also reported by PMP's server list display test. */
    public static final String PROTOCOL_VERSION = "1";
    private static SimpleChannel channel;

    /**
     * Creates the channel and registers every message. Both sides accept the same protocol version, a remote side
     * without the channel, and a vanilla remote side. Called once from the mod constructor, on both sides.
     */
    @SuppressWarnings("removal")
    public static void register() {
        channel = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(PrioritizedMusicPlayer.MOD_ID, "main"))
                .networkProtocolVersion(() -> PROTOCOL_VERSION)
                .clientAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals))
                .serverAcceptedVersions(NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION::equals))
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
    }

    /**
     * Sends a message to one player's client. Does nothing for a client without PMP.
     *
     * @param player  the receiving player
     * @param message a registered message
     */
    public static void sendToPlayer(ServerPlayer player, Object message) {
        if (!hasPMP(player)) {
            return;
        }

        channel.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /**
     * Tells whether a player's client has PMP, from the channel list Forge negotiated at login. A field lookup, no
     * network call. Internal, not part of the API.
     *
     * @param player the player
     * @return true if the player's client has PMP's channel
     */
    public static boolean hasPMP(ServerPlayer player) {
        return channel.isRemotePresent(player.connection.connection);
    }
}
