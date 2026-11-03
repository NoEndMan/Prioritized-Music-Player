package net.flameslight.prioritizedmusicplayer;

import com.mojang.blaze3d.audio.Channel;
import net.flameslight.prioritizedmusicplayer.config.ClientConfig;
import net.flameslight.prioritizedmusicplayer.logger.ModLogger;
import net.flameslight.prioritizedmusicplayer.mixins.SoundEngineAccessor;
import net.flameslight.prioritizedmusicplayer.mixins.SoundManagerAccessor;
import net.flameslight.prioritizedmusicplayer.types.sound.PrioritizationStatus;
import net.flameslight.prioritizedmusicplayer.types.sound.SoundData;
import net.flameslight.prioritizedmusicplayer.types.sound.prioritized.PrioritizedSoundData;
import net.flameslight.prioritizedmusicplayer.types.sound.prioritized.PrioritizedSoundInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Applies the client config's prioritization and pause rules to playing sounds.
 * <p>
 * Sounds listed in prioritizedMusicList are replaced by a PrioritizedSoundInstance when they start, and every one of
 * them starts with a fade in. The one with the highest priority is the current prioritized playing: the only one heard
 * at full level. While there is one, every other sound of the affected categories (music, ambient, weather, records,
 * hostile) is muted when it is in the current prioritized playing's category, music and records counting as one, and
 * weakened (fadeOutHearingPercent) otherwise. Less prioritized sounds follow the same rule. Nothing is stopped by
 * prioritization: affected sounds keep playing concealed. Paused sounds of soundsToPauseUntilAllowed are paused after
 * they start, until a conditional sound of their rules finishes or another mod allows them.
 * <p>
 * Non-prioritized sounds of the affected categories are tracked one by one with their own fade.
 * Their volume is a fraction of vanilla's own volume, pushed to their channel every
 * SOUND_UPDATE_DELAY_TICKS and applied by the volume hooks.
 * <p>
 * IMPORTANT! this code is multi thread called.
 * Some called in client main thread (Render thread), some in Sound Engine threads (there are multiple threads of this!)
 * The non-prioritized fades and the volume hooks are client main thread only.
 */
@OnlyIn(Dist.CLIENT)
public class ClientMusicHandler {
    /** Ticks between PMP's sound updates: tracked volume pushes, and a prioritized sound following its original. */
    public static final int SOUND_UPDATE_DELAY_TICKS = 2;
    private static final int INITIAL_TRACKED_SOUNDS_SIZE = 24;
    /** Initial size of channelToSoundData: only sounds configured to be paused are in it, usually 0 to 2. */
    private static final int INITIAL_PAUSED_CHANNELS_SIZE = 4;
    /** Unmodifiable */
    public static final List<SoundSource> affectedSoundSources = List.of(
            SoundSource.MUSIC,
            SoundSource.AMBIENT,
            SoundSource.WEATHER,
            SoundSource.RECORDS,
            SoundSource.HOSTILE);
    /** Categories that count as one category for muting: mods play music in either one. Unmodifiable. */
    private static final List<SoundSource> MUTE_GROUP = List.of(
            SoundSource.MUSIC,
            SoundSource.RECORDS);

    /**
     * Tracked non-prioritized sounds: every sound of affectedSoundSources, and the paused sounds of
     * soundsToPauseUntilAllowed whatever their category.
     */
    private static final ConcurrentHashMap<SoundInstance, SoundData<SoundInstance>> nonPrioritizedTrackedSounds = new ConcurrentHashMap<>(INITIAL_TRACKED_SOUNDS_SIZE);

    /**
        prioritized, thread safe, sounds data hashmap, keyed by the playing prioritized instance (not sorted)
     */
    private static final ConcurrentHashMap<PrioritizedSoundInstance, PrioritizedSoundData> prioritizedTrackedSounds = new ConcurrentHashMap<>();

    /**
     * Channels of the playing sounds configured to be paused (the paused side of a soundsToPauseUntilAllowed rule),
     * for ChannelMixin: Channel.unpause() only knows its channel, so this is how it tells a channel that must stay
     * paused. No other sound gets an entry.
     */
    private static final ConcurrentHashMap<Channel, SoundData<?>> channelToSoundData = new ConcurrentHashMap<>(INITIAL_PAUSED_CHANNELS_SIZE);

    /** Played instance to the prioritized instance that replaced it, so its owner can still stop it. */
    private static final ConcurrentHashMap<SoundInstance, PrioritizedSoundInstance> originalToPrioritized = new ConcurrentHashMap<>();

    /** Playing instances of conditional sounds, kept only to tell when they finish. */
    private static final Set<SoundInstance> playingConditionalInstances = ConcurrentHashMap.newKeySet();

    /**
     * The latest change of the current prioritized playing, applied to the non-prioritized fades by the next main
     * thread tick.
     */
    private static final AtomicReference<CurrentPrioritizedPlayingUpdate> pendingCurrentPrioritizedPlayingUpdate = new AtomicReference<>();

    /** Sounds stopped by a stop by name with no name and no source. Client main thread only. */
    private static final Set<SoundInstance> stoppedByStopAllPacket = new HashSet<>();

    /** True while a stop by name runs vanilla's stop. Client main thread only. */
    private static boolean stopByNameInProgress = false;

    /**
     * The current prioritized playing: the playing prioritized sound with the highest priority that isn't concealed by
     * a mod, paused or fading out to stop. Null when there is none.
     */
    private static volatile PrioritizedSoundData currentPrioritizedPlaying;

    private static int soundUpdateTicksCounter = SOUND_UPDATE_DELAY_TICKS;

