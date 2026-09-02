package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import com.spatialaudiosystem.server.ServerInteractionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.BitSet;
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

    /** Number of chunks a transfer of {@code totalSize} bytes must be split into. */
    static int expectedChunkCount(int totalSize) {
        return (totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE;
    }

    /**
     * Opens a reassembly session, or returns false if the announced framing is not one
     * this server would ever produce.
     */
    static boolean startUpload(UUID playerId, BlockPos pos, String fileName, String format, int totalSize, int chunkCount) {
        if (totalSize <= 0 || totalSize > MAX_TOTAL_SIZE) return false;
        if (chunkCount != expectedChunkCount(totalSize)) return false;

        sweepExpired();
        activeSessions.put(playerId, new UploadSession(pos, fileName, format, totalSize, chunkCount));
        return true;
    }

    /** Drops sessions past their deadline. Called on upload start and from the server tick. */
    public static void sweepExpired() {
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(e -> now - e.getValue().createdAt > SESSION_TIMEOUT_MS);
    }

    /** Drops a disconnecting player's half-finished transfer. */
    public static void cancelUpload(UUID playerId) {
        activeSessions.remove(playerId);
    }

    /** Drops every in-flight transfer. The map is static, so it outlives a single world. */
    public static void clearUploads() {
        activeSessions.clear();
    }

    public static void handle(AudioUploadChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            UploadSession session = activeSessions.get(player.getUUID());
            if (session == null) {
                SpatialAudioSystem.LOGGER.warn("Player {} sent audio chunk without active session", player.getName().getString());
                return;
            }

            if (!session.accept(payload.chunkIndex, payload.data)) {
                // Out of range, wrong length, or a repeat. Any of these means the bytes no
                // longer describe the announced file, so the transfer cannot be finished.
                activeSessions.remove(player.getUUID());
                SpatialAudioSystem.LOGGER.warn(
                        "Player {} sent an invalid audio chunk ({} bytes at index {}); upload dropped",
                        player.getName().getString(), payload.data.length, payload.chunkIndex);
                player.sendSystemMessage(Component.translatable("message.spatialaudiosystem.upload_failed")
                        .withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }

            if (!session.isComplete()) return;
            activeSessions.remove(player.getUUID());

            net.minecraft.network.chat.Component sizeError = AudioStorage.validateSize(session.buffer);
            if (sizeError != null) {
                player.sendSystemMessage(sizeError.copy().withStyle(net.minecraft.ChatFormatting.RED));
                return;
            }

            RecordingDeviceBlockEntity recordingDevice =
                    ServerInteractionGuard.recordingDevice(player, session.pos);
            if (recordingDevice != null) {
                recordingDevice.setPendingAudio(session.buffer, session.fileName, session.format);
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
        /** Which indices have arrived. Counting receipts instead would let a repeated
         *  chunk complete a transfer whose gaps are still zero-filled. */
        final BitSet received;

        final long createdAt;

        UploadSession(BlockPos pos, String fileName, String format, int totalSize, int chunkCount) {
            this.pos = pos;
            this.fileName = fileName;
            this.format = format;
            this.buffer = new byte[totalSize];
            this.chunkCount = chunkCount;
            this.received = new BitSet(chunkCount);
            this.createdAt = System.currentTimeMillis();
        }

        /** Length the chunk at {@code index} must have for this transfer. */
        private int expectedLength(int index) {
            return Math.min(CHUNK_SIZE, buffer.length - index * CHUNK_SIZE);
        }

        /** Stores a chunk, or returns false if it does not fit the announced transfer. */
        boolean accept(int index, byte[] data) {
            if (index < 0 || index >= chunkCount) return false;
            if (received.get(index)) return false;
            if (data.length != expectedLength(index)) return false;

            System.arraycopy(data, 0, buffer, index * CHUNK_SIZE, data.length);
            received.set(index);
            return true;
        }

        boolean isComplete() {
            return received.cardinality() == chunkCount;
        }
    }
}
