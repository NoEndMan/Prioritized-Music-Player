package net.flameslight.prioritizedmusicplayer.events;

import net.flameslight.prioritizedmusicplayer.PrioritizedMusicPlayer;
import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler.ModdedEntry;
import net.flameslight.prioritizedmusicplayer.network.PMPNetwork;
import net.flameslight.prioritizedmusicplayer.network.packets.S2CServerModEntriesPacket;
import net.flameslight.prioritizedmusicplayer.server.ServerMusicEntries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * Forge bus events of the server (dedicated or integrated): sends the server mods' entries to every player who joins.
 */
@Mod.EventBusSubscriber(modid = PrioritizedMusicPlayer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ServerEvents {
    /**
     * Sends the entries mods registered through ServerRegisterPrioritizationEvent to the joining player's client.
     *
     * @param event the login event
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        List<ModdedEntry> entries = ServerMusicEntries.getEntries();

        if (!entries.isEmpty() && event.getEntity() instanceof ServerPlayer player) {
            PMPNetwork.sendToPlayer(player, new S2CServerModEntriesPacket(entries));
        }
    }
}
