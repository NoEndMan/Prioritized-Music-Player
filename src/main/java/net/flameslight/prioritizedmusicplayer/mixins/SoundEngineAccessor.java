package net.flameslight.prioritizedmusicplayer.mixins;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Map;

/**
 * Read access to SoundEngine's private sound maps and volume calculation, used by ClientMusicHandler.
 */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("instanceToChannel")
    Map<SoundInstance, ChannelAccess.ChannelHandle> getInstanceToChannel();

    /**
     * Returns the delayed and manually looping sounds waiting to start.
     *
     * @return SoundEngine.queuedSounds, sound to start tick
     */
    @Accessor("queuedSounds")
    Map<SoundInstance, Integer> getQueuedSounds();

    /**
     * Returns the ticking sounds queued to start on the next tick.
     *
     * @return SoundEngine.queuedTickableSounds
     */
    @Accessor("queuedTickableSounds")
    List<TickableSoundInstance> getQueuedTickableSounds();

    /**
     * Calls SoundEngine.getVolume(SoundSource): the user's slider for a category, 1 for MASTER.
     *
     * @param source the category
     * @return the category's volume, from 0 to 1
     */
    @Invoker("getVolume")
    float invokeGetVolume(SoundSource source);
}
