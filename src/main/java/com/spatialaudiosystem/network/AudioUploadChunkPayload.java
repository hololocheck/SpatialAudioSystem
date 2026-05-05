package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client → Server: one chunk of an audio upload.
 * Chunks are reassembled server-side; when the last chunk arrives the audio is saved.
 */
public record AudioUploadChunkPayload(int chunkIndex, byte[] data)
        implements CustomPacketPayload {

    /** Max size per chunk (~500 KB, well under NeoForge's ~1 MB packet limit). */
    public static final int CHUNK_SIZE = 500 * 1024;
    /** Max total audio size (10 MB). */
    public static final int MAX_TOTAL_SIZE = 10 * 1024 * 1024;

    public static final Type<AudioUploadChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "audio_upload_chunk"));

    public static final StreamCodec<FriendlyByteBuf, AudioUploadChunkPayload> STREAM_CODEC =
            StreamCodec.of(AudioUploadChunkPayload::write, AudioUploadChunkPayload::read);

    private static void write(FriendlyByteBuf buf, AudioUploadChunkPayload p) {
        buf.writeInt(p.chunkIndex);
        buf.writeByteArray(p.data);
    }

    private static AudioUploadChunkPayload read(FriendlyByteBuf buf) {
        return new AudioUploadChunkPayload(buf.readInt(), buf.readByteArray(CHUNK_SIZE + 1024));
    }

    // ---- Server-side reassembly state ----

    private static final ConcurrentHashMap<UUID, UploadSession> activeSessions = new ConcurrentHashMap<>();
    /** Session timeout: 30 seconds. */
    private static final long SESSION_TIMEOUT_MS = 30_000;

    static void startUpload(UUID playerId, BlockPos pos, String fileName, String format, int totalSize, int chunkCount) {
        // Clean up any expired sessions first
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(e -> now - e.getValue().createdAt > SESSION_TIMEOUT_MS);

        activeSessions.put(playerId, new UploadSession(pos, fileName, format, totalSize, chunkCount));
    }

    public static void handle(AudioUploadChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            UploadSession session = activeSessions.get(player.getUUID());
            if (session == null) {
                SpatialAudioSystem.LOGGER.warn("Player {} sent audio chunk without active session", player.getName().getString());
                return;
            }

            // Copy chunk data into the reassembly buffer
            int offset = payload.chunkIndex * CHUNK_SIZE;
            int len = Math.min(payload.data.length, session.buffer.length - offset);
            if (len > 0) {
                System.arraycopy(payload.data, 0, session.buffer, offset, len);
            }
            session.receivedChunks++;

            // All chunks received — finalize
            if (session.receivedChunks >= session.chunkCount) {
                activeSessions.remove(player.getUUID());

                String sizeError = AudioStorage.validateSize(session.buffer);
                if (sizeError != null) {
                    player.sendSystemMessage(Component.literal("\u00a7c" + sizeError));
                    return;
                }

                BlockEntity entity = player.level().getBlockEntity(session.pos);
                if (entity instanceof RecordingDeviceBlockEntity recordingDevice) {
                    recordingDevice.setPendingAudio(session.buffer, session.fileName, session.format);
                }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ---- Session data ----

    private static class UploadSession {
        final BlockPos pos;
        final String fileName;
        final String format;
        final byte[] buffer;
        final int chunkCount;
        int receivedChunks;

        final long createdAt;

        UploadSession(BlockPos pos, String fileName, String format, int totalSize, int chunkCount) {
            this.pos = pos;
            this.fileName = fileName;
            this.format = format;
            this.buffer = new byte[totalSize];
            this.chunkCount = chunkCount;
            this.receivedChunks = 0;
            this.createdAt = System.currentTimeMillis();
        }
    }
}
