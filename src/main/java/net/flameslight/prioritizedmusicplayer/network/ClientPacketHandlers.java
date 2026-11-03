package net.flameslight.prioritizedmusicplayer.network;

import net.flameslight.prioritizedmusicplayer.api.routes.CommonRoutes;
import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler;
import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler.ModdedEntry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Client side effects of the server's messages. The only class the packets call into, through DistExecutor, so no
 * client class is ever loaded on a dedicated server.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPacketHandlers {
    /**
     * Allows paused instances of a sound to resume.
     *
     * @param sound the sound name, without a namespace
     */
    public static void unpauseSound(String sound) {
        CommonRoutes.unpauseSound(sound);
    }

    /**
     * Hides or reveals a playing prioritized sound.
     *
     * @param sound     the sound name, without a namespace
     * @param concealed true to hide, false to reveal
     */
    public static void setPrioritizedMusicConcealed(String sound, boolean concealed) {
        CommonRoutes.setPrioritizedMusicConcealed(sound, concealed);
    }

    /**
     * Validates the entries the server sent again, then writes the valid ones to the client config.
     *
     * @param received the entries as received
     */
    public static void applyServerModEntries(List<ModdedEntry> received) {
        List<ModdedEntry> validated = new ArrayList<>(received.size());
        for (ModdedEntry entry : received) {
            ModdedEntry valid = ModdedAddedEntriesHandler.createEntry(entry.modId(), entry.list(), entry.entry());
            if (valid != null) {
                validated.add(valid);
            }
        }

        ModdedAddedEntriesHandler.applyServerModEntries(validated);
    }
}
