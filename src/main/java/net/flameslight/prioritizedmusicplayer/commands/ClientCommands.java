package net.flameslight.prioritizedmusicplayer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.flameslight.prioritizedmusicplayer.ClientMusicHandler;
import net.flameslight.prioritizedmusicplayer.PrioritizedMusicPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * PMP's client commands. They run on the player's own client only, in singleplayer and on servers, and need no
 * permission.
 * <p>
 * /pmp nowplaying: prints every sound playing on the client, with its prioritization status.
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = PrioritizedMusicPlayer.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientCommands {
    /**
     * Registers PMP's client commands.
     *
     * @param event Forge's client command registration event
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    /**
     * Registers /pmp nowplaying.
     *
     * @param dispatcher the client command dispatcher
     */
    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pmp")
                .then(Commands.literal("nowplaying")
                        .executes(ClientCommands::nowPlaying)));
    }

    /**
     * Prints every sound playing on the client with its prioritization status. Runs on the client main thread.
     *
     * @param context the command context
     * @return the number of playing sounds
     */
    private static int nowPlaying(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ClientMusicHandler.NowPlaying nowPlaying = ClientMusicHandler.getNowPlaying();

        if (nowPlaying.soundCount() == 0) {
            source.sendSuccess(() -> Component.literal("PMP now playing: no sounds."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("PMP now playing (" + nowPlaying.soundCount() + " sounds):"), false);

        for (Component line : nowPlaying.lines()) {
            source.sendSuccess(() -> line, false);
        }

        return nowPlaying.soundCount();
    }
}
