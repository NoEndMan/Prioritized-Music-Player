package net.flameslight.prioritizedmusicplayer.api.event;

/**
 * Mod bus event where mods on the server register entries for the client config of every player who joins.
 * <p>
 * Posted once, during common setup (after every mod is constructed, before any world or server starts), on both
 * physical sides, so single player's integrated server sends entries too. The server sends the collected entries to
 * each player on login, where they are written to the player's client config. The add methods and their rules are in
 * {@link RegisterPrioritizationEvent}. The user may block these mods or whitelist some of them in the client config.
 */
public class ServerRegisterPrioritizationEvent extends RegisterPrioritizationEvent {
}
