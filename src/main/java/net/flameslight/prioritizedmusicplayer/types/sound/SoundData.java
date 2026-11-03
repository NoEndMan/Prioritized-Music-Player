package net.flameslight.prioritizedmusicplayer.types.sound;

import net.minecraft.client.resources.sounds.SoundInstance;

/**
 * A tracked playing sound and its fade, from IHBRT's SoundData.
 * <p>
 * For a non-prioritized sound, MAX_VOLUME is 1 and the current volume is the fraction of vanilla's own volume it is
 * played at: CONCEALED_VOLUME when muted, the fadeOutHearingPercent fraction when weakened, 1 when normal.
 */
public class SoundData<T extends SoundInstance> {
    /** The lowest volume: muted sounds keep playing silently, since vanilla stops or skips a sound at volume 0. */
    public static final float CONCEALED_VOLUME = 0.001F;
    public final float MAX_VOLUME;
    public final String NAME;
    private final T soundInstance;
    private float volumeChangePerTick;
    private volatile float currentVolume;
    /** The volume the current fade stops at: MAX_VOLUME, CONCEALED_VOLUME or a weakened hearing in between. */
    private float fadeTarget;
    private boolean isConcealedByPrioritizing;
    private boolean isFadingOut = false;
    private boolean isFadingIn = false;
    private boolean isRequiresVolumeUpdate = false;
    private boolean isPaused = false;
    private boolean isTerminated = false;

    public SoundData(T soundInstance, boolean isConcealedByPrioritizing, float maxVolume) {
        this.MAX_VOLUME = maxVolume;
        this.NAME = soundInstance.getLocation().getPath();
        this.isConcealedByPrioritizing = isConcealedByPrioritizing;
        this.soundInstance = soundInstance;
        this.currentVolume = isConcealedByPrioritizing ? CONCEALED_VOLUME : this.MAX_VOLUME;
        this.fadeTarget = this.currentVolume;
    }

    /**
     * Fades the volume up to MAX_VOLUME.
     *
     * @param ticks the duration of a fade over the full range, in ticks
     */
    public void startFadeIn(int ticks) {
        this.volumeChangePerTick = (MAX_VOLUME - CONCEALED_VOLUME) / ticks;
        this.fadeTarget = MAX_VOLUME;
        this.isFadingIn = true;
        this.isFadingOut = false;
        this.isConcealedByPrioritizing = false;
    }

    /**
     * Fades the volume down to CONCEALED_VOLUME.
     *
     * @param ticks                     the duration of a fade over the full range, in ticks
     * @param toKeepPlayingAfterFadeOut false to have tick() ask for the sound's removal once the fade ends
     */
    public void startFadeOut(int ticks, boolean toKeepPlayingAfterFadeOut) {
        this.volumeChangePerTick = (MAX_VOLUME - CONCEALED_VOLUME) / ticks;
        this.fadeTarget = CONCEALED_VOLUME;
        this.isFadingIn = false;
        this.isFadingOut = true;
        this.isConcealedByPrioritizing = toKeepPlayingAfterFadeOut;
    }

    /**
     * Fades the volume to a hearing level between CONCEALED_VOLUME and MAX_VOLUME, up or down. The sound keeps playing
     * once the fade ends. Used to weaken a sound.
     *
     * @param hearing the target volume, clamped to CONCEALED_VOLUME and MAX_VOLUME
     * @param ticks   the duration of a fade over the full range, in ticks
     */
    public void startFadeToHearing(float hearing, int ticks) {
        float target = Math.max(CONCEALED_VOLUME, Math.min(MAX_VOLUME, hearing));

        if (this.getCurrentVolume() < target) {
            this.startFadeIn(ticks);
        } else {
            this.startFadeOut(ticks, true);
        }

        this.fadeTarget = target;
    }

    /**
     * Function has side effects on SoundData instance
     * @return true if the fade-out ended and the sound should be stopped
     */
    public boolean tick() {
        if (isFadingIn) {
            if (this.getCurrentVolume() < fadeTarget) {
                this.setCurrentVolume(Math.min(this.getCurrentVolume() + volumeChangePerTick, fadeTarget));
                this.isRequiresVolumeUpdate = true;
            } else
                this.isFadingIn = false;
        } else if (isFadingOut) {
            if (this.getCurrentVolume() > fadeTarget) {
                this.setCurrentVolume(Math.max(this.getCurrentVolume() - volumeChangePerTick, fadeTarget));
                this.isRequiresVolumeUpdate = true;
            } else {
                isFadingOut = false;
                boolean toRemove = !this.isConcealedByPrioritizing;

                // Stop the sound when fade-out is complete and not concealed
                return toRemove;
            }
        }

        return false;
    }

    public float getCurrentVolume() {
        return this.currentVolume;
    }

    /**
     * Sets the volume at once. A running fade continues from it.
     *
     * @param newVolume the volume
     */
    public void setCurrentVolume(float newVolume) {
        this.currentVolume = newVolume;
    }

    /**
     * Sets the volume and the fade target at once and stops any running fade. A volume below MAX_VOLUME counts as
     * concealed by prioritizing, as after a finished fade-out to it, so the sound is never asked to stop. Used for a
     * sound that starts while a prioritized sound plays.
     *
     * @param volume the volume, from CONCEALED_VOLUME to MAX_VOLUME
     */
    public void setVolumeAtOnce(float volume) {
        this.currentVolume = volume;
        this.fadeTarget = volume;
        this.isFadingIn = false;
        this.isFadingOut = false;
        this.isConcealedByPrioritizing = volume < MAX_VOLUME;
    }

    /**
     * Reads the volume the current fade heads to. Used by /pmp nowplaying.
     *
     * @return the fade target, from CONCEALED_VOLUME to MAX_VOLUME
     */
    public float getFadeTarget() {
        return this.fadeTarget;
    }

    public boolean isConcealedByPrioritizing() {
        return isConcealedByPrioritizing;
    }

    public T getSoundInstance() {
        return this.soundInstance;
    }

    public boolean isRequiresVolumeUpdate() {
        return isRequiresVolumeUpdate;
    }

    public boolean isPausedState() {
        return this.isPaused;
    }

    public void setPaused(boolean paused) {
        this.isPaused = paused;
    }

    /**
     * Marks the current volume as applied to the channel, so the timed update doesn't push it again until the fade
     * changes it. IHBRT set the flag to true here, which pushed every tracked sound's volume every update.
     */
    public void flagVolumeAsUpdated() {
        this.isRequiresVolumeUpdate = false;
    }

    public boolean isTerminated() { return isTerminated; }
    public void terminate() { this.isTerminated = true; }
}
