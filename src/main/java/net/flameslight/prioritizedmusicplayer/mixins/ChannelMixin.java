package net.flameslight.prioritizedmusicplayer.mixins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.audio.Channel;
import net.flameslight.prioritizedmusicplayer.ClientMusicHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Keeps sounds paused by ClientMusicHandler paused: skips any other resume of their channel, for example the resume
 * of every channel when the game's pause menu closes.
 * <p>
 * Uses MixinExtras WrapMethod, which chains with other mods' wrappers of the same method.
 */
@OnlyIn(Dist.CLIENT)
@Mixin(Channel.class)
public class ChannelMixin {
    /**
     * Runs the unpaused only if the channel's sound doesn't have to stay paused.
     *
     * @param original the wrapped unpause
     */
    @WrapMethod(method = "unpause")
    private void prioritizedmusicplayer$preventCustomUnpause(Operation<Void> original) {
        Channel self = (Channel) (Object) this;

        if (!ClientMusicHandler.shouldStayPaused(self)) {
            original.call();
        }
    }
}
