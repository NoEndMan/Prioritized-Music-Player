package net.flameslight.prioritizedmusicplayer.network.packets;

import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler.EntryList;
import net.flameslight.prioritizedmusicplayer.config.ModdedAddedEntriesHandler.ModdedEntry;
import net.flameslight.prioritizedmusicplayer.network.ClientPacketHandlers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client, on login: the entries mods on the server registered through ServerRegisterPrioritizationEvent,
 * to be written to the client config.
 */
public class S2CServerModEntriesPacket {
    private final List<ModdedEntry> entries;

    /**
     * Creates the message.
     *
     * @param entries the entries to send
     */
    public S2CServerModEntriesPacket(List<ModdedEntry> entries) {
        this.entries = entries;
    }

    /**
     * Writes the message: each entry's mod id, list and entry string.
     *
     * @param msg the message
     * @param buf the buffer to write to
     */
    public static void encode(S2CServerModEntriesPacket msg, FriendlyByteBuf buf) {
        buf.writeCollection(msg.entries, (out, entry) -> {
            out.writeUtf(entry.modId());
            out.writeEnum(entry.list());
            out.writeUtf(entry.entry());
        });
    }

    /**
     * Reads the message. The entries are validated again on the client before they are written.
     *
     * @param buf the buffer to read from
     * @return the read message
     */
    public static S2CServerModEntriesPacket decode(FriendlyByteBuf buf) {
        return new S2CServerModEntriesPacket(buf.readCollection(ArrayList::new,
                in -> new ModdedEntry(in.readUtf(), in.readEnum(EntryList.class), in.readUtf())));
    }

    /**
     * Writes the entries to the client config on the client main thread.
     *
     * @param msg the message
     * @param ctx the network context
     */
    public static void handle(S2CServerModEntriesPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandlers.applyServerModEntries(msg.entries)));
        ctx.get().setPacketHandled(true);
    }
}
