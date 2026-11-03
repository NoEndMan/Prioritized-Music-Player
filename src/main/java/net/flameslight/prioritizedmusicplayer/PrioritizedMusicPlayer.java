package net.flameslight.prioritizedmusicplayer;

import com.mojang.logging.LogUtils;
import net.flameslight.prioritizedmusicplayer.config.ClientConfig;
import net.flameslight.prioritizedmusicplayer.network.PMPNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod, required on both server and client, that makes configured music sounds block the playing of other sounds on
 * the client: the highest priority sound is the current prioritized playing, and while it plays other sounds of the
 * music, ambient, weather, records and hostile categories are muted or weakened. Configured sounds can also be paused until a
 * conditional sound finishes playing. Mods on the server can configure clients and control their music through
 * ServerRoutes.
 */
@Mod(PrioritizedMusicPlayer.MOD_ID)
public class PrioritizedMusicPlayer
{
    /** The mod id. */
    public static final String MOD_ID = "prioritizedmusicplayer";
    /** The mod's logger. */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Registers the network channel on both sides. On the client, also creates the config folder and registers the
     * client config.
     */
    @SuppressWarnings("removal")
    public PrioritizedMusicPlayer() {
        PMPNetwork.register();

        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }

        /*
            NightConfig writes client-config.toml into this folder. Create it first so the very first
            launch can't race the folder into existence.
         */
        Path configFolder = FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
        try {
            Files.createDirectories(configFolder);
        } catch (IOException e) {
            LOGGER.error("Could not create the Prioritized Music Player config folder {}", configFolder, e);
        }

        FMLJavaModLoadingContext.get().registerConfig(
                ModConfig.Type.CLIENT, ClientConfig.CLIENT_SPEC, MOD_ID + "/client-config.toml");
    }
}
