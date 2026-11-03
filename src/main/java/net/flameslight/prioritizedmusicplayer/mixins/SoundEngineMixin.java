package net.flameslight.prioritizedmusicplayer.mixins;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.flameslight.prioritizedmusicplayer.ClientMusicHandler;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Iterator;

/**
 * Applies ClientMusicHandler's volume rules to vanilla's volume calculation, and tells it when a sound finishes.
 * <p>
 * The volume hooks are MixinExtras ModifyReturnValue and ModifyExpressionValue: they chain with other mods'
 * injections and create no object per call, which matters since calculateVolume runs for every ticking sound on every
 * tick.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(SoundEngine.class)
public class SoundEngineMixin {
    /**
     * Applies the volume rules to the volume play(...) starts a sound with. The played instance is read from play's
     * parameter at the call, so it is the instance actually played, after Forge's PlaySoundEvent may have replaced it.
     *
     * @param volume  vanilla's calculateVolume(float, SoundSource) result
     * @param current the instance play(...) is starting
     * @return the volume to use
     */
    @ModifyExpressionValue(
            method = "play",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"
            )
    )
    private float prioritizedmusicplayer$adjustPlayVolume(float volume, @Local(argsOnly = true) SoundInstance current) {
        return ClientMusicHandler.adjustVolume((SoundEngine) (Object) this, current, volume);
    }

    /**
     * Applies the volume rules to calculateVolume(SoundInstance), used for ticking sounds every tick, for every channel
     * on a category volume change, and for PMP's own volume push.
     *
     * @param volume   vanilla's result
     * @param instance the sound
     * @return the volume to use
     */
    @ModifyReturnValue(
            method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
            at = @At("RETURN")
    )
    private float prioritizedmusicplayer$adjustInstanceVolume(float volume, @Local(argsOnly = true) SoundInstance instance) {
        return ClientMusicHandler.adjustVolume((SoundEngine) (Object) this, instance, volume);
    }

    /**
     * Wraps Iterator.remove() calls inside tickNonPaused: tells ClientMusicHandler the removed sound finished, runs the
     * vanilla removal, then drops the manual loop re-queue of a sound stopped by a stop sound packet with no name and
     * no source, as stopAll would. The removed sound is the only SoundInstance local in scope at each remove() call.
     *
     * @param iterator the iterated iterator
     * @param original the real remove() call
     * @param sound    the sound the iterator removes
     */
    @WrapOperation(
            method = "tickNonPaused",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Iterator;remove()V"
            )
    )
    private void prioritizedmusicplayer$onSoundRemoved(Iterator<?> iterator, Operation<Void> original, @Local SoundInstance sound) {
        if (sound != null) {
            ClientMusicHandler.removeTrackedSoundBySoundInstance(sound);
        }

        original.call(iterator); // vanilla removal

        if (sound != null && ClientMusicHandler.consumeStoppedByStopAllPacket(sound)) {
            ((SoundEngineAccessor) (Object) this).getQueuedSounds().remove(sound);
        }
    }
}
