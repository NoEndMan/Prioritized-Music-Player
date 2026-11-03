package net.flameslight.prioritizedmusicplayer.api.event;

import net.flameslight.prioritizedmusicplayer.api.routes.CommonRoutes;
import net.flameslight.prioritizedmusicplayer.config.ClientConfig;
import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler;
import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler.EntryList;
import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler.ModdedEntry;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.event.IModBusEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Base of the mod bus events where mods register their entries for Prioritized Music Player's client config. Subscribe
 * to {@link ClientRegisterPrioritizationEvent} or {@link ServerRegisterPrioritizationEvent}, never to this class.
 * <p>
 * Sound names are the name only, without a namespace (for example music_disc.cat). Entries are only ever added to the
 * client config, never removed or overwritten. An entry already in the config is not written again, and is listed as a
 * duplicate in "mod list that adds to the configuration.txt". The user may block mods or whitelist some of them in the
 * client config.
 * <p>
 * Part of the API: methods are never removed or changed, a new method raises {@link CommonRoutes#API_VERSION}.
 */
public abstract class RegisterPrioritizationEvent extends Event implements IModBusEvent {
    private final List<ModdedEntry> entries = new ArrayList<>();

    /**
     * Adds a prioritizedMusicList entry: the sound is prioritized whenever it starts playing.
     *
     * @param modId    your mod id
     * @param sound    the sound name, without a namespace
     * @param priority the priority, a higher value wins
     */
    public void addPrioritizedMusic(String modId, String sound, int priority) {
        add(modId, EntryList.PRIORITIZED_MUSIC_LIST, sound + ClientConfig.PRIORITY_SEPARATOR + priority);
    }

    /**
     * Adds a soundsToPauseUntilAllowed rule: the paused sound is paused after it starts, until the conditional sound
     * finishes playing or CommonRoutes.unpauseSound (or ServerRoutes.unpauseSound) allows it.
     *
     * @param modId            your mod id
     * @param conditionalSound the sound whose finish allows the paused sound, without a namespace
     * @param pausedSound      the sound to pause, without a namespace
     */
    public void addSoundToPauseUntilAllowed(String modId, String conditionalSound, String pausedSound) {
        add(modId, EntryList.SOUNDS_TO_PAUSE_UNTIL_ALLOWED, conditionalSound + ClientConfig.PAUSE_RULE_SEPARATOR + pausedSound);
    }

    /**
     * Returns the entries registered so far. Read by Prioritized Music Player after the event was posted.
     *
     * @return an unmodifiable copy of the registered entries
     */
    public List<ModdedEntry> getEntries() {
        return List.copyOf(entries);
    }

    /**
     * Validates an entry and keeps it if valid. Invalid entries are logged as a warning.
     *
     * @param modId    the adding mod's id
     * @param list     the config list it goes to
     * @param rawEntry the entry in the config's format
     */
    private void add(String modId, EntryList list, String rawEntry) {
        ModdedEntry entry = ModdedAddedEntriesHandler.createEntry(modId, list, rawEntry);
        if (entry != null) {
            entries.add(entry);
        }
    }
}