    /**
        Save sounds that should be unpaused, for a possible case that the call to unpause
        was sent before the sound started playing, causing the unpausing to fail as the
        target sound doesn't exist yet. Only written when no instance of the sound plays at
        the call. Consumed by the next start of that sound, and cleared when a conditional
        sound of its rules starts, since a new pause begins.
     */
    private static final CopyOnWriteArraySet<String> soundsToUnpause = new CopyOnWriteArraySet<>();

    /**
     * A change of the current prioritized playing, for the non-prioritized fades. Posted when a prioritized sound
     * becomes the current prioritized playing while there was none, when a different one becomes it, or when there is
     * none anymore.
     *
     * @param playingSource the new current prioritized playing's category, null when there is none anymore
     * @param restoreTicks  the duration of the fade back to normal when there is no current prioritized playing anymore
     */
    private record CurrentPrioritizedPlayingUpdate(@Nullable SoundSource playingSource, int restoreTicks) {
    }

    /**
     * Applies the latest change of the current prioritized playing to the tracked non-prioritized sounds, steps their
     * fades, and every SOUND_UPDATE_DELAY_TICKS pushes the changed volumes to their channels and stops replaced paused
     * sounds, as IHBRT's client handler did. Called at client tick end.
     */
    public static void updateEveryClientTick() {
        CurrentPrioritizedPlayingUpdate update = pendingCurrentPrioritizedPlayingUpdate.getAndSet(null);
        if (update != null) {
            applyCurrentPrioritizedPlayingUpdate(update);
        }

        updateTickForNonPrioritizedTrackedSounds();
        soundUpdateTicksCounter--;

        if (soundUpdateTicksCounter < 1) {
            soundUpdateTicksCounter = SOUND_UPDATE_DELAY_TICKS;
            timedUpdatesForNonPrioritizedTrackedSounds();
        }
    }

    /**
     * Creates the prioritized replacement of a played instance of a prioritizedMusicList sound, and remembers which
     * instance it replaced. It starts concealed and fades to its level once tracked. Its volume is the original's,
     * followed while it plays. Called from PlaySoundEvent, client main thread.
     *
     * @param original the played instance, not yet resolved
     * @param priority the sound's priority
     * @return the instance to play instead
     */
    public static PrioritizedSoundInstance createPrioritized(SoundInstance original, int priority) {
        original.resolve(Minecraft.getInstance().getSoundManager());

        PrioritizedSoundInstance replacement = new PrioritizedSoundInstance(original, priority, true);
        originalToPrioritized.put(original, replacement);
        return replacement;
    }

    /**
     * Returns the instance actually playing for a played instance: its prioritized replacement if it was replaced,
     * otherwise itself. Used by SoundEngineStopMixin so owners can stop and query their replaced sounds.
     *
     * @param soundInstance the played instance, may be null
     * @return the replacement, or the same instance
     */
    @Nullable
    public static SoundInstance getPlayingInstance(@Nullable SoundInstance soundInstance) {
        if (soundInstance == null) {
            return null;
        }
        PrioritizedSoundInstance replacement = originalToPrioritized.get(soundInstance);
        return replacement != null ? replacement : soundInstance;
    }

    /**
     * Tracks a sound that just started playing, if the rules need it: remembers conditional sounds (and clears the
     * saved unpauses of the paused sounds of their rules, since a new pause begins), tracks prioritized instances (and
     * updates the current prioritized playing), tracks non-prioritized sounds of the affected categories (muted or
     * weakened at once while there is a current prioritized playing) and pauses paused sounds. Every other sound
     * returns at once. Called in client Sound Engine thread, inside the channel's task.
     *
     * @param soundInstance the started instance
     * @param channel       its channel
     */
    public static void handleNewSoundFromEvent(SoundInstance soundInstance, Channel channel) {
        String name = getSoundInstanceName(soundInstance);

        if (ClientConfig.isConditionalSound(name)) {
            playingConditionalInstances.add(soundInstance);
            clearSavedUnpausesOf(name);
        }

        if (soundInstance instanceof PrioritizedSoundInstance prioritizedSoundInstance) {
            PrioritizedSoundData data = new PrioritizedSoundData(prioritizedSoundInstance);
            attachChannel(data, channel);

            // prioritized sounds track
            prioritizedTrackedSounds.put(prioritizedSoundInstance, data);
            applyPauseUntilAllowed(data);
            handleChangesForCurrentPrioritizedPlaying();
        } else {
            PrioritizedSoundData currentPlaying = currentPrioritizedPlaying;
            SoundSource playingSource = currentPlaying == null ? null : currentPlaying.getSoundInstance().getSource();
            SoundData<SoundInstance> handled = handleNewSoundWithChannel(soundInstance, channel, playingSource);

            if (handled != null) {
                // Non-prioritized sounds track
                nonPrioritizedTrackedSounds.put(soundInstance, handled);

                // handle pause application
                applyPauseUntilAllowed(handled);
            }
        }
    }

    /**
     * Tells whether there is a current prioritized playing.
     *
     * @return true if a prioritized sound is the current prioritized playing
     */
    public static boolean isPlayingPrioritizedSound() {
        return currentPrioritizedPlaying != null;
    }

    /**
     * Conceals or reveals every playing prioritized instance of a sound, then updates the current prioritized playing
     * and every prioritized sound's level. Only instances playing at that moment are affected. Client main thread.
     *
     * @param sound     the sound name
     * @param concealed true to conceal, false to reveal
     */
    public static synchronized void setPrioritizedMusicConcealed(String sound, boolean concealed) {
        for (PrioritizedSoundData data : prioritizedTrackedSounds.values()) {
            if (data.NAME.equals(sound)) {
                data.setConcealedByApi(concealed);
            }
        }

        handleChangesForCurrentPrioritizedPlaying();
    }

