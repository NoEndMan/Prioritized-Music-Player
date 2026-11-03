package net.flameslight.prioritizedmusicplayer.types.sound.prioritized;

import net.flameslight.prioritizedmusicplayer.ClientMusicHandler;
import net.flameslight.prioritizedmusicplayer.logger.ModLogger;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * A playing Minecraft SoundInstance of a sound listed in prioritizedMusicList, with the prioritization logic.
 * <p>
 * When a configured sound starts, ClientMusicHandler replaces the played instance (the original) with this one. It
 * copies the original's sound, source, volume, pitch, position, attenuation and looping, plays the original's own
 * resolved sound and stream, and fades itself in and out. If the original is itself tickable, it is ticked from here
 * every tick, so its own logic and its own stop still apply, and its position, pitch and volume are copied again every
 * SOUND_UPDATE_DELAY_TICKS: the owning mod keeps control of its sound, for example its own fades. Only configured
 * sounds are replaced. Every other sound instance stays as it was played.
 * <p>
 * PMP's fade moves a hearing fraction (from the concealed volume to 1) on top of the original's volume: the volume
 * hooks turn the original's volume times the hearing into the played volume, so it follows the category slider.
 * <p>
 * Minecraft doesn't replace AbstractTickableSoundInstance instances, so this instance is caught later in the forge
 * sound events and tracked as PrioritizedSoundData.
 */
public class PrioritizedSoundInstance extends AbstractTickableSoundInstance {
    private static final float CONCEALED_VOLUME = 0.001F;

    /** The sound's priority from prioritizedMusicList, a higher value wins. */
    public final int priority;
    private final SoundInstance original;
    private volatile float hearing;
    private volatile float targetHearing;
    private volatile float hearingChangePerTick = 0;
    private volatile boolean stopAfterFadeOut = false;
    /** Ticks since the fade-out to stop started, only for the debug line logged when it stops. */
    private int fadeOutAndStopTicks = 0;
    /** Counts down to the next copy of a ticking original's position, pitch and volume. */
    private int followOriginalTicksCounter = ClientMusicHandler.SOUND_UPDATE_DELAY_TICKS;

    /**
     * Creates the replacement of a played instance. The original must already be resolved (its sound picked), so its
     * volume and pitch can be read.
     *
     * @param original       the played instance being replaced
     * @param priority       the sound's priority from prioritizedMusicList
     * @param startConcealed true to start at the concealed hearing, then fade to the level ClientMusicHandler sets
     */
    public PrioritizedSoundInstance(SoundInstance original, int priority, boolean startConcealed) {
        super(SoundEvent.createVariableRangeEvent(original.getLocation()), original.getSource(), RandomSource.create());

        this.original = original;
        this.priority = priority;
        this.hearing = startConcealed ? CONCEALED_VOLUME : 1f;
        this.targetHearing = this.hearing;

        this.relative = original.isRelative();
        this.attenuation = original.getAttenuation();
        this.looping = original.isLooping();
        this.delay = original.getDelay();
        this.pitch = original.getPitch();
        this.x = original.getX();
        this.y = original.getY();
        this.z = original.getZ();
        this.volume = original.getVolume();
    }

    /**
     * Plays the original's resolved sound, so the same variant (and any custom sound of the original) is heard.
     *
     * @param soundManager the client sound manager
     * @return the sound events of this sound, null if the sound is unknown
     */
    @Nullable
    @Override
    public WeighedSoundEvents resolve(SoundManager soundManager) {
        if (this.original.getSound() == null) {
            this.original.resolve(soundManager);
        }
        this.sound = this.original.getSound();
        return soundManager.getSoundEvent(this.original.getLocation());
    }

    /**
     * Uses the original's stream, so mods with a custom audio stream keep working.
     *
     * @param soundBuffers the sound buffer library
     * @param sound        the resolved sound
     * @param looping      whether the sound loops
     * @return the original's audio stream
     */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return this.original.getStream(soundBuffers, sound, looping);
    }

    /**
     * Ticks a tickable original every tick (stopping at once when it stops) and copies its position, pitch and volume
     * every SOUND_UPDATE_DELAY_TICKS, then steps the hearing toward its target with IHBRT's fade math. A fade-out
     * marked to stop stops the sound once it is concealed.
     */
    @Override
    public void tick() {
        if (this.original instanceof TickableSoundInstance tickableOriginal) {
            if (!tickableOriginal.isStopped()) {
                tickableOriginal.tick();
            }
            if (tickableOriginal.isStopped()) {
                this.stop();
                return;
            }

            this.followOriginalTicksCounter--;

            if (this.followOriginalTicksCounter < 1) {
                this.followOriginalTicksCounter = ClientMusicHandler.SOUND_UPDATE_DELAY_TICKS;
                this.x = this.original.getX();
                this.y = this.original.getY();
                this.z = this.original.getZ();
                this.pitch = this.original.getPitch();
                this.volume = this.original.getVolume();
            }
        }

        float current = this.hearing;
        float goal = this.targetHearing;

        if (current != goal) {
            this.hearing = current < goal
                    ? Math.min(current + hearingChangePerTick, goal)
                    : Math.max(current - hearingChangePerTick, goal);
        }

        if (this.stopAfterFadeOut) {
            this.fadeOutAndStopTicks++;

            if (this.hearing <= CONCEALED_VOLUME) {
                ModLogger.debug("Prioritized sound {} finished its fade-out after {} ticks and stops.",
                        this.getLocation(), this.fadeOutAndStopTicks);
                this.stop();
            }
        }
    }

    /**
     * Starts fading the hearing to a target. Does nothing if it already fades to (or is at) that target. A fade-out
     * marked to stop keeps its mark.
     *
     * @param target the target hearing, from the concealed volume to 1
     * @param ticks  the fade duration in ticks
     */
    public void startFadeTo(float target, int ticks) {
        float clampedTarget = Math.max(CONCEALED_VOLUME, Math.min(1f, target));

        if (clampedTarget == this.targetHearing) {
            return;
        }

        this.hearingChangePerTick = Math.abs(clampedTarget - this.hearing) / Math.max(1, ticks);
        this.targetHearing = clampedTarget;
    }

    /**
     * Fades the sound out and stops it once concealed. Used for a stop by name (the stop sound packet).
     *
     * @param ticks the fade out duration in ticks
     */
    public void startFadeOutAndStop(int ticks) {
        this.stopAfterFadeOut = true;
        startFadeTo(CONCEALED_VOLUME, ticks);
    }

    /**
     * Tells whether the sound fades out to stop (stop by name).
     *
     * @return true if it stops once its fade-out ends
     */
    public boolean isStoppingAfterFadeOut() {
        return this.stopAfterFadeOut;
    }

    /**
     * Tells whether the sound is at the concealed hearing.
     *
     * @return true if concealed
     */
    public boolean isConcealed() {
        return this.hearing <= CONCEALED_VOLUME;
    }

    /**
     * Reads the current hearing fraction, which the volume hooks turn into the played volume.
     *
     * @return the hearing, from the concealed volume to 1
     */
    public float getHearing() {
        return this.hearing;
    }

    /**
     * Reads the hearing fraction the current fade heads to. Used by /pmp nowplaying.
     *
     * @return the target hearing, from the concealed volume to 1
     */
    public float getTargetHearing() {
        return this.targetHearing;
    }

    public void stopSound() {
        this.stop();
    }

    /**
     * Returns the played instance this one replaced.
     *
     * @return the original instance
     */
    public SoundInstance getOriginal() {
        return this.original;
    }
}
