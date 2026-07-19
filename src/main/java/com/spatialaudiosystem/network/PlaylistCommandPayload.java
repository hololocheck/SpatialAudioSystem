package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.PlaybackScheduler;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.server.ServerInteractionGuard;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S: Playback Device playlist commands — continuous play, stop, per-entry test, adjusting an
 * entry's play count, and the schedule editor CRUD (add / remove / reorder entries).
 */
public record PlaylistCommandPayload(BlockPos pos, int op, int a1, int a2) implements CustomPacketPayload {

    public static final int OP_PLAY_ALL = 0;
    public static final int OP_STOP = 1;
    public static final int OP_TEST = 2;              // a1 = entry index
    public static final int OP_ADJUST_PLAYCOUNT = 3;  // a1 = entry index, a2 = delta
    public static final int OP_ADD_ENTRY = 4;         // append a new empty entry
    public static final int OP_REMOVE_ENTRY = 5;      // a1 = entry index (media returned to player)
    public static final int OP_REORDER = 6;           // a1 = from, a2 = to
    public static final int OP_TOGGLE_MODE = 7;       // flip schedule mode (bars/frees the media slot)

    public static final CustomPacketPayload.Type<PlaylistCommandPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "playlist_cmd"));

    public static final StreamCodec<FriendlyByteBuf, PlaylistCommandPayload> STREAM_CODEC =
            StreamCodec.of(PlaylistCommandPayload::write, PlaylistCommandPayload::read);

    private static void write(FriendlyByteBuf buf, PlaylistCommandPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeInt(payload.op);
        buf.writeInt(payload.a1);
        buf.writeInt(payload.a2);
    }

    /**
     * Bounds live here so no handler downstream has to re-derive them: {@code a1} is always an
     * entry index, {@code a2} is either a target index or a play-count delta. Decoding an
     * out-of-range value is refused outright — an unchecked index reached the playlist handler
     * directly and threw out of the server thread.
     */
    private static PlaylistCommandPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int op = buf.readInt();
        int a1 = buf.readInt();
        int a2 = buf.readInt();
        if (op < OP_PLAY_ALL || op > OP_TOGGLE_MODE) {
            throw new DecoderException("Invalid playlist op: " + op);
        }
        if (a1 < 0 || a1 >= PlaybackDeviceBlockEntity.MAX_ENTRIES) {
            throw new DecoderException("Playlist entry index out of range: " + a1);
        }
        if (a2 < -PlaybackDeviceBlockEntity.MAX_PLAY_COUNT
                || a2 > PlaybackDeviceBlockEntity.MAX_ENTRIES) {
            throw new DecoderException("Playlist argument out of range: " + a2);
        }
        return new PlaylistCommandPayload(pos, op, a1, a2);
    }

    public static void handle(PlaylistCommandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            // Same gate as every other device packet: the sender must actually have this
            // device's screen open, and still be allowed to use it.
            PlaybackDeviceBlockEntity be = ServerInteractionGuard.playbackDevice(player, payload.pos);
            if (be == null) return;
            if (!(be.getLevel() instanceof ServerLevel level)) return;

            switch (payload.op) {
                case OP_PLAY_ALL -> PlaybackScheduler.playAll(level, payload.pos);
                case OP_STOP -> PlaybackScheduler.stop(level, payload.pos);
                case OP_TEST -> PlaybackScheduler.testEntry(level, payload.pos, payload.a1);
                case OP_ADJUST_PLAYCOUNT -> be.setPlayCount(payload.a1, be.getPlayCount(payload.a1) + payload.a2);
                case OP_ADD_ENTRY -> be.addEntry();
                case OP_REMOVE_ENTRY -> {
                    ItemStack media = be.removeEntry(payload.a1);
                    if (!media.isEmpty() && !player.getInventory().add(media)) {
                        player.drop(media, false);
                    }
                }
                case OP_REORDER -> be.swapEntries(payload.a1, payload.a2);
                case OP_TOGGLE_MODE -> be.toggleScheduleMode();
                default -> { }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