    /**
     * The volume hook, applied to vanilla's calculated volume of every sound: a prioritized instance gets its volume
     * fraction (capped by the smaller of its max volume and its category's slider, never above its normal volume), a
     * tracked non-prioritized sound gets its fade fraction (IHBRT's SoundEngineMixin lookup), any other sound is
     * unchanged. Ticking sounds are muted or weakened through this hook, since vanilla resets their channel volume
     * every tick. Client main thread only.
     *
     * @param engine   the sound engine
     * @param instance the sound
     * @param volume   vanilla's calculated volume
     * @return the volume to use
     */
    public static float adjustVolume(SoundEngine engine, SoundInstance instance, float volume) {
        if (instance instanceof PrioritizedSoundInstance prioritized) {
            float hearing = prioritized.getHearing();

            if (hearing >= 1f) {
                return volume;
            }

            float categoryMax = ((SoundEngineAccessor) engine).invokeGetVolume(instance.getSource());
            float soundMax = instance.getVolume();
            return Math.min(volume, hearing * Math.min(soundMax, categoryMax));
        }

        if (nonPrioritizedTrackedSounds.isEmpty()) {
            return volume;
        }

        SoundData<SoundInstance> sd = getNonPrioritizedTrackedSound(instance);

        if (sd != null) {
            return volume * sd.getCurrentVolume();
        }

        return volume;
    }

    /**
     * Lists every sound playing on the client with its prioritization status, for /pmp nowplaying: the current
     * prioritized playing first, then muted, then weakened, then paused, then unaffected sounds, each group sorted by
     * id. Sounds with the same id and status are merged into one line with a count. A status is where the sound's fade
     * heads, not its current level, and is shown bold and yellow after the id. Each id is underlined, and clicking it
     * copies it to the clipboard. Client main thread.
     *
     * @return the number of playing sounds and the chat lines, one per id and status, empty when nothing plays
     */
    public static NowPlaying getNowPlaying() {
        Map<PrioritizationStatus, Map<String, Integer>> countsByStatus = new EnumMap<>(PrioritizationStatus.class);
        PrioritizedSoundData currentPlaying = currentPrioritizedPlaying;
        int soundCount = 0;

        for (SoundInstance playing : ((SoundEngineAccessor) getSoundEngine()).getInstanceToChannel().keySet()) {
            PrioritizationStatus status = getPrioritizationStatus(playing, currentPlaying);
            countsByStatus.computeIfAbsent(status, k -> new TreeMap<>())
                    .merge(playing.getLocation().toString(), 1, Integer::sum);
            soundCount++;
        }

        List<Component> lines = new ArrayList<>();

        // EnumMap iterates in the enum's order: most prioritized, muted, weakened, paused, unaffected
        countsByStatus.forEach((status, counts) -> counts.forEach((id, count) -> {
            /*
                Only the id copies on click. It is a sibling of an empty root, so the count and the status
                appended after it don't inherit its click and hover.
             */
            MutableComponent line = Component.empty().append(Component.literal(id).withStyle(style -> style
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, id))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy")))));

            if (count > 1) {
                line.append(" x" + count);
            }

            if (!status.getText().isEmpty()) {
                line.append(" - ").append(Component.literal(status.getText()).withStyle(ChatFormatting.BOLD, ChatFormatting.YELLOW));
            }
            lines.add(line);
        }));

        return new NowPlaying(soundCount, lines);
    }

    /**
     * What /pmp nowplaying prints.
     *
     * @param soundCount the number of playing sounds
     * @param lines      the chat lines, one per id and status
     */
    public record NowPlaying(int soundCount, List<Component> lines) {
    }

    /**
     * Gives a playing sound its prioritization status: paused when a soundsToPauseUntilAllowed rule holds it paused,
     * otherwise from where its fade heads.
     *
     * @param playing        the playing sound
     * @param currentPlaying the current prioritized playing, null when there is none
     * @return its status
     */
    private static PrioritizationStatus getPrioritizationStatus(SoundInstance playing, @Nullable PrioritizedSoundData currentPlaying) {
        float target;

        if (playing instanceof PrioritizedSoundInstance prioritized) {
            PrioritizedSoundData data = prioritizedTrackedSounds.get(prioritized);

            if (data != null && data.isPausedState()) {
                return PrioritizationStatus.PAUSED;
            }
            if (currentPlaying != null && currentPlaying.getSoundInstance() == prioritized) {
                return PrioritizationStatus.MOST_PRIORITIZED;
            }
            target = prioritized.getTargetHearing();
        } else {
            SoundData<SoundInstance> data = getNonPrioritizedTrackedSound(playing);

            if (data == null) {
                return PrioritizationStatus.NONE;
            }
            if (data.isPausedState()) {
                return PrioritizationStatus.PAUSED;
            }
            target = data.getFadeTarget();
        }

        if (target <= SoundData.CONCEALED_VOLUME) {
            return PrioritizationStatus.MUTED;
        }

        return target < 1f ? PrioritizationStatus.WEAKENED : PrioritizationStatus.NONE;
    }

    /**
     * Stops every prioritized instance and tracked paused sound at once, brings every other tracked sound back to full
     * volume at once, and clears every saved sound data. Called when the client enters or leaves a world.
     */
    public static synchronized void clean() {
        currentPrioritizedPlaying = null;

        for (PrioritizedSoundData data : prioritizedTrackedSounds.values()) {
            data.terminate();
        }
        for (SoundData<SoundInstance> data : nonPrioritizedTrackedSounds.values()) {
            restoreFullVolume(data);

            if (ClientConfig.isPausedSound(data.NAME)) {
                safelyStopSound(data.getSoundInstance());
            }
        }

        nonPrioritizedTrackedSounds.clear();
        channelToSoundData.clear();
        prioritizedTrackedSounds.clear();
        originalToPrioritized.clear();
        playingConditionalInstances.clear();
        soundsToUnpause.clear();
        stoppedByStopAllPacket.clear();
        stopByNameInProgress = false;
        pendingCurrentPrioritizedPlayingUpdate.set(null);
    }

