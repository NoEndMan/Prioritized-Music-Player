package net.flameslight.prioritizedmusicplayer.logger;

import net.flameslight.prioritizedmusicplayer.PrioritizedMusicPlayer;
import net.flameslight.prioritizedmusicplayer.config.ClientConfig;
import org.slf4j.Logger;

import java.text.MessageFormat;

/**
 * The mod's logger: prefixes every line with the mod's name and prints debug lines only when debugMod is on.
 */
public class ModLogger {
    private static final Logger LOGGER = PrioritizedMusicPlayer.LOGGER;

    public static void info(String message, Object ... args) {
        LOGGER.info(buildMessageLog(message), args);
    }

    /**
     * Logs a [DEBUG] line at debug level, only when debugMod is on. Forge writes debug level to logs/debug.log.
     *
     * @param message the message, with {} placeholders
     * @param args    the placeholder values
     */
    public static void debug(String message, Object ... args) {
        if(ClientConfig.isDebugMod())
            LOGGER.debug(buildMessageLog("[DEBUG]: " + message), args);
    }

    public static void warn(String message, Object ... args) {
        LOGGER.warn(buildMessageLog(message), args);
    }

    public static void error(String message, Object ... args) {
        LOGGER.error(buildMessageLog(message), args);
    }

    public static void error(String message, Throwable error) {
        LOGGER.error(buildMessageLog(message), error);
    }

    /**
     * Prefixes a message with the logger's name.
     *
     * @param message the message
     * @return "PrioritizedMusicPlayer-logger: message"
     */
    private static String buildMessageLog(String message) {
        return MessageFormat.format("{0}: {1}", "PrioritizedMusicPlayer-logger", message);
    }
}
