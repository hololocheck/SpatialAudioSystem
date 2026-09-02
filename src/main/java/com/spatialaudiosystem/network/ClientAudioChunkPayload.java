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
                                       boolean attenuationMode, int[] attenuationRanges,
                                       boolean loop, int startOffsetMillis, boolean synchronised) {
        long now = System.currentTimeMillis();
        activeSessions.entrySet().removeIf(e -> now - e.getValue().createdAt > SESSION_TIMEOUT_MS);

        activeSessions.put(pos, new DownloadSession(
                playbackId, totalSize, format, rangePos1, rangePos2, attenuationMode,
                attenuationRanges, loop, startOffsetMillis, synchronised));
    }

    /**
     * Changes the endless flag on a transfer that has not finished arriving.
     *
     * <p>The playing session and this one are separate holders of it, and a loop change can land
     * in the window between them. Ignored unless the id matches, so a change aimed at a sound
     * that has already been replaced cannot land on its successor.
     */
    public static void setLoop(BlockPos pos, long playbackId, boolean loop) {
        DownloadSession session = activeSessions.get(pos);
        if (session != null && session.playbackId == playbackId) session.loop = loop;
    }

    /**
     * Pretends the transfer at {@code pos} was announced {@code millisAgo} milliseconds ago.
     *
     * <p>A real download takes seconds; a test's takes none, so without this the difference
     * between "when it was announced" and "now" is unobservable -- and that difference is the
     * whole of the catch-up correction.
     */
    static void backdateForTest(BlockPos pos, long millisAgo) {
        DownloadSession session = activeSessions.get(pos);
        if (session != null) session.createdAt -= millisAgo;
    }

    /**
     * Feeds one chunk into the transfer at {@code pos}, exactly as {@link #handle} does.
     *
     * <p>Exists because {@code handle} needs an {@code IPayloadContext} and a live level, so the
     * accept-and-complete logic could not otherwise be driven. This is the same call handle
     * makes; what it cannot cover is handle's own guard clauses.
     */
    static boolean deliverForTest(BlockPos pos, long playbackId, int index, int count, byte[] data) {
        DownloadSession session = activeSessions.get(pos);
        if (session == null || session.playbackId != playbackId) return false;
        return session.accept(index, data);
    }

    /**
     * Everything a completed download hands to the player.
     *
     * <p>Extracted so the wiring can be checked. Until 2026-08-30 nothing exercised the path
     * from the play payload to {@code playAudio}: replacing the offset with a literal zero here
     * left every test green while no listener ever caught up, which is exactly what a live
     * test then reported. The transfer itself is driven for real by the test -- chunks and all
     * -- so this is the shape the player receives, not a restatement of it.
     */
    record Ready(long playbackId, byte[] audio, String format,
                 BlockPos rangePos1, BlockPos rangePos2,
                 boolean attenuationMode, int[] attenuationRanges,
                 boolean loop, int startOffsetMillis,
                 boolean synchronised, long announcedAtMillis) {}

    /** The completed download at {@code pos}, or null while it is still arriving. */
    static Ready readyFor(BlockPos pos) {
        DownloadSession s = activeSessions.get(pos);
        if (s == null || !s.isComplete()) return null;
        return new Ready(s.playbackId, s.buffer, s.format, s.rangePos1, s.rangePos2,
                s.attenuationMode, s.attenuationRanges, s.loop, s.startOffsetMillis,
                s.synchronised, s.createdAt);
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

            Ready ready = readyFor(payload.pos);
            if (ready == null) return;
            activeSessions.remove(payload.pos);
            AudioManager.getInstance().playAudio(
                    context.player().level(), payload.pos, ready.playbackId(), ready.audio(), ready.format(),
                    ready.rangePos1(), ready.rangePos2(),
                    ready.attenuationMode(), ready.attenuationRanges(), ready.loop(),
                    ready.startOffsetMillis(), ready.synchronised(), ready.announcedAtMillis());
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
        /** Restart at the top forever, instead of ending and reporting completion. */
        /**
         * Not final: the endless button can be turned off while this transfer is still arriving,
         * and a download can take seconds. Left stale, that client would start the sound with
         * the setting from before the button was pressed -- looping alone for ever if it was
         * turned off, or reporting a completion that stops everyone else if it was turned on.
         */
        volatile boolean loop;
        /** How far into the sound this listener starts; see ClientPlayAudioPayload. */
        final int startOffsetMillis;
        /** See ClientPlayAudioPayload#synchronised. */
        final boolean synchronised;
        /**
         * When the metadata arrived. Not final only so a test can move it: in a unit test the
         * whole transfer is instant, so a mutation that hands over "now" instead of this lands
         * inside the same millisecond window and no assertion can tell the two apart.
         */
        long createdAt;
        /** Which indices have arrived. Counting receipts instead would let a repeated
         *  chunk hand a half-zero buffer to the decoder. */
        final BitSet received;

        DownloadSession(long playbackId, int totalSize, String format, BlockPos rangePos1, BlockPos rangePos2,
                        boolean attenuationMode, int[] attenuationRanges, boolean loop,
                        int startOffsetMillis, boolean synchronised) {
            this.playbackId = playbackId;
            this.buffer = new byte[totalSize];
            this.format = format;
            this.rangePos1 = rangePos1;
            this.rangePos2 = rangePos2;
            this.attenuationMode = attenuationMode;
            this.attenuationRanges = attenuationRanges;
            this.loop = loop;
            this.startOffsetMillis = startOffsetMillis;
            this.synchronised = synchronised;
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
