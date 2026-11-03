package net.flameslight.prioritizedmusicplayer.server;

import net.flameslight.prioritizedmusicplayer.api.event.ServerRegisterPrioritizationEvent;
import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler.ModdedEntry;
import net.flameslight.prioritizedmusicplayer.logger.ModLogger;
import net.minecraftforge.fml.ModLoader;

import java.util.List;

/**
 * Keeps the entries mods registered through ServerRegisterPrioritizationEvent, sent to every player who joins.
 * References no client class.
 */
public final class ServerMusicEntries {
    private static volatile List<ModdedEntry> entries = List.of();
    private static boolean registered = false;

    /**
     * Posts ServerRegisterPrioritizationEvent to every mod and keeps what they registered. Runs once, during common
     * setup on the main thread.
     */
    public static void registerServerModsEntries() {
        if (registered) {
            return;
        }
        registered = true;

        ServerRegisterPrioritizationEvent event = new ServerRegisterPrioritizationEvent();
        ModLoader.get().postEvent(event);
        entries = event.getEntries();
        ModLogger.debug("ServerRegisterPrioritizationEvent posted, {} entries registered.", entries.size());
    }

    /**
     * Returns the entries mods registered through ServerRegisterPrioritizationEvent.
     *
     * @return the entries, immutable, empty before the event was posted
     */
    public static List<ModdedEntry> getEntries() {
        return entries;
    }
}
