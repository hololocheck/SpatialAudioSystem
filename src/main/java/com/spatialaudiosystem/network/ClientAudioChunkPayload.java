package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Server → Client: one chunk of audio data for playback.
 * Chunks are reassembled client-side; playback starts when all chunks arrive.
 */
public record ClientAudioChunkPayload(BlockPos pos, int chunkIndex, int chunkCount, byte[] data)
        implements CustomPacketPayload {

    private static final int CHUNK_SIZE = 500 * 1024; // 500 KB per chunk

    public static final Type<ClientAudioChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "client_audio_chunk"));

    public static final StreamCodec<FriendlyByteBuf, ClientAudioChunkPayload> STREAM_CODEC =
            StreamCodec.of(ClientAudioChunkPayload::write, ClientAudioChunkPayload::read);

    private static void write(FriendlyByteBuf buf, ClientAudioChunkPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeInt(p.chunkIndex);
        buf.writeInt(p.chunkCount);
        buf.writeByteArray(p.data);
    }

    private static ClientAudioChunkPayload read(FriendlyByteBuf buf) {
        return new ClientAudioChunkPayload(
                buf.readBlockPos(), buf.readInt(), buf.readInt(), buf.readByteArray(CHUNK_SIZE + 1024));
    }

    // ---- Client-side reassembly ----

    private static final ConcurrentHashMap<BlockPos, DownloadSession> activeSessions = new ConcurrentHashMap<>();
    /** Session timeout: 30 seconds. */
    private static final long SESSION_TIMEOUT_MS = 30_000;

    /** Clear all in-flight chunk sessions (called on world exit / disconnect). */
    public static void clearAllSessions() {
        activeSessions.clear();
    }

    /** Called when ClientPlayAudioPayload (metadata) arrives — prepare the reassembly buffer. */
    public static void prepareSession(BlockPos pos, int totalSize, String format,
                                       BlockPos rangePos1, BlockPos rangePos2,
                                       boolean attenuationMode, int[] attenuationRanges) {
        // Clean up any expired sessions first
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(e -> now - e.getValue().createdAt > SESSION_TIMEOUT_MS);

        activeSessions.put(pos, new DownloadSession(
                totalSize, format, rangePos1, rangePos2, attenuationMode, attenuationRanges));
    }

    public static void handle(ClientAudioChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            DownloadSession session = activeSessions.get(payload.pos);
            if (session == null) {
                SpatialAudioSystem.LOGGER.warn("Received audio chunk for {} without active session", payload.pos);
                return;
            }

            int offset = payload.chunkIndex * CHUNK_SIZE;
            int len = Math.min(payload.data.length, session.buffer.length - offset);
            if (len > 0) {
                System.arraycopy(payload.data, 0, session.buffer, offset, len);
            }
            session.receivedChunks++;

            if (session.receivedChunks >= payload.chunkCount) {
                activeSessions.remove(payload.pos);
                // All chunks received — start playback
                AudioManager.getInstance().playAudio(
                        context.player().level(), payload.pos, session.buffer, session.format,
                        session.rangePos1, session.rangePos2,
                        session.attenuationMode, session.attenuationRanges);
            }
        });
    }

    /** Send audio data to a player in chunks. */
    public static void sendChunked(net.minecraft.server.level.ServerPlayer player,
                                    BlockPos pos, byte[] audioData) {
        int chunkCount = (audioData.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int i = 0; i < chunkCount; i++) {
            int offset = i * CHUNK_SIZE;
            int len = Math.min(CHUNK_SIZE, audioData.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(audioData, offset, chunk, 0, len);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player, new ClientAudioChunkPayload(pos, i, chunkCount, chunk));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static class DownloadSession {
        final byte[] buffer;
        final String format;
        final BlockPos rangePos1, rangePos2;
        final boolean attenuationMode;
        final int[] attenuationRanges;
        final long createdAt;
        int receivedChunks;

        DownloadSession(int totalSize, String format, BlockPos rangePos1, BlockPos rangePos2,
                        boolean attenuationMode, int[] attenuationRanges) {
            this.buffer = new byte[totalSize];
            this.format = format;
            this.rangePos1 = rangePos1;
            this.rangePos2 = rangePos2;
            this.attenuationMode = attenuationMode;
            this.attenuationRanges = attenuationRanges;
            this.createdAt = System.currentTimeMillis();
            this.receivedChunks = 0;
        }
    }
}
