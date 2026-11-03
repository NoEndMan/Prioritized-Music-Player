package net.flameslight.prioritizedmusicplayer.types.sound.prioritized;

import net.flameslight.prioritizedmusicplayer.types.sound.SoundData;

/**
 * Extra data kept for a playing PrioritizedSoundInstance, only in ClientMusicHandler's maps: its priority and
 * whether another mod concealed it (CommonRoutes.setPrioritizedMusicConcealed). Fading and stopping are driven
 * through the instance itself, which fades its hearing on its own tick.
 */
public class PrioritizedSoundData extends SoundData<PrioritizedSoundInstance> {
    private volatile boolean isConcealedByApi = false;

    /**
     * Creates the data of a started prioritized instance.
     *
     * @param prioritizedSoundInstance the started instance
     */
    public PrioritizedSoundData(PrioritizedSoundInstance prioritizedSoundInstance) {
        super(prioritizedSoundInstance, prioritizedSoundInstance.isConcealed(), 1f);
    }

    /**
     * Tells whether another mod concealed this sound.
     *
     * @return true if concealed through CommonRoutes.setPrioritizedMusicConcealed
     */
    public boolean isConcealedByApi() {
        return isConcealedByApi;
    }

    /**
     * Sets whether another mod conceals this sound.
     *
     * @param isConcealedByApi true to conceal, false to reveal
     */
    public void setConcealedByApi(boolean isConcealedByApi) {
        this.isConcealedByApi = isConcealedByApi;
    }

    public int getPriority() {
        return this.getSoundInstance().priority;
    }

    /**
     * The instance fades on its own tick and its owner stops it, so this data never asks for removal.
     *
     * @return always false
     */
    @Override
    public boolean tick() {
        return false;
    }

    /**
     * Fades the sound to full hearing.
     *
     * @param ticks the fade duration in ticks
     */
    @Override
    public void startFadeIn(int ticks) {
        this.getSoundInstance().startFadeTo(1f, ticks);
    }

    /**
     * Fades the sound to the concealed hearing. It keeps playing.
     *
     * @param ticks                     the fade duration in ticks
     * @param toKeepPlayingAfterFadeOut unused: a prioritized sound is only stopped by its owner or a stop packet
     */
    @Override
    public void startFadeOut(int ticks, boolean toKeepPlayingAfterFadeOut) {
        this.getSoundInstance().startFadeTo(SoundData.CONCEALED_VOLUME, ticks);
    }

    /**
     * Fades the sound to a hearing fraction, used to weaken a less prioritized sound of another category.
     *
     * @param hearing the target hearing, from 0 to 1
     * @param ticks   the fade duration in ticks
     */
    @Override
    public void startFadeToHearing(float hearing, int ticks) {
        this.getSoundInstance().startFadeTo(hearing, ticks);
    }

    @Override
    public void terminate() {
        this.getSoundInstance().stopSound();
    }
}
