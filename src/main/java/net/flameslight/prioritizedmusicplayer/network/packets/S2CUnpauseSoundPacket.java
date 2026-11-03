package net.flameslight.prioritizedmusicplayer.network.packets;

import net.flameslight.prioritizedmusicplayer.network.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: allows paused instances of a sound to resume (CommonRoutes.unpauseSound on the client).
 */
public class S2CUnpauseSoundPacket {
    private final String sound;

    /**
     * Creates the message.
     *
     * @param sound the sound name, without a namespace
     */
    public S2CUnpauseSoundPacket(String sound) {
        this.sound = sound;
    }

    /**
     * Writes the message.
     *
     * @param msg the message
     * @param buf the buffer to write to
     */
    public static void encode(S2CUnpauseSoundPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sound);
    }

    /**
     * Reads the message.
     *
     * @param buf the buffer to read from
     * @return the read message
     */
    public static S2CUnpauseSoundPacket decode(FriendlyByteBuf buf) {
        return new S2CUnpauseSoundPacket(buf.readUtf());
    }

    /**
     * Unpauses the sound on the client main thread.
     *
     * @param msg the message
     * @param ctx the network context
     */
    public static void handle(S2CUnpauseSoundPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.unpauseSound(msg.sound)));
        ctx.get().setPacketHandled(true);
    }
}