    /**
     * Stops every prioritized instance at once, with no fade, and brings every tracked non-prioritized sound back to
     * full volume at once. The pause rules are left alone. Called when the player changes dimension or respawns.
     * Client main thread.
     */
    public static synchronized void stopPrioritizedInstantly() {
        currentPrioritizedPlaying = null;

        for (PrioritizedSoundData data : prioritizedTrackedSounds.values()) {
            data.terminate();
        }

        pendingCurrentPrioritizedPlayingUpdate.set(null);

        for (SoundData<SoundInstance> data : nonPrioritizedTrackedSounds.values()) {
            restoreFullVolume(data);
        }
    }

    /**
     * Forgets every playing sound after SoundEngine.stopAll, which clears the engine's maps without the removal hook.
     * Early unpauses are kept, since they are about future starts. Client main thread.
     */
    public static synchronized void onAllSoundsStopped() {
        currentPrioritizedPlaying = null;

        nonPrioritizedTrackedSounds.clear();
        prioritizedTrackedSounds.clear();
        channelToSoundData.clear();
        originalToPrioritized.clear();
        playingConditionalInstances.clear();
        stoppedByStopAllPacket.clear();
        pendingCurrentPrioritizedPlayingUpdate.set(null);
    }

    /**
     * Forgets a finished sound. A finished conditional sound allows the paused sounds of its rules, and a finished
     * current prioritized playing lets the next prioritized sound become it, or the other sounds come back.
     * Untracked sounds return at once. Called from multiple threads.
     *
     * @param soundInstance the finished instance
     */
    public static void removeTrackedSoundBySoundInstance(SoundInstance soundInstance) {
        if (soundInstance instanceof PrioritizedSoundInstance prioritizedSoundInstance) {
            PrioritizedSoundData data = prioritizedTrackedSounds.remove(prioritizedSoundInstance);
            untrackChannel(data);
            originalToPrioritized.remove(prioritizedSoundInstance.getOriginal(), prioritizedSoundInstance);

            if (data != null) {
                onPrioritizedSoundRemoved(prioritizedSoundInstance);
            }
        } else if (!nonPrioritizedTrackedSounds.isEmpty()) {
            untrackChannel(nonPrioritizedTrackedSounds.remove(soundInstance));
        }

        if (!playingConditionalInstances.isEmpty() && playingConditionalInstances.remove(soundInstance)) {
            onConditionalSoundFinished(getSoundInstanceName(soundInstance));
        }
    }

    /**
     * Allows the instances of a sound playing at the moment of the call: the paused ones resume, and the ones already
     * allowed are left as they are. An instance that starts later is paused by its rule as usual. Only when no
     * instance of the sound plays (the call came before the sound started) is the name saved, so the next start of
     * that sound isn't paused. Client main thread.
     *
     * @param soundName the sound name
     */
    public static void markSoundToUnpause(String soundName) {
        int resumedCount = 0;
        boolean isAnyInstancePlaying = false;

        for (SoundData<?> soundData : getAllTrackedSoundData()) {
            if (!soundData.NAME.equals(soundName) || soundData.isTerminated()) {
                continue;
            }

            isAnyInstancePlaying = true;

            if (soundData.isPausedState()) {
                resumedCount++;
                unpause(soundData);
            }
        }

        if (resumedCount > 0) {
            ModLogger.debug("Unpause {}: {} paused instances resumed.", soundName, resumedCount);
        } else if (isAnyInstancePlaying) {
            ModLogger.debug("Unpause {}: already allowed, nothing saved.", soundName);
        } else {
            soundsToUnpause.add(soundName);
            ModLogger.debug("Unpause {}: no instance playing, saved for its next start.", soundName);
        }
    }

    /**
     * Tells ChannelMixin whether a channel must stay paused: it belongs to a sound configured to be paused that is
     * still paused. Called from Sound Engine thread!
     *
     * @param channel the channel being unpaused
     * @return true to skip the unpause
     */
    public static boolean shouldStayPaused(Channel channel) {
        if (channelToSoundData.isEmpty()) {
            return false;
        }

        SoundData<?> soundData = channelToSoundData.get(channel);

        boolean toStayPause = soundData != null &&
                !soundData.isTerminated() &&
                soundData.isPausedState();

        return toStayPause;
    }

    /**
     * Handles a stop by name (SoundEngine.stop(name, source), which the stop sound packet ends in): matching
     * prioritized sounds fade out and stop afterwards, every other matching sound stops at once as in vanilla. A stop
     * with no name and no source stops every other sound instead of calling stopAll, so the prioritized sounds can
     * fade. Client main thread.
     *
     * @param name        the sound name, null for any
     * @param source      the category, null for any
     * @param vanillaStop vanilla's SoundEngine.stop(name, source)
     */
    public static void handleStopByName(@Nullable ResourceLocation name, @Nullable SoundSource source, Runnable vanillaStop) {
        fadeOutPrioritizedForStopByName(name, source);
        stopByNameInProgress = true;

        try {
            if (name != null || source != null) {
                vanillaStop.run();
            } else {
                stopAllExceptFadingPrioritized();
            }
        } finally {
            stopByNameInProgress = false;
        }
    }

