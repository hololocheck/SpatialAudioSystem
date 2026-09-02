package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.server.ServerInteractionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: begin a chunked audio upload.
 * Tells the server how many bytes to expect and resets the reassembly buffer.
 */
public record AudioUploadStartPayload(BlockPos pos, String fileName, String format, int totalSize, int chunkCount)
        implements CustomPacketPayload {

    public static final Type<AudioUploadStartPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "audio_upload_start"));

    public static final StreamCodec<FriendlyByteBuf, AudioUploadStartPayload> STREAM_CODEC =
            StreamCodec.of(AudioUploadStartPayload::write, AudioUploadStartPayload::read);

    private static void write(FriendlyByteBuf buf, AudioUploadStartPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeUtf(p.fileName);
        buf.writeUtf(p.format);
        buf.writeInt(p.totalSize);
        buf.writeInt(p.chunkCount);
    }

    private static AudioUploadStartPayload read(FriendlyByteBuf buf) {
        return new AudioUploadStartPayload(
                buf.readBlockPos(), buf.readUtf(), buf.readUtf(), buf.readInt(), buf.readInt());
    }

    public static void handle(AudioUploadStartPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            if (payload.totalSize > AudioUploadChunkPayload.MAX_TOTAL_SIZE) {
                // The message a client can actually provoke. Its twin in AudioStorage was
                // translated first and is unreachable -- both callers bound the size before
                // asking -- so translating that one alone changed nothing a player sees.
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                                "message.spatialaudiosystem.too_large",
                                payload.totalSize / 1024 / 1024,
                                AudioUploadChunkPayload.MAX_TOTAL_SIZE / 1024 / 1024)
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }
            // An upload targets the device whose screen the sender has open, so a client
            // cannot stage bytes onto someone else's recorder.
            if (ServerInteractionGuard.recordingDevice(player, payload.pos) == null) return;

            if (!AudioUploadChunkPayload.startUpload(player.getUUID(), payload.pos,
                    payload.fileName, payload.format, payload.totalSize, payload.chunkCount)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component
                        .translatable("message.spatialaudiosystem.upload_rejected")
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
