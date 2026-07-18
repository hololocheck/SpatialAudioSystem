package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.PlaybackScheduler;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

    public static final CustomPacketPayload.Type<PlaylistCommandPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "playlist_cmd"));

    public static final StreamCodec<FriendlyByteBuf, PlaylistCommandPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeBlockPos(p.pos);
                        buf.writeInt(p.op);
                        buf.writeInt(p.a1);
                        buf.writeInt(p.a2);
                    },
                    buf -> new PlaylistCommandPayload(buf.readBlockPos(), buf.readInt(), buf.readInt(), buf.readInt()));

    public static void handle(PlaylistCommandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.level() instanceof ServerLevel level)) return;
            if (player.distanceToSqr(payload.pos.getX() + 0.5, payload.pos.getY() + 0.5,
                    payload.pos.getZ() + 0.5) > 64) return;
            if (!(level.getBlockEntity(payload.pos) instanceof PlaybackDeviceBlockEntity be)) return;
            if (!be.canAccess(player)) return;

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
                default -> { }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
