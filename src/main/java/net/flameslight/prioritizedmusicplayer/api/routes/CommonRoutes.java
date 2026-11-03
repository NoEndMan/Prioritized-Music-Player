package net.flameslight.prioritizedmusicplayer.api.routes;

import net.flameslight.prioritizedmusicplayer.ClientMusicHandler;
import net.flameslight.prioritizedmusicplayer.api.event.ClientRegisterPrioritizationEvent;

/**
 * API other mods use to control Prioritized Music Player at runtime on the client.
 * <p>
 * Each method routes to whatever the mod does internally, so its own classes can be renamed, moved or rewritten
 * freely. Existing methods are never removed or changed. A new feature adds a new method and raises
 * {@link #API_VERSION}, which callers check through {@link #apiVersion()} before using it. The version covers the
 * whole API: this class, {@link ServerRoutes} and {@link ClientRegisterPrioritizationEvent}.
 * The playback methods below are client side only and must be called on the client main thread. Client classes are
 * referenced only inside method bodies, so loading this class elsewhere is safe.
 */
public final class CommonRoutes {
    /** Raised whenever a method is added. Existing methods never change. Read it through apiVersion(). */
    public static final int API_VERSION = 1;

    /**
     * Returns this API's version.
     * <p>
     * A method, not the field itself: API_VERSION is a compile-time constant, so a caller reading the field
     * would get the value of the jar it was compiled against, not the installed one.
     *
     * @return API_VERSION of the installed Prioritized Music Player
     */
    public static int apiVersion() {
        return API_VERSION;
    }

    /**
     * Allows paused instances of a soundsToPauseUntilAllowed sound to resume playing, in custom conditions. If no
     * instance is paused yet, the sound's next start isn't paused. Client main thread only.
     *
     * @param sound the sound name, without a namespace
     */
    public static void unpauseSound(String sound) {
        ClientMusicHandler.markSoundToUnpause(sound);
    }

    /**
     * Hides (fades out, keeps playing) or reveals a playing prioritized sound without stopping it. Only instances
     * playing at that moment are affected. Client main thread only.
     *
     * @param sound     the sound name, without a namespace
     * @param concealed true to hide, false to reveal
     */
    public static void setPrioritizedMusicConcealed(String sound, boolean concealed) {
        ClientMusicHandler.setPrioritizedMusicConcealed(sound, concealed);
    }

    /**
     * Tells whether there is a current prioritized playing right now: a prioritized sound playing that isn't
     * concealed, paused or fading out to stop.
     *
     * @return true if there is a current prioritized playing
     */
    public static boolean isPlayingPrioritizedMusic() {
        return ClientMusicHandler.isPlayingPrioritizedSound();
    }
}
