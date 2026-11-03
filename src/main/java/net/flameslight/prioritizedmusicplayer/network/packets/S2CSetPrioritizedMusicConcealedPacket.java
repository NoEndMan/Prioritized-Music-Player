package net.flameslight.prioritizedmusicplayer.network.packets;

import net.flameslight.prioritizedmusicplayer.network.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server to client: hides or reveals a playing prioritized sound (CommonRoutes.setPrioritizedMusicConcealed on the
 * client).
 */
public class S2CSetPrioritizedMusicConcealedPacket {
    private final String sound;
    private final boolean concealed;

    /**
     * Creates the message.
     *
     * @param sound     the sound name, without a namespace
     * @param concealed true to hide, false to reveal
     */
    public S2CSetPrioritizedMusicConcealedPacket(String sound, boolean concealed) {
        this.sound = sound;
        this.concealed = concealed;
    }

    /**
     * Writes the message.
     *
     * @param msg the message
     * @param buf the buffer to write to
     */
    public static void encode(S2CSetPrioritizedMusicConcealedPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sound);
        buf.writeBoolean(msg.concealed);
    }

    /**
     * Reads the message.
     *
     * @param buf the buffer to read from
     * @return the read message
     */
    public static S2CSetPrioritizedMusicConcealedPacket decode(FriendlyByteBuf buf) {
        return new S2CSetPrioritizedMusicConcealedPacket(buf.readUtf(), buf.readBoolean());
    }

    /**
     * Hides or reveals the sound on the client main thread.
     *
     * @param msg the message
     * @param ctx the network context
     */
    public static void handle(S2CSetPrioritizedMusicConcealedPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                        ClientPacketHandlers.setPrioritizedMusicConcealed(msg.sound, msg.concealed)));
        ctx.get().setPacketHandled(true);
    }
}
