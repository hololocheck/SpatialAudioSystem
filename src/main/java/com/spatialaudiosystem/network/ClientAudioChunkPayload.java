package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server → Client: one chunk of audio data for playback.
 * Chunks are reassembled client-side; playback starts when all chunks arrive.
 */
public record ClientAudioChunkPayload(BlockPos pos, long playbackId, int chunkIndex, int chunkCount, byte[] data)
        implements CustomPacketPayload {

    private static final int CHUNK_SIZE = 500 * 1024; // 500 KB per chunk

    public static final Type<ClientAudioChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "client_audio_chunk"));

    public static final StreamCodec<FriendlyByteBuf, ClientAudioChunkPayload> STREAM_CODEC =
            StreamCodec.of(ClientAudioChunkPayload::write, ClientAudioChunkPayload::read);

    private static void write(FriendlyByteBuf buf, ClientAudioChunkPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeLong(p.playbackId);
        buf.writeInt(p.chunkIndex);
        buf.writeInt(p.chunkCount);
        buf.writeByteArray(p.data);
    }

    private static ClientAudioChunkPayload read(FriendlyByteBuf buf) {
        return new ClientAudioChunkPayload(
                buf.readBlockPos(), buf.readLong(), buf.readInt(), buf.readInt(),
                buf.readByteArray(CHUNK_SIZE + 1024));
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
    public static void prepareSession(BlockPos pos, long playbackId, int totalSize, String format,
                                       BlockPos rangePos1, BlockPos rangePos2,
                                       boolean attenuationMode, int[] attenuationRanges) {
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(e -> now - e.getValue().createdAt > SESSION_TIMEOUT_MS);

        activeSessions.put(pos, new DownloadSession(
                playbackId, totalSize, format, rangePos1, rangePos2, attenuationMode, attenuationRanges));
    }

    public static void handle(ClientAudioChunkPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            DownloadSession session = activeSessions.get(payload.pos);
            if (session == null) {
                SpatialAudioSystem.LOGGER.warn("Received audio chunk for {} without active session", payload.pos);
                return;
            }
            // Chunks of a sound that has already been replaced must not be written into
            // the buffer of the one that replaced it.
            if (session.playbackId != payload.playbackId) return;

            if (!session.accept(payload.chunkIndex, payload.data)) {
                activeSessions.remove(payload.pos);
                SpatialAudioSystem.LOGGER.warn("Discarding audio download for {}: chunk {} does not fit the transfer",
                        payload.pos, payload.chunkIndex);
                return;
            }

            if (!session.isComplete()) return;
            activeSessions.remove(payload.pos);
            AudioManager.getInstance().playAudio(
                    context.player().level(), payload.pos, session.playbackId, session.buffer, session.format,
                    session.rangePos1, session.rangePos2,
                    session.attenuationMode, session.attenuationRanges);
        });
    }

    /** Send audio data to a player in chunks. */
    public static void sendChunked(net.minecraft.server.level.ServerPlayer player,
                                    BlockPos pos, long playbackId, byte[] audioData) {
        int chunkCount = (audioData.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int i = 0; i < chunkCount; i++) {
            int offset = i * CHUNK_SIZE;
            int len = Math.min(CHUNK_SIZE, audioData.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(audioData, offset, chunk, 0, len);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    player, new ClientAudioChunkPayload(pos, playbackId, i, chunkCount, chunk));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    private static class DownloadSession {
        final long playbackId;
        final byte[] buffer;
        final String format;
        final BlockPos rangePos1, rangePos2;
        final boolean attenuationMode;
        final int[] attenuationRanges;
        final long createdAt;
        /** Which indices have arrived. Counting receipts instead would let a repeated
         *  chunk hand a half-zero buffer to the decoder. */
        final BitSet received;

        DownloadSession(long playbackId, int totalSize, String format, BlockPos rangePos1, BlockPos rangePos2,
                        boolean attenuationMode, int[] attenuationRanges) {
            this.playbackId = playbackId;
            this.buffer = new byte[totalSize];
            this.format = format;
            this.rangePos1 = rangePos1;
            this.rangePos2 = rangePos2;
            this.attenuationMode = attenuationMode;
            this.attenuationRanges = attenuationRanges;
            this.createdAt = System.currentTimeMillis();
            this.received = new BitSet(chunkCountFor(totalSize));
        }

        private int expectedLength(int index) {
            return Math.min(CHUNK_SIZE, buffer.length - index * CHUNK_SIZE);
        }

        boolean accept(int index, byte[] data) {
            if (index < 0 || index >= chunkCountFor(buffer.length)) return false;
            if (received.get(index)) return false;
            if (data.length != expectedLength(index)) return false;

            System.arraycopy(data, 0, buffer, index * CHUNK_SIZE, data.length);
            received.set(index);
            return true;
        }

        boolean isComplete() {
            return received.cardinality() == chunkCountFor(buffer.length);
        }
    }

    private static int chunkCountFor(int totalSize) {
        return (totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE;
    }
}
