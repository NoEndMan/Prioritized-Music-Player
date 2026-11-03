package net.flameslight.prioritizedmusicplayer.api.event;

/**
 * Client side mod bus event where client mods register their entries for Prioritized Music Player's client config.
 * <p>
 * Posted once, on the client only, when the client config first loads: after every mod is constructed and before
 * common and client setup. Subscribe on your mod event bus, on the client only, from a class that only loads when
 * Prioritized Music Player is installed. The add methods and their rules are in {@link RegisterPrioritizationEvent}.
 * The user may block client mods or whitelist some of them in the client config.
 */
public class ClientRegisterPrioritizationEvent extends RegisterPrioritizationEvent {
}
