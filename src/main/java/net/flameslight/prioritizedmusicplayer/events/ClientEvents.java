package net.flameslight.prioritizedmusicplayer.events;

import net.flameslight.prioritizedmusicplayer.ClientMusicHandler;
import net.flameslight.prioritizedmusicplayer.PrioritizedMusicPlayer;
import net.flameslight.prioritizedmusicplayer.config.ClientConfig;
import net.flameslight.prioritizedmusicplayer.types.sound.prioritized.PrioritizedSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.client.event.sound.PlaySoundSourceEvent;
import net.minecraftforge.client.event.sound.PlayStreamingSourceEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge bus events of the client: replaces prioritized sounds when they are played, tracks started sounds, ticks
 * the music handler, clears saved sound data when the client enters or leaves a world, and stops prioritized music at
 * once when the player leaves the level (game, dimension change, respawn).
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = PrioritizedMusicPlayer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientEvents {
    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientMusicHandler.clean();
    }

    /**
     * Leaving the game: stops prioritized music at once and clears saved sound data.
     *
     * @param event the logout event
     */
    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientMusicHandler.clean();
    }

    /**
     * Changing dimension or respawning after death (the client player is recreated): stops prioritized music at once,
     * with no fade, and puts every sound category back to normal at once.
     *
     * @param event the player clone event
     */
    @SubscribeEvent
    public static void onClientPlayerClone(ClientPlayerNetworkEvent.Clone event) {
        ClientMusicHandler.stopPrioritizedInstantly();
    }

    /**
     * Replaces a played instance of a prioritizedMusicList sound with a PrioritizedSoundInstance. Every other sound
     * is left as it was played.
     *
     * @param event the play event, fired by SoundEngine.play on the client main thread
     */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();

        if (sound == null || sound instanceof PrioritizedSoundInstance) {
            return;
        }

        Integer priority = ClientConfig.getPriority(sound.getLocation().getPath());

        if (priority != null) {
            event.setSound(ClientMusicHandler.createPrioritized(sound, priority));
        }
    }

    @SubscribeEvent
    public static void onPlaySoundSource(PlaySoundSourceEvent event) {
        // PlaySoundSourceEvent is fired for non-streaming sounds (buffers).
        ClientMusicHandler.handleNewSoundFromEvent(event.getSound(), event.getChannel());
    }

    @SubscribeEvent
    public static void onPlayStreamingSource(PlayStreamingSourceEvent event) {
        // PlayStreamingSourceEvent is fired for streaming sounds (jukeboxes/long music).
        ClientMusicHandler.handleNewSoundFromEvent(event.getSound(), event.getChannel());
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if(event.phase == TickEvent.Phase.END) {
            ClientMusicHandler.updateEveryClientTick();
        }
    }
}