    /**
     * Tells whether SoundEngine.stop must skip a sound: a prioritized sound fading out for the stop by name being
     * handled. Client main thread.
     *
     * @param soundInstance the sound vanilla is about to stop
     * @return true to skip the stop
     */
    public static boolean isSkippedByStopByName(@Nullable SoundInstance soundInstance) {
        return stopByNameInProgress
                && soundInstance instanceof PrioritizedSoundInstance prioritized
                && prioritized.isStoppingAfterFadeOut();
    }

    /**
     * Tells whether a removed sound was stopped by a stop by name with no name and no source, and forgets it. Its
     * manual loop re-queue must then be dropped, as stopAll would. Client main thread.
     *
     * @param soundInstance the removed sound
     * @return true if it was stopped that way
     */
    public static boolean consumeStoppedByStopAllPacket(SoundInstance soundInstance) {
        return !stoppedByStopAllPacket.isEmpty() && stoppedByStopAllPacket.remove(soundInstance);
    }

    /**
     * Marks the prioritized sounds a stop by name matches (vanilla's rule) to fade out and stop. The engine's own
     * playing instances are searched, so a prioritized sound that started but isn't tracked yet is marked too. A
     * paused one isn't marked, so vanilla stops it at once. If the current prioritized playing was marked, the next one
     * fades in while it fades out.
     *
     * @param name   the sound name, null for any
     * @param source the category, null for any
     */
    private static synchronized void fadeOutPrioritizedForStopByName(@Nullable ResourceLocation name, @Nullable SoundSource source) {
        int fadeOutTicks = ClientConfig.fadeOutTicks();
        boolean currentPlayingMarked = false;
        int markedCount = 0;

        for (SoundInstance playing : ((SoundEngineAccessor) getSoundEngine()).getInstanceToChannel().keySet()) {
            if (!(playing instanceof PrioritizedSoundInstance instance)) {
                continue;
            }

            PrioritizedSoundData data = prioritizedTrackedSounds.get(instance);

            if ((source != null && instance.getSource() != source)
                    || (name != null && !instance.getLocation().equals(name))
                    || (data != null && data.isPausedState())) {
                continue;
            }

            instance.startFadeOutAndStop(fadeOutTicks);
            markedCount++;
            currentPlayingMarked |= data != null && data == currentPrioritizedPlaying;
        }

        ModLogger.debug("Stop by name {} in {}: {} prioritized sounds marked to fade out over {} ticks.",
                name, source, markedCount, fadeOutTicks);

        if (currentPlayingMarked) {
            handleChangesForCurrentPrioritizedPlaying(fadeOutTicks);
        }
    }

    /**
     * Stops every playing sound except the prioritized sounds fading out for the stop by name, and clears the queued
     * sounds as SoundEngine.stopAll does. Client main thread.
     */
    private static void stopAllExceptFadingPrioritized() {
        SoundEngine engine = getSoundEngine();
        SoundEngineAccessor accessor = (SoundEngineAccessor) engine;

        for (SoundInstance instance : new ArrayList<>(accessor.getInstanceToChannel().keySet())) {
            if (isSkippedByStopByName(instance)) {
                continue;
            }

            stoppedByStopAllPacket.add(instance);
            engine.stop(instance);
        }

        accessor.getQueuedSounds().clear();
        accessor.getQueuedTickableSounds().clear();
    }

    /**
     * Applies a change of the current prioritized playing to every tracked non-prioritized sound. Lowering runs over
     * fadeInTicks, in step with the current prioritized playing's fade in, and coming back when there is none anymore
     * runs over the update's ticks. Client main thread, so the fades are never written from two threads.
     *
     * @param update the change of the current prioritized playing
     */
    private static void applyCurrentPrioritizedPlayingUpdate(CurrentPrioritizedPlayingUpdate update) {
        SoundSource playingSource = update.playingSource();
        int ticks = playingSource == null ? update.restoreTicks() : ClientConfig.fadeInTicks();

        ModLogger.debug("Current prioritized playing changed: category {}, {} tracked sounds fade over {} ticks.",
                playingSource == null ? "none" : playingSource.getName(), nonPrioritizedTrackedSounds.size(), ticks);
        updateNonPrioritizedSounds(playingSource, ticks);
    }

    /**
     * Tells whether a sound's category is one of affectedSoundSources.
     *
     * @param soundInstance the sound
     * @return true if its category is affected
     */
    public static boolean isAffectedSoundSource(SoundInstance soundInstance) {
        return affectedSoundSources.contains(soundInstance.getSource());
    }

    /**
     * Returns the tracked data of a non-prioritized sound.
     *
     * @param soundInstance the sound
     * @return its tracked data, null if it isn't tracked
     */
    @Nullable
    public static SoundData<SoundInstance> getNonPrioritizedTrackedSound(SoundInstance soundInstance) {
        return nonPrioritizedTrackedSounds.get(soundInstance);
    }

    /**
     * Builds the data of a just started non-prioritized sound and remembers its channel if it is configured to be
     * paused. While there is a current prioritized playing, the sound is muted or weakened at once on its channel.
     *
     * @param soundInstance the started sound
     * @param channel       its channel, may be null or already stopped
     * @param playingSource the current prioritized playing's category, null when there is none
     * @return the built data, null if the sound isn't tracked
     */
    @Nullable
    private static SoundData<SoundInstance> handleNewSoundWithChannel(SoundInstance soundInstance, @Nullable Channel channel,
                                                                      @Nullable SoundSource playingSource) {
        SoundData<SoundInstance> handledSoundData = buildSoundData(soundInstance, playingSource);

        if (handledSoundData != null) {
            attachChannel(handledSoundData, channel);

            if (handledSoundData.getCurrentVolume() < handledSoundData.MAX_VOLUME && channel != null && !channel.stopped()) {
                channel.setVolume(calculateTrackedVolume(handledSoundData));
                handledSoundData.flagVolumeAsUpdated();
            }
        }

        return handledSoundData;
    }

