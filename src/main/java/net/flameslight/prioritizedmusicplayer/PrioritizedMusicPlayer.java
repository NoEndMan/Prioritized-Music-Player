package net.flameslight.prioritizedmusicplayer;

import com.mojang.logging.LogUtils;
import net.flameslight.prioritizedmusicplayer.config.ClientConfig;
import net.flameslight.prioritizedmusicplayer.network.PMPNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
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
 * Client mod, optional on either side, that makes configured music sounds block the playing of other sounds on
 * the client: the highest priority sound is the current prioritized playing, and while it plays other sounds of the
 * music, ambient, weather, records and hostile categories are muted or weakened. Configured sounds can also be paused until a
 * conditional sound finishes playing. With PMP on the server too, mods there can control the music of the clients that
 * have PMP through ServerRoutes.
 */
@Mod(PrioritizedMusicPlayer.MOD_ID)
public class PrioritizedMusicPlayer
{
    /** The mod id. */
    public static final String MOD_ID = "prioritizedmusicplayer";
    /** The mod's logger. */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Registers the network channel and the server list display test on both sides. On the client, also creates the
     * config folder and registers the client config.
     * <p>
     * The display test reports PMP's protocol version. On the client, a server without PMP (null version) or with the
     * same protocol counts as compatible, so the server list shows a red X exactly when joining would be refused.
     */
    @SuppressWarnings("removal")
    public PrioritizedMusicPlayer() {
        PMPNetwork.register();
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> PMPNetwork.PROTOCOL_VERSION,
                        (remoteVersion, isFromServer) -> remoteVersion == null || PMPNetwork.PROTOCOL_VERSION.equals(remoteVersion)));

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
