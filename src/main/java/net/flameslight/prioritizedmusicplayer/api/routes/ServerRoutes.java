package net.flameslight.prioritizedmusicplayer.api.routes;

import net.flameslight.prioritizedmusicplayer.network.PMPNetwork;
import net.flameslight.prioritizedmusicplayer.network.packets.S2CSetPrioritizedMusicConcealedPacket;
import net.flameslight.prioritizedmusicplayer.network.packets.S2CUnpauseSoundPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * API mods use on the server to control Prioritized Music Player on a player's client. Each method sends a message
 * that runs the matching {@link CommonRoutes} method on that client. A call for a player whose client lacks PMP does
 * nothing. Use {@link #isPMPInstalled(ServerPlayer)} to check first.
 * Part of the API: methods are never removed or changed, a new method raises {@link CommonRoutes#API_VERSION}, read
 * through {@link CommonRoutes#apiVersion()}.
 */
public final class ServerRoutes {
    /**
     * Tells whether a player's client has PMP. The other methods do nothing for a client without it, so mods can use
     * this to skip their own work for that player.
     *
     * @param player the player
     * @return true if the player's client has PMP
     */
    public static boolean isPMPInstalled(ServerPlayer player) {
        return PMPNetwork.hasPMP(player);
    }

    /**
     * Allows paused instances of a soundsToPauseUntilAllowed sound to resume on a player's client
     * (CommonRoutes.unpauseSound).
     *
     * @param player the player whose client is affected
     * @param sound  the sound name, without a namespace
     */
    public static void unpauseSound(ServerPlayer player, String sound) {
        PMPNetwork.sendToPlayer(player, new S2CUnpauseSoundPacket(sound));
    }

    /**
     * Hides (fades out, keeps playing) or reveals a playing prioritized sound on a player's client
     * (CommonRoutes.setPrioritizedMusicConcealed).
     *
     * @param player    the player whose client is affected
     * @param sound     the sound name, without a namespace
     * @param concealed true to hide, false to reveal
     */
    public static void setPrioritizedMusicConcealed(ServerPlayer player, String sound, boolean concealed) {
        PMPNetwork.sendToPlayer(player, new S2CSetPrioritizedMusicConcealedPacket(sound, concealed));
    }
}
