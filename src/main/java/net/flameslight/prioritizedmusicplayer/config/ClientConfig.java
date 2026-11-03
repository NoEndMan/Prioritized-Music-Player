package net.flameslight.prioritizedmusicplayer.config;

import net.flameslight.prioritizedmusicplayer.logger.ModLogger;
import net.minecraftforge.common.ForgeConfigSpec;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mod's client config (config/prioritizedmusicplayer/client-config.toml).
 * <p>
 * The music handler runs on the client main thread and on the sound engine threads, so every option is parsed once
 * into volatile, immutable caches by {@link #refresh()} and read only through the getters below. Getters return the
 * defaults until the config file has loaded.
 * <p>
 * Sound names are the name only, without a namespace (the sound location's path, for example music_disc.cat), the
 * same way the music handler compares them.
 */
public class ClientConfig {
    /** Default of fadeInTicks (20 ticks = 1 second). */
    public static final int DEFAULT_FADE_IN_TICKS = 20;
    /** Default of fadeOutTicks (20 ticks = 1 second). */
    public static final int DEFAULT_FADE_OUT_TICKS = 20;
    /** Default of fadeOutHearingPercent. */
    public static final int DEFAULT_FADE_OUT_HEARING_PERCENT = 60;
    /** Default of debugMod. */
    public static final boolean DEFAULT_DEBUG_MOD = false;
    /** Separates a sound from its priority in a prioritizedMusicList entry. */
    public static final String PRIORITY_SEPARATOR = ":";
    /** Separates the conditional sound from the paused sound in a soundsToPauseUntilAllowed entry. */
    public static final String PAUSE_RULE_SEPARATOR = "->";

    /** The registered client config spec. */
    public static final ForgeConfigSpec CLIENT_SPEC;
    /** fadeInTicks option. */
    public static final ForgeConfigSpec.IntValue FADE_IN_TICKS;
    /** fadeOutTicks option. */
    public static final ForgeConfigSpec.IntValue FADE_OUT_TICKS;
    /** fadeOutHearingPercent option. */
    public static final ForgeConfigSpec.IntValue FADE_OUT_HEARING_PERCENT;
    /** soundsToPauseUntilAllowed option. */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> SOUNDS_TO_PAUSE_UNTIL_ALLOWED;
    /** prioritizedMusicList option. */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> PRIORITIZED_MUSIC_LIST;
    /** blockClientModsConfigOverrides option. */
    public static final ForgeConfigSpec.BooleanValue BLOCK_CLIENT_MODS_CONFIG_OVERRIDES;
    /** clientModsConfigOverridesWhitelist option. */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CLIENT_MODS_CONFIG_OVERRIDES_WHITELIST;
    /** debugMod option. */
    public static final ForgeConfigSpec.BooleanValue DEBUG_MOD;

    private static volatile int fadeInTicks = DEFAULT_FADE_IN_TICKS;
    private static volatile int fadeOutTicks = DEFAULT_FADE_OUT_TICKS;
    private static volatile float fadeOutHearing = DEFAULT_FADE_OUT_HEARING_PERCENT / 100f;
    private static volatile Map<String, Integer> prioritizedMusic = Map.of();
    /** Paused sound to the conditional sounds of its rules. */
    private static volatile Map<String, Set<String>> pausedSoundToConditionals = Map.of();
    /** Conditional sound to the paused sounds of its rules. */
    private static volatile Map<String, Set<String>> conditionalToPausedSounds = Map.of();
    private static volatile boolean blockClientModsConfigOverrides = false;
    private static volatile Set<String> clientModsWhitelist = Set.of();

    private static volatile boolean isDebugMod = DEFAULT_DEBUG_MOD;
    private static volatile boolean isDebugModResolved = false;

    /**
     * One parsed prioritizedMusicList entry.
     *
     * @param sound    the sound name, without a namespace
     * @param priority the priority, a higher value wins
     */
    public record PrioritizedEntry(String sound, int priority) {
        /**
         * Formats the entry the way the config stores it.
         *
         * @return "sound:priority"
         */
        public String toConfigString() {
            return sound + PRIORITY_SEPARATOR + priority;
        }
    }

    /**
     * One parsed soundsToPauseUntilAllowed rule.
     *
     * @param conditionalSound the sound whose finish allows the paused sound to play
     * @param pausedSound      the sound paused after it starts
     */
    public record PauseRule(String conditionalSound, String pausedSound) {
        /**
         * Formats the rule the way the config stores it.
         *
         * @return "conditionalSound->pausedSound"
         */
        public String toConfigString() {
            return conditionalSound + PAUSE_RULE_SEPARATOR + pausedSound;
        }
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                "Prioritized Music Player client configuration.",
                "Sound names are the name only, without a namespace, for example \"music_disc.cat\" and not",
                "\"minecraft:music_disc.cat\". A sound name never contains ':'. Malformed entries are skipped and",
                "logged as a warning.",
                "Other mods may add entries to soundsToPauseUntilAllowed and prioritizedMusicList.",
                "They never remove or overwrite an entry. Every entry they add, or tried to",
                "add while it already existed, is listed per mod in 'mod list that adds to the configuration.txt'",
                "next to this file."
        ).push("general");

        FADE_IN_TICKS = builder
                .comment("Ticks a prioritized sound takes to fade in when it becomes the current prioritized playing (20 ticks = 1 second).",
                        "The sounds it mutes or weakens fade down over the same ticks, in step with it.")
                .defineInRange("fadeInTicks", DEFAULT_FADE_IN_TICKS, 1, 1200);

        FADE_OUT_TICKS = builder
                .comment("Ticks a prioritized sound takes to fade out when it is concealed or stopped by name (a stop sound",
                        "packet or /stopsound) (20 ticks = 1 second). The sounds it muted fade back in over the same ticks, in step with it.")
                .defineInRange("fadeOutTicks", DEFAULT_FADE_OUT_TICKS, 1, 1200);

        FADE_OUT_HEARING_PERCENT = builder
                .comment("the set percentage volume to played sounds from different category of a playing prioritized sound:",
                        "When a prioritized sound is playing, all sounds volume is reduced to allow the prioritized sound to be heard.",
                        "For sounds of different category than the prioritized, 0 would mute them (concealed, still playing) while 100 ",
                        "would make them to be heard normally. Less or non prioritized sounds of the same category as the current prioritized ",
                        "playing are always muted.",
                        "Affected categories: music, ambient, weather, records and hostile. Music and records count as one",
                        "category for muting.")
                .defineInRange("fadeOutHearingPercent", DEFAULT_FADE_OUT_HEARING_PERCENT, 0, 100);

        SOUNDS_TO_PAUSE_UNTIL_ALLOWED = builder
                .comment("Rules of \"conditionalSound->pausedSound\". Every pausedSound instance is paused right after",
                        "it starts playing and stays paused until a conditionalSound of one of its rules finishes",
                        "playing, or until another mod allows it (Using this mod api).",
                        "This never blocks a sound from starting: a sound is only paused after it is detected playing.",
                        "If a new pausedSound instance starts while an older one is still paused, the older one is stopped.",
                        "A conditionalSound starting while a pausedSound is already were allowed to keep playing doesn't ",
                        " get paused again. Note: pausing keeps the sound loaded until it resumes or stops. Use it for: ",
                        "sounds owned by other mods that must be delayed but can't be stopped and replayed.",
                        "Example: soundsToPauseUntilAllowed = [\"malenia_intro->malenia_theme\"]")
                .defineList("soundsToPauseUntilAllowed", new ArrayList<>(), o -> o instanceof String);

        PRIORITIZED_MUSIC_LIST = builder
                .comment("Entries of \"sound:priority\" (priority is a whole number). A listed sound is prioritized",
                        "whenever it starts playing, whoever played it, and starts with a fade in. When multiple prioritized ",
                        "sounds playing at the same time, the one with the highest priority is the current prioritized playing and On ",
                        "equal priority, the current prioritized playing stays. While there is a current prioritized playing, every ",
                        "other sound of the music, ambient, weather, records and hostile categories, prioritized or not, is muted when ",
                        "it is playing in the current prioritized playing's category (music and records count as one category) and ",
                        "otherwise weakened to [fadeOutHearingPercent] percentage of the sound volume.",
                        "Non-prioritized sounds of the other categories (players, blocks, voice...) are not affected.",
                        "Sounds muted or weakened keep playing, and fade back once there is no current prioritized playing.",
                        "Example: prioritizedMusicList = [\"malenia_intro:10\"]")
                .defineList("prioritizedMusicList", new ArrayList<>(), o -> o instanceof String);

        BLOCK_CLIENT_MODS_CONFIG_OVERRIDES = builder
                .comment("If true, client side mods can't add entries to this config or to the mod list text file.",
                        "Also overrides clientModsConfigOverridesWhitelist: no client mod writes, listed or not.")
                .define("blockClientModsConfigOverrides", false);

        CLIENT_MODS_CONFIG_OVERRIDES_WHITELIST = builder
                .comment("Mod ids. If not empty, only these client side mods may add entries to this config.",
                        "Empty: every client side mod may.",
                        "Example: clientModsConfigOverridesWhitelist = [\"ithasbeenrpgtweaks\"]")
                .defineList("clientModsConfigOverridesWhitelist", new ArrayList<>(), o -> o instanceof String);

        DEBUG_MOD = builder
                .comment("Enables this mod's own [DEBUG] log lines.")
                .define("debugMod", DEFAULT_DEBUG_MOD);

        builder.pop();
        CLIENT_SPEC = builder.build();
    }

    /**
     * Re-reads every option from the config into the caches. Called on config load and reload (ModEvents) and after
     * mods write entries. Malformed entries are logged and skipped.
     */
    public static void refresh() {
        fadeInTicks = FADE_IN_TICKS.get();
        fadeOutTicks = FADE_OUT_TICKS.get();
        fadeOutHearing = FADE_OUT_HEARING_PERCENT.get() / 100f;

        Map<String, Integer> priorities = new HashMap<>();
        for (String raw : PRIORITIZED_MUSIC_LIST.get()) {
            PrioritizedEntry entry = parsePrioritizedEntry(raw);
            if (entry == null) {
                ModLogger.warn("prioritizedMusicList: '{}' is malformed, expected \"sound:priority\" with a sound name without a namespace, ignored.", raw);
                continue;
            }
            if (priorities.putIfAbsent(entry.sound(), entry.priority()) != null) {
                ModLogger.warn("prioritizedMusicList: '{}' is listed more than once, only its first entry is used.", entry.sound());
            }
        }
        prioritizedMusic = Map.copyOf(priorities);

        Map<String, Set<String>> pausedToConditionals = new HashMap<>();
        Map<String, Set<String>> conditionalToPaused = new HashMap<>();
        for (String raw : SOUNDS_TO_PAUSE_UNTIL_ALLOWED.get()) {
            PauseRule rule = parsePauseRule(raw);
            if (rule == null) {
                ModLogger.warn("soundsToPauseUntilAllowed: '{}' is malformed, expected \"conditionalSound->pausedSound\" with two different sound names without a namespace, ignored.", raw);
                continue;
            }
            pausedToConditionals.computeIfAbsent(rule.pausedSound(), k -> new HashSet<>()).add(rule.conditionalSound());
            conditionalToPaused.computeIfAbsent(rule.conditionalSound(), k -> new HashSet<>()).add(rule.pausedSound());
        }
        pausedSoundToConditionals = toImmutable(pausedToConditionals);
        conditionalToPausedSounds = toImmutable(conditionalToPaused);

        blockClientModsConfigOverrides = BLOCK_CLIENT_MODS_CONFIG_OVERRIDES.get();
        clientModsWhitelist = parseModIds(CLIENT_MODS_CONFIG_OVERRIDES_WHITELIST.get());

        isDebugModResolved = false;
    }

    /**
     * Tells whether a sound name is valid: not blank, without a namespace (no ':') and without "->".
     *
     * @param sound the sound name, may be null
     * @return true if valid
     */
    public static boolean isValidSoundName(@Nullable String sound) {
        return sound != null && !sound.isBlank() && !sound.contains(PRIORITY_SEPARATOR) && !sound.contains(PAUSE_RULE_SEPARATOR);
    }

    /**
     * Parses a prioritizedMusicList entry. It must hold exactly one ':', a valid sound name before it and a whole
     * number after it. Nothing is cut or repaired.
     *
     * @param raw the entry, may be null
     * @return the parsed entry, null if malformed
     */
    @Nullable
    public static PrioritizedEntry parsePrioritizedEntry(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String entry = raw.trim();
        int separator = entry.indexOf(PRIORITY_SEPARATOR);
        if (separator < 0 || separator != entry.lastIndexOf(PRIORITY_SEPARATOR)) {
            return null;
        }
        String sound = entry.substring(0, separator).trim();
        if (!isValidSoundName(sound)) {
            return null;
        }
        try {
            return new PrioritizedEntry(sound, Integer.parseInt(entry.substring(separator + 1).trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a soundsToPauseUntilAllowed rule. It must hold exactly one "->" between two different valid sound
     * names. Nothing is cut or repaired.
     *
     * @param raw the rule, may be null
     * @return the parsed rule, null if malformed
     */
    @Nullable
    public static PauseRule parsePauseRule(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String entry = raw.trim();
        int separator = entry.indexOf(PAUSE_RULE_SEPARATOR);
        if (separator < 0 || separator != entry.lastIndexOf(PAUSE_RULE_SEPARATOR)) {
            return null;
        }
        String conditionalSound = entry.substring(0, separator).trim();
        String pausedSound = entry.substring(separator + PAUSE_RULE_SEPARATOR.length()).trim();
        if (!isValidSoundName(conditionalSound) || !isValidSoundName(pausedSound) || conditionalSound.equals(pausedSound)) {
            return null;
        }
        return new PauseRule(conditionalSound, pausedSound);
    }

    /**
     * Reads fadeInTicks.
     *
     * @return the fade in duration in ticks, at least 1
     */
    public static int fadeInTicks() {
        return fadeInTicks;
    }

    /**
     * Reads fadeOutTicks.
     *
     * @return the fade out duration in ticks, at least 1
     */
    public static int fadeOutTicks() {
        return fadeOutTicks;
    }

    /**
     * Reads fadeOutHearingPercent.
     *
     * @return the weakened hearing fraction of a less prioritized sound of another category, from 0 to 1
     */
    public static float fadeOutHearing() {
        return fadeOutHearing;
    }

    /**
     * Reads a sound's priority from prioritizedMusicList.
     *
     * @param sound the sound name
     * @return its priority, null if the sound isn't prioritized
     */
    @Nullable
    public static Integer getPriority(String sound) {
        return prioritizedMusic.get(sound);
    }

    /**
     * Tells whether a sound is the paused sound of a soundsToPauseUntilAllowed rule.
     *
     * @param sound the sound name
     * @return true if it is paused after it starts
     */
    public static boolean isPausedSound(String sound) {
        return pausedSoundToConditionals.containsKey(sound);
    }

    /**
     * Tells whether a sound is the conditional sound of a soundsToPauseUntilAllowed rule.
     *
     * @param sound the sound name
     * @return true if its finish allows paused sounds to play
     */
    public static boolean isConditionalSound(String sound) {
        return conditionalToPausedSounds.containsKey(sound);
    }

    /**
     * Lists the paused sounds a conditional sound allows when it finishes.
     *
     * @param conditionalSound the conditional sound name
     * @return the paused sound names of its rules, immutable, empty if none
     */
    public static Set<String> getPausedSoundsOf(String conditionalSound) {
        return conditionalToPausedSounds.getOrDefault(conditionalSound, Set.of());
    }

    /**
     * Reads blockClientModsConfigOverrides.
     *
     * @return true if client mods can't add entries
     */
    public static boolean isBlockClientModsConfigOverrides() {
        return blockClientModsConfigOverrides;
    }

    /**
     * Tells whether clientModsConfigOverridesWhitelist lets a client mod add entries. Doesn't check the block switch.
     *
     * @param modId the mod id
     * @return true if the whitelist is empty or lists the mod
     */
    public static boolean isClientModWhitelisted(String modId) {
        Set<String> whitelist = clientModsWhitelist;
        return whitelist.isEmpty() || whitelist.contains(modId);
    }

    /**
     * Reads debugMod.
     * <p>
     * Safe at any point in the lifecycle: returns the default until the config file has actually
     * been loaded, rather than throwing the way ConfigValue.get() does.
     *
     * @return the option's value, the default until the config file has loaded
     */
    public static boolean isDebugMod() {
        if (!isDebugModResolved) {
            if (!CLIENT_SPEC.isLoaded()) {
                return DEFAULT_DEBUG_MOD;
            }
            isDebugMod = DEBUG_MOD.get();
            isDebugModResolved = true;
        }

        return isDebugMod;
    }

    /**
     * Copies a map of sets into an immutable one.
     *
     * @param source the map to copy, not changed
     * @return an immutable copy with immutable sets
     */
    private static Map<String, Set<String>> toImmutable(Map<String, Set<String>> source) {
        Map<String, Set<String>> out = new HashMap<>();
        source.forEach((key, value) -> out.put(key, Set.copyOf(value)));
        return Map.copyOf(out);
    }

    /**
     * Parses a list of mod ids, trimmed, skipping blank ones.
     *
     * @param raw the configured list
     * @return the mod ids, immutable
     */
    private static Set<String> parseModIds(List<? extends String> raw) {
        Set<String> out = new HashSet<>();
        for (String modId : raw) {
            if (modId != null && !modId.isBlank()) {
                out.add(modId.trim());
            }
        }
        return Set.copyOf(out);
    }
}