    /**
     * Builds the data of a just started non-prioritized sound.
     * Paused sounds are tracked whatever their category.
     *
     * @param soundInstance the started sound
     * @param playingSource the current prioritized playing's category, null when there is none
     * @return the built data, null if the sound isn't tracked
     */
    @Nullable
    private static SoundData<SoundInstance> buildSoundData(SoundInstance soundInstance, @Nullable SoundSource playingSource) {
        String name = getSoundInstanceName(soundInstance);

        /*
            Don't interrupt sounds from sources we don't want to effect.
            Paused sounds are tracked whatever their source, for their pause rules.
         */
        if (!isAffectedSoundSource(soundInstance) && !ClientConfig.isPausedSound(name))
            return null;

        SoundData<SoundInstance> soundData = new SoundData<>(soundInstance, false, 1f);
        float hearing = getHearingFor(soundInstance.getSource(), playingSource);

        // conceal new sounds while prioritized music is playing and not concealed
        if (hearing < soundData.MAX_VOLUME) {
            soundData.setVolumeAtOnce(hearing);
        }

        return soundData;
    }

    /**
     * Starts the fade of every tracked non-prioritized sound for a change of the current prioritized playing. From
     * IHBRT's client handler, with the fade going to each sound's own target (muted or weakened) instead of always
     * fully out, and without IHBRT's pause changes, since PMP's pause rules are separate.
     *
     * @param playingSource the current prioritized playing's category, null when there is none
     * @param ticks         the fade duration in ticks
     */
    private static void updateNonPrioritizedSounds(@Nullable SoundSource playingSource, int ticks) {
        for (SoundData<SoundInstance> soundData : nonPrioritizedTrackedSounds.values()) {
            if (soundData.isTerminated()) {
                continue;
            }

            if (playingSource == null) {
                soundData.startFadeIn(ticks);
            } else {
                soundData.startFadeToHearing(getHearingFor(soundData.getSoundInstance().getSource(), playingSource), ticks);
            }
        }
    }

    /**
     * Steps the fade of every tracked non-prioritized sound. Client main thread.
     */
    private static void updateTickForNonPrioritizedTrackedSounds() {
        for (Map.Entry<SoundInstance, SoundData<SoundInstance>> entry : nonPrioritizedTrackedSounds.entrySet()) {
            SoundData<SoundInstance> data = entry.getValue();
            boolean toTerminate = data.tick();

            if (toTerminate) {
                data.terminate();
            }
        }
    }

    /**
     * Pushes the changed volumes of the tracked non-prioritized sounds to their channels, and stops and forgets the
     * terminated ones (paused sounds replaced by a newer paused instance of the same sound). Client main thread.
     */
    private static void timedUpdatesForNonPrioritizedTrackedSounds() {
        List<SoundInstance> toStopAndRemove = null;

        // apply volume changes and check if to stop sounds
        for (Map.Entry<SoundInstance, SoundData<SoundInstance>> entry : nonPrioritizedTrackedSounds.entrySet()) {
            SoundData<SoundInstance> data = entry.getValue();

            if (data.isTerminated()) {
                if (toStopAndRemove == null) {
                    toStopAndRemove = new ArrayList<>();
                }
                toStopAndRemove.add(data.getSoundInstance());
            } else if (data.isRequiresVolumeUpdate()) {
                applyVolumeChanges(data);
            }
        }

        if (toStopAndRemove == null) {
            return;
        }

        // perform stops outside the loop to avoid ConcurrentModificationError
        for (SoundInstance soundInstance : toStopAndRemove) {
            safelyStopSound(soundInstance);
            removeTrackedSoundBySoundInstance(soundInstance);
        }
    }

    /**
     * Pushes a tracked non-prioritized sound's volume to its channel. Client main thread.
     *
     * @param data the tracked sound, may be null
     */
    private static void applyVolumeChanges(SoundData<SoundInstance> data) {
        if (data == null || data.getSoundInstance() == null || data.isTerminated())
            return;

        ChannelAccess.ChannelHandle handle = getChannelHandle(data.getSoundInstance());

        if (handle != null && !handle.isStopped()) {
            float volume = calculateTrackedVolume(data);
            handle.execute(channel -> channel.setVolume(volume));
            data.flagVolumeAsUpdated();
        }
    }

    /**
     * Calculates the channel volume of a tracked non-prioritized sound: vanilla's volume, computed the way IHBRT's
     * SoundEngineMixin fallback did, times the sound's fade fraction. Doesn't depend on the volume hooks.
     *
     * @param data the tracked sound
     * @return the channel volume, from 0 to 1
     */
    private static float calculateTrackedVolume(SoundData<SoundInstance> data) {
        SoundInstance soundInstance = data.getSoundInstance();

        // fallback: compute original value using private getVolume(source)
        float computed = ((SoundEngineAccessor) getSoundEngine()).invokeGetVolume(soundInstance.getSource()) * soundInstance.getVolume();
        return Mth.clamp(computed, 0.0F, 1.0F) * data.getCurrentVolume();
    }

    /**
     * Brings a tracked non-prioritized sound back to full volume at once. From IHBRT's clean. Client main thread.
     *
     * @param soundData the tracked sound
     */
    private static void restoreFullVolume(SoundData<SoundInstance> soundData) {
        // bring to full volume to avoid weird muted sounds
        soundData.startFadeIn(1);
        soundData.setCurrentVolume(soundData.MAX_VOLUME);
        applyVolumeChanges(soundData);
    }

