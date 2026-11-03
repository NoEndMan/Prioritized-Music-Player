package net.flameslight.prioritizedmusicplayer.types.sound;

/**
 * A playing sound's prioritization status, as /pmp nowplaying shows it. The constants are in display order.
 */
public enum PrioritizationStatus {
    /** The current prioritized playing. */
    MOST_PRIORITIZED("most prioritized"),
    /** Faded, or fading, to the muted level. */
    MUTED("muted"),
    /** Faded, or fading, to fadeOutHearingPercent. */
    WEAKENED("weakened"),
    /** Paused by a soundsToPauseUntilAllowed rule, until it is allowed. */
    PAUSED("paused"),
    /** Not affected by PMP. */
    NONE("");

    private final String text;

    PrioritizationStatus(String text) {
        this.text = text;
    }

    /**
     * Returns the status as shown in chat.
     *
     * @return the chat text, empty for NONE
     */
    public String getText() {
        return this.text;
    }
}
