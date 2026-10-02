package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioOffers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: whether this client already holds the bytes a {@link ClientPlayAudioPayload}
 * announced (notes/CLIENT_AUDIO_CACHE.md §2). {@code have = false} asks for the audio.
 *
 * <p>Sent from the client's network thread the moment the announcement arrives, so a player whose
 * main thread is still loading terrain does not hold up its own transfer.
 */
public record AudioCacheAnswerPayload(BlockPos pos, long playbackId, boolean have)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AudioCacheAnswerPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "audio_cache_answer"));

    public static final StreamCodec<FriendlyByteBuf, AudioCacheAnswerPayload> STREAM_CODEC =
            StreamCodec.of(AudioCacheAnswerPayload::write, AudioCacheAnswerPayload::read);

    private static void write(FriendlyByteBuf buf, AudioCacheAnswerPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeLong(p.playbackId);
        buf.writeBoolean(p.have);
    }

    private static AudioCacheAnswerPayload read(FriendlyByteBuf buf) {
        return new AudioCacheAnswerPayload(buf.readBlockPos(), buf.readLong(), buf.readBoolean());
    }

    public static void handle(AudioCacheAnswerPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                AudioOffers.answer(player, payload.pos, payload.playbackId, payload.have);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