    /**
     * Returns the fraction a non-prioritized sound of a category is played at: normal when there is no current
     * prioritized playing or the category isn't affected, muted in the current prioritized playing's mute group,
     * weakened (fadeOutHearingPercent) otherwise.
     *
     * @param source        the sound's category
     * @param playingSource the current prioritized playing's category, null when there is none
     * @return the fraction, from CONCEALED_VOLUME to 1
     */
    private static float getHearingFor(SoundSource source, @Nullable SoundSource playingSource) {
        if (playingSource == null || !affectedSoundSources.contains(source)) {
            return 1f;
        }

        if (isSameMuteGroup(source, playingSource)) {
            return SoundData.CONCEALED_VOLUME;
        }

        return Math.max(SoundData.CONCEALED_VOLUME, ClientConfig.fadeOutHearing());
    }

    /**
     * Tells whether two categories count as the same category for muting: equal, or both in MUTE_GROUP.
     *
     * @param first  a category
     * @param second another category
     * @return true if a sound of one is muted while a sound of the other is the current prioritized playing
     */
    private static boolean isSameMuteGroup(@Nullable SoundSource first, @Nullable SoundSource second) {
        return first == second || (MUTE_GROUP.contains(first) && MUTE_GROUP.contains(second));
    }

    private static String getSoundInstanceName(SoundInstance soundInstance) {
        return soundInstance.getLocation().getPath();
    }

    /**
     * Remembers the channel of a sound configured to be paused (the paused side of a soundsToPauseUntilAllowed rule),
     * for ChannelMixin. Every other sound returns at once, so no channel is kept for it.
     *
     * @param data    the tracked sound
     * @param channel its channel, may be null or already stopped
     */
    private static void attachChannel(SoundData<?> data, @Nullable Channel channel) {
        if (!ClientConfig.isPausedSound(data.NAME)) {
            return;
        }

        if (channel != null && !channel.stopped()) {
            channelToSoundData.put(channel, data);
        }
    }

    /**
     * Forgets a finished sound's channel, if it was kept. The map holds only sounds configured to be paused, so it is
     * almost always empty, and otherwise holds a couple of entries.
     *
     * @param data the tracked sound, may be null
     */
    private static void untrackChannel(@Nullable SoundData<?> data) {
        if (data != null && !channelToSoundData.isEmpty()) {
            channelToSoundData.values().remove(data);
        }
    }

    /**
     * Removes the saved unpauses of the paused sounds of a just started conditional sound's rules: they were saved
     * for an earlier pause and would let the sound skip this new one.
     *
     * @param conditionalSound the started conditional sound name
     */
    private static void clearSavedUnpausesOf(String conditionalSound) {
        if (soundsToUnpause.isEmpty()) {
            return;
        }

        List<String> cleared = new ArrayList<>();

        for (String pausedSound : ClientConfig.getPausedSoundsOf(conditionalSound)) {
            if (soundsToUnpause.remove(pausedSound)) {
                cleared.add(pausedSound);
            }
        }

        if (!cleared.isEmpty()) {
            ModLogger.debug("{} started: cleared saved unpauses of {}.", conditionalSound, cleared);
        }
    }

    /**
     * Applies the pause state of a tracked sound to its channel: pauses or unpauses it.
     *
     * @param data the tracked sound, non-null
     */
    private static void applyPauseChanges(@Nonnull SoundData<?> data) {
        if (data.isTerminated() || data.getSoundInstance() == null) return;

        ChannelAccess.ChannelHandle handle = getChannelHandle(data.getSoundInstance());

        if (handle != null && !handle.isStopped()) {
            handle.execute(channel -> {
                if (data.isPausedState())
                    channel.pause();
                else
                    channel.unpause();
            });
        }
    }

    /**
     * Pauses a just started paused sound (soundsToPauseUntilAllowed), unless it was allowed in advance. An older
     * instance of the same sound that is still paused is stopped.
     *
     * @param data the just started tracked sound
     */
    private static void applyPauseUntilAllowed(SoundData<?> data) {
        if (!ClientConfig.isPausedSound(data.NAME)) {
            return;
        }

        // allowed in advance by an early unpause call
        if (soundsToUnpause.remove(data.NAME)) {
            ModLogger.debug("{} started unpaused: allowed in advance.", data.NAME);
            return;
        }

        for (SoundData<?> other : getAllTrackedSoundData()) {
            if (other != data && other.NAME.equals(data.NAME) && other.isPausedState() && !other.isTerminated()) {
                // stopped on the main thread: by the timed updates, or by the engine for a prioritized instance
                other.terminate();
            }
        }

        data.setPaused(true);
        applyPauseChanges(data);
    }

    /**
     * Resumes a paused tracked sound. A resumed prioritized sound may become the current prioritized playing, or gets
     * its level.
     *
     * @param data the paused tracked sound
     */
    private static void unpause(SoundData<?> data) {
        data.setPaused(false);
        applyPauseChanges(data);

        if (data instanceof PrioritizedSoundData) {
            handleChangesForCurrentPrioritizedPlaying();
        }
    }

    /**
     * Allows the paused instances of the paused sounds a finished conditional sound's rules name.
     *
     * @param conditionalSound the finished conditional sound name
     */
    private static void onConditionalSoundFinished(String conditionalSound) {
        Set<String> pausedSounds = ClientConfig.getPausedSoundsOf(conditionalSound);

        if (pausedSounds.isEmpty()) {
            return;
        }

        for (SoundData<?> soundData : getAllTrackedSoundData()) {
            if (pausedSounds.contains(soundData.NAME) && soundData.isPausedState() && !soundData.isTerminated()) {
                unpause(soundData);
            }
        }
    }

    /**
     * Lists every tracked sound data, non-prioritized and prioritized.
     *
     * @return a new list of the tracked data
     */
    private static List<SoundData<?>> getAllTrackedSoundData() {
        List<SoundData<?>> all = new ArrayList<>(nonPrioritizedTrackedSounds.size() + prioritizedTrackedSounds.size());
        all.addAll(nonPrioritizedTrackedSounds.values());
        all.addAll(prioritizedTrackedSounds.values());
        return all;
    }

