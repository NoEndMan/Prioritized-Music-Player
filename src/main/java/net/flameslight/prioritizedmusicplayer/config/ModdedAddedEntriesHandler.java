package net.flameslight.prioritizedmusicplayer.config;

import net.flameslight.prioritizedmusicplayer.PrioritizedMusicPlayer;
import net.flameslight.prioritizedmusicplayer.api.event.ClientRegisterPrioritizationEvent;
import net.flameslight.prioritizedmusicplayer.logger.ModLogger;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.loading.FMLPaths;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes entries other mods add into the client config, and lists them per mod in
 * "mod list that adds to the configuration.txt" next to it.
 * <p>
 * Client mods register their entries through ClientRegisterPrioritizationEvent, posted once when the client config
 * first loads. Mods on the server register theirs through ServerRegisterPrioritizationEvent, and the server sends them
 * to every player who joins, where {@link #applyServerModEntries(List)} applies them.
 * <p>
 * Entries are only ever added: an entry already in the config is not written again and is only
 * listed as a duplicate in the text file, without logging. The block switches and whitelists of the client config
 * decide which mods may write.
 */
public final class ModdedAddedEntriesHandler {
    /** Name of the text file listing the entries mods added, in the mod's config folder. */
    public static final String ENTRIES_FILE_NAME = "mod list that adds to the configuration.txt";

    private static final String ADDED = "added";
    private static final String DUPLICATE = "duplicate";
    private static final String FILE_HEADER = "# Entries other mods added to client-config.toml. Written by Prioritized Music Player.";

    /** Set once the client mods' entries were handled, config reloads don't handle them again. */
    private static boolean clientEntriesHandled = false;

    /** The client config lists mods may add entries to. */
    public enum EntryList {
        PRIORITIZED_MUSIC_LIST("prioritizedMusicList"),
        SOUNDS_TO_PAUSE_UNTIL_ALLOWED("soundsToPauseUntilAllowed");

        /** The list's config key. */
        public final String key;

        EntryList(String key) {
            this.key = key;
        }
    }

    /**
     * One validated entry a mod adds.
     *
     * @param modId the adding mod's id
     * @param list  the config list it goes to
     * @param entry the entry, in the config's own format
     */
    public record ModdedEntry(String modId, EntryList list, String entry) {
    }

    /**
     * Validates and normalizes an entry a mod adds. Invalid entries are logged as a warning.
     *
     * @param modId    the adding mod's id
     * @param list     the config list it goes to
     * @param rawEntry the entry in the config's format: "sound:priority" or "conditionalSound->pausedSound"
     * @return the entry, null if the mod id or the entry is invalid
     */
    @Nullable
    public static ModdedEntry createEntry(@Nullable String modId, EntryList list, @Nullable String rawEntry) {
        if (modId == null || modId.isBlank()) {
            ModLogger.warn("A mod tried to add '{}' to {} without a mod id, ignored.", rawEntry, list.key);
            return null;
        }

        String normalized = switch (list) {
            case PRIORITIZED_MUSIC_LIST -> {
                ClientConfig.PrioritizedEntry parsed = ClientConfig.parsePrioritizedEntry(rawEntry);
                yield parsed == null ? null : parsed.toConfigString();
            }
            case SOUNDS_TO_PAUSE_UNTIL_ALLOWED -> {
                ClientConfig.PauseRule parsed = ClientConfig.parsePauseRule(rawEntry);
                yield parsed == null ? null : parsed.toConfigString();
            }
        };

        if (normalized == null) {
            ModLogger.warn("{} tried to add a malformed {} entry '{}' (sound names are the name only, without a namespace), ignored.",
                    modId, list.key, rawEntry);
            return null;
        }

        return new ModdedEntry(modId.trim(), list, normalized);
    }

    /**
     * Posts ClientRegisterPrioritizationEvent to every mod and writes the entries allowed by blockClientModsConfigOverrides
     * and clientModsConfigOverridesWhitelist. Runs once, on the client config's first load.
     */
    public static void registerClientModsEntries() {
        if (clientEntriesHandled) {
            return;
        }
        clientEntriesHandled = true;

        if (ClientConfig.isBlockClientModsConfigOverrides()) {
            ModLogger.debug("blockClientModsConfigOverrides is on, client mods can't add entries to the client config.");
            return;
        }

        ClientRegisterPrioritizationEvent event = new ClientRegisterPrioritizationEvent();
        ModLoader.get().postEvent(event);
        ModLogger.debug("ClientRegisterPrioritizationEvent posted, {} entries registered.", event.getEntries().size());

        List<ModdedEntry> allowed = new ArrayList<>();
        Set<String> notWhitelisted = new LinkedHashSet<>();
        for (ModdedEntry entry : event.getEntries()) {
            if (ClientConfig.isClientModWhitelisted(entry.modId())) {
                allowed.add(entry);
            } else {
                notWhitelisted.add(entry.modId());
            }
        }
        notWhitelisted.forEach(modId ->
                ModLogger.debug("{} is not in clientModsConfigOverridesWhitelist, its entries are ignored.", modId));

        write(allowed);
    }

    /**
     * Writes the entries the server's mods registered through ServerRegisterPrioritizationEvent, as far as
     * blockServerModsConfigOverrides and serverModsConfigOverridesWhitelist allow. Client main thread, when the
     * server sends them on login.
     *
     * @param entries the validated entries the server sent
     */
    public static void applyServerModEntries(List<ModdedEntry> entries) {
        if (ClientConfig.isBlockServerModsConfigOverrides()) {
            ModLogger.debug("blockServerModsConfigOverrides is on, {} entries from the server's mods are ignored.", entries.size());
            return;
        }

        List<ModdedEntry> allowed = new ArrayList<>();
        Set<String> notWhitelisted = new LinkedHashSet<>();
        for (ModdedEntry entry : entries) {
            if (ClientConfig.isServerModWhitelisted(entry.modId())) {
                allowed.add(entry);
            } else {
                notWhitelisted.add(entry.modId());
            }
        }
        notWhitelisted.forEach(modId ->
                ModLogger.debug("{} is not in serverModsConfigOverridesWhitelist, its entries are ignored.", modId));

        write(allowed);
    }

    /**
     * Adds entries to the client config and updates the text file. An entry already in the config is not written:
     * it is listed as a duplicate, unless the same mod added it in an earlier launch. Saves the config and refreshes
     * its caches once, only if something was added.
     *
     * @param entries the allowed entries to add
     */
    private static void write(List<ModdedEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }

        Path file = FMLPaths.CONFIGDIR.get().resolve(PrioritizedMusicPlayer.MOD_ID).resolve(ENTRIES_FILE_NAME);
        Map<String, LinkedHashSet<String>> linesPerMod = readEntriesFile(file);

        Map<EntryList, List<String>> configLists = new EnumMap<>(EntryList.class);
        configLists.put(EntryList.PRIORITIZED_MUSIC_LIST, new ArrayList<>(ClientConfig.PRIORITIZED_MUSIC_LIST.get()));
        configLists.put(EntryList.SOUNDS_TO_PAUSE_UNTIL_ALLOWED, new ArrayList<>(ClientConfig.SOUNDS_TO_PAUSE_UNTIL_ALLOWED.get()));

        boolean configChanged = false;
        boolean fileChanged = false;

        for (ModdedEntry entry : entries) {
            String addedLine = ADDED + " " + entry.list().key + " " + entry.entry();
            String duplicateLine = DUPLICATE + " " + entry.list().key + " " + entry.entry();
            Set<String> modLines = linesPerMod.computeIfAbsent(entry.modId(), k -> new LinkedHashSet<>());
            List<String> configList = configLists.get(entry.list());

            if (!isInConfig(entry, configList)) {
                configList.add(entry.entry());
                configChanged = true;
                fileChanged |= modLines.add(addedLine);
                fileChanged |= modLines.remove(duplicateLine);
            } else if (!modLines.contains(addedLine)) {
                fileChanged |= modLines.add(duplicateLine);
            }
        }

        if (configChanged) {
            ClientConfig.PRIORITIZED_MUSIC_LIST.set(configLists.get(EntryList.PRIORITIZED_MUSIC_LIST));
            ClientConfig.SOUNDS_TO_PAUSE_UNTIL_ALLOWED.set(configLists.get(EntryList.SOUNDS_TO_PAUSE_UNTIL_ALLOWED));
            ClientConfig.CLIENT_SPEC.save();
            ClientConfig.refresh();
        }

        if (fileChanged) {
            writeEntriesFile(file, linesPerMod);
        }
    }

    /**
     * Tells whether an entry is already in its config list: the same sound for prioritizedMusicList, the same exact
     * rule for soundsToPauseUntilAllowed. Malformed config entries never match.
     *
     * @param entry      the entry to add
     * @param configList the list's current entries
     * @return true if already present
     */
    private static boolean isInConfig(ModdedEntry entry, List<String> configList) {
        return switch (entry.list()) {
            case PRIORITIZED_MUSIC_LIST -> {
                ClientConfig.PrioritizedEntry added = ClientConfig.parsePrioritizedEntry(entry.entry());
                for (String existing : configList) {
                    ClientConfig.PrioritizedEntry parsed = ClientConfig.parsePrioritizedEntry(existing);
                    if (parsed != null && added != null && parsed.sound().equals(added.sound())) {
                        yield true;
                    }
                }
                yield false;
            }
            case SOUNDS_TO_PAUSE_UNTIL_ALLOWED -> {
                ClientConfig.PauseRule added = ClientConfig.parsePauseRule(entry.entry());
                for (String existing : configList) {
                    if (Objects.equals(ClientConfig.parsePauseRule(existing), added)) {
                        yield true;
                    }
                }
                yield false;
            }
        };
    }

    /**
     * Reads the text file's lines per mod. A missing or unreadable file reads as empty.
     *
     * @param file the text file
     * @return each mod id's lines, in file order
     */
    private static Map<String, LinkedHashSet<String>> readEntriesFile(Path file) {
        Map<String, LinkedHashSet<String>> linesPerMod = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return linesPerMod;
        }

        try {
            String currentModId = null;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentModId = line.substring(1, line.length() - 1).trim();
                    linesPerMod.computeIfAbsent(currentModId, k -> new LinkedHashSet<>());
                } else if (currentModId != null) {
                    linesPerMod.get(currentModId).add(line);
                }
            }
        } catch (IOException e) {
            ModLogger.error("Could not read " + file + ", it will be rewritten.", e);
        }
        return linesPerMod;
    }

    /**
     * Rewrites the text file, grouped per mod.
     *
     * @param file        the text file
     * @param linesPerMod each mod id's lines
     */
    private static void writeEntriesFile(Path file, Map<String, LinkedHashSet<String>> linesPerMod) {
        List<String> lines = new ArrayList<>();
        lines.add(FILE_HEADER);
        linesPerMod.forEach((modId, modLines) -> {
            if (modLines.isEmpty()) {
                return;
            }
            lines.add("");
            lines.add("[" + modId + "]");
            lines.addAll(modLines);
        });

        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            ModLogger.error("Could not write " + file + ".", e);
        }
    }
}
