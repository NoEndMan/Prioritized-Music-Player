package net.flameslight.prioritizedmusicplayer.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.flameslight.prioritizedmusicplayer.ClientMusicHandler;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Lets the owner of a replaced prioritized sound still stop and query it: SoundEngine.stop and SoundEngine.isActive
 * called with the played instance work on its PrioritizedSoundInstance replacement. Without it, for example, a
 * jukebox disc would keep playing after being taken out, and vanilla's music manager would think its music ended.
 * Also makes a stop by name (SoundEngine.stop(name, source), which the stop sound packet ends in) fade out the
 * prioritized sounds it matches instead of cutting them, skips those sounds in vanilla's stop, and keeps
 * ClientMusicHandler right after stopAll. Hooking the sound engine instead of the packet handler keeps the fade working
 * whatever other mods do to ClientPacketListener.
 * <p>
 * Uses MixinExtras WrapMethod, which chains with other mods' wrappers of the same methods.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(SoundEngine.class)
public class SoundEngineStopMixin {
    /**
     * Stops the replacement of a replaced instance instead of the instance itself, unless it is a prioritized sound
     * fading out for the stop by name being handled.
     *
     * @param soundInstance the instance to stop
     * @param original      the wrapped stop
     */
    @WrapMethod(method = "stop(Lnet/minecraft/client/resources/sounds/SoundInstance;)V")
    private void prioritizedmusicplayer$stopReplacement(SoundInstance soundInstance, Operation<Void> original) {
        SoundInstance playing = ClientMusicHandler.getPlayingInstance(soundInstance);

        if (ClientMusicHandler.isSkippedByStopByName(playing)) {
            return;
        }

        original.call(playing);
    }

    /**
     * Hands a stop by name to ClientMusicHandler, which fades the matching prioritized sounds and runs vanilla's stop
     * for the rest.
     *
     * @param name     the sound name, null for any
     * @param source   the category, null for any
     * @param original the wrapped stop by name
     */
    @WrapMethod(method = "stop(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/sounds/SoundSource;)V")
    private void prioritizedmusicplayer$fadeOutPrioritizedOnStopByName(ResourceLocation name, SoundSource source,
                                                                       Operation<Void> original) {
        ClientMusicHandler.handleStopByName(name, source, () -> original.call(name, source));
    }

    /**
     * Checks the replacement of a replaced instance instead of the instance itself.
     *
     * @param soundInstance the instance to check
     * @param original      the wrapped isActive
     * @return whether the instance, or its replacement, is playing
     */
    @WrapMethod(method = "isActive(Lnet/minecraft/client/resources/sounds/SoundInstance;)Z")
    private boolean prioritizedmusicplayer$isActiveReplacement(SoundInstance soundInstance, Operation<Boolean> original) {
        return original.call(ClientMusicHandler.getPlayingInstance(soundInstance));
    }

    /**
     * Runs stopAll, then lets ClientMusicHandler forget every sound: stopAll clears the engine's maps without the
     * removal hook.
     *
     * @param original the wrapped stopAll
     */
    @WrapMethod(method = "stopAll")
    private void prioritizedmusicplayer$onStopAll(Operation<Void> original) {
        original.call();
        ClientMusicHandler.onAllSoundsStopped();
    }
}
