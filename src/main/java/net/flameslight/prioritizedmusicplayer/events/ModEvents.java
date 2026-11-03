package net.flameslight.prioritizedmusicplayer.events;

import net.flameslight.prioritizedmusicplayer.PrioritizedMusicPlayer;
import net.flameslight.prioritizedmusicplayer.config.ClientConfig;
import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler;
import net.flameslight.prioritizedmusicplayer.server.ServerMusicEntries;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Mod bus events of the client
 */
@Mod.EventBusSubscriber(modid = PrioritizedMusicPlayer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEvents {
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        /*
         * keeps the client config caches current and lets other client mods add their
         * entries once the client config first loads.
         */
        event.enqueueWork(ServerMusicEntries::registerServerModsEntries);
    }

    /**
     * called only on client side, refreshes the client config caches on load and reload. On the first load, the earliest point the client config
     * can be written, it also posts ClientRegisterPrioritizationEvent and writes what client mods registered.
     *
     * @param event any mod config event, only this mod's client config is handled
     */
    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public static void onConfigLoaded(ModConfigEvent event) {
        if (event instanceof ModConfigEvent.Unloading
                || event.getConfig().getSpec() != ClientConfig.CLIENT_SPEC) {
            return;
        }

        ClientConfig.refresh();

        if (event instanceof ModConfigEvent.Loading) {
            ModdedAddedEntriesHandler.registerClientModsEntries();
        }
    }
}