    private static void safelyStopSound(SoundInstance soundInstance) {
        if (soundInstance == null) return;

        ChannelAccess.ChannelHandle handle = getChannelHandle(soundInstance);
        if (handle == null || handle.isStopped()) {
            // Channel already invalid, skip stop to avoid OpenAL error
            return;
        }

        Minecraft.getInstance().getSoundManager().stop(soundInstance);
    }

    /**
     * Returns the client's sound engine.
     *
     * @return the sound engine
     */
    private static SoundEngine getSoundEngine() {
        return ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).getSoundEngine();
    }

    /**
     * Helper to get the ChannelHandle for a SoundInstance from SoundEngine internals.
     */
    @Nullable
    private static ChannelAccess.ChannelHandle getChannelHandle(SoundInstance instance) {
        if (instance == null) return null;
        Map<SoundInstance, ChannelAccess.ChannelHandle> map =
                ((SoundEngineAccessor) getSoundEngine()).getInstanceToChannel();
        return map.get(instance);
    }

    /**
     * Updates the current prioritized playing and every prioritized sound's level, with the configured fade out.
     */
    private static synchronized void handleChangesForCurrentPrioritizedPlaying() {
        handleChangesForCurrentPrioritizedPlaying(ClientConfig.fadeOutTicks());
    }

    /**
     * Updates the current prioritized playing and sets every prioritized sound's level relative to it (fades to an
     * unchanged target do nothing). If the current prioritized playing changed, the non-prioritized fades follow on
     * the next main thread tick. Synchronized: called from the sound engine thread and the main thread.
     *
     * @param fadeOutTicks the fade out duration of sounds going concealed, and of the other sounds coming back when
     *                     there is no current prioritized playing anymore
     */
    private static synchronized void handleChangesForCurrentPrioritizedPlaying(int fadeOutTicks) {
        PrioritizedSoundData newCurrentPlaying = getMostPrioritizedSoundPlaying();
        boolean currentPlayingChanged = newCurrentPlaying != currentPrioritizedPlaying;

        currentPrioritizedPlaying = newCurrentPlaying;
        retargetPrioritized(newCurrentPlaying, fadeOutTicks);

        if (currentPlayingChanged) {
            SoundSource playingSource = newCurrentPlaying == null ? null : newCurrentPlaying.getSoundInstance().getSource();
            int restoreTicks = newCurrentPlaying == null ? fadeOutTicks : ClientConfig.fadeInTicks();
            pendingCurrentPrioritizedPlayingUpdate.set(new CurrentPrioritizedPlayingUpdate(playingSource, restoreTicks));
        }
    }

    /**
     * Sets every tracked prioritized sound's target volume fraction relative to the current prioritized playing: it
     * fades in, the concealed, paused or stopping ones fade out, the less prioritized ones are muted in its mute group
     * (its category, music and records counting as one) and weakened (fadeOutHearingPercent) in other categories, in
     * step with its fade in.
     *
     * @param currentPlaying the current prioritized playing, null for none
     * @param fadeOutTicks   the fade out duration of sounds going concealed
     */
    private static void retargetPrioritized(@Nullable PrioritizedSoundData currentPlaying, int fadeOutTicks) {
        int fadeInTicks = ClientConfig.fadeInTicks();
        SoundSource playingSource = currentPlaying == null ? null : currentPlaying.getSoundInstance().getSource();

        for (PrioritizedSoundData data : prioritizedTrackedSounds.values()) {
            PrioritizedSoundInstance instance = data.getSoundInstance();

            if (data == currentPlaying) {
                data.startFadeIn(fadeInTicks);
            } else if (currentPlaying == null || data.isConcealedByApi() || data.isPausedState() || instance.isStoppingAfterFadeOut()) {
                data.startFadeOut(fadeOutTicks, true);
            } else if (isSameMuteGroup(instance.getSource(), playingSource)) {
                data.startFadeOut(fadeInTicks, true);
            } else {
                data.startFadeToHearing(ClientConfig.fadeOutHearing(), fadeInTicks);
            }
        }
    }

    /**
     * If the finished prioritized instance was the current prioritized playing, lets the next prioritized sound become
     * it, or the other sounds come back.
     *
     * @param removed the finished prioritized instance
     */
    private static synchronized void onPrioritizedSoundRemoved(PrioritizedSoundInstance removed) {
        PrioritizedSoundData currentPlaying = currentPrioritizedPlaying;

        if (currentPlaying != null && currentPlaying.getSoundInstance() == removed) {
            handleChangesForCurrentPrioritizedPlaying(ClientConfig.fadeOutTicks());
        }
    }

    /**
     * Picks the current prioritized playing: the highest priority value among the playing prioritized sounds not
     * concealed by a mod, not paused and not stopping. On equal priority, the current one stays.
     *
     * @return the current prioritized playing, null if none
     */
    @Nullable
    private static PrioritizedSoundData getMostPrioritizedSoundPlaying() {
        PrioritizedSoundData result = null;
        PrioritizedSoundData current = currentPrioritizedPlaying;

        for (PrioritizedSoundData data : prioritizedTrackedSounds.values()) {
            PrioritizedSoundInstance instance = data.getSoundInstance();

            if (data.isConcealedByApi() || data.isPausedState() || instance.isStoppingAfterFadeOut() || instance.isStopped()) {
                continue;
            }

            if (result == null
                    || data.getPriority() > result.getPriority()
                    || (data.getPriority() == result.getPriority() && data == current)) {
                result = data;
            }
        }

        return result;
    }
}
