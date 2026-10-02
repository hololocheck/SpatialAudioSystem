package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioHashes;
import com.spatialaudiosystem.audio.AudioStorage;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: metadata-only playback start signal.
 * The client answers with {@link AudioCacheAnswerPayload}; the audio follows via
 * {@link ClientAudioChunkPayload} chunks only when it answers that it does not keep it.
 */
public record ClientPlayAudioPayload(
        BlockPos pos,
        long playbackId,
        int totalSize,
        String format,
        BlockPos rangePos1,
        BlockPos rangePos2,
        boolean attenuationMode,
        int[] attenuationRanges,
        boolean loop,
        /**
         * How far into the sound this listener should begin, in milliseconds.
         *
         * <p>Zero for the players present when it started. A listener who arrives later gets the
         * elapsed time, so they hear the point the sound has already reached instead of starting
         * it again from the top and being out of step with everyone else.
         */
        int startOffsetMillis,
        /**
         * Whether this sound is one every listener should hear at the same moment.
         *
         * <p>True for anything placed in the world; false for the recording screen's preview,
         * which is a check on the medium in your hand and always starts at the top.
         *
         * <p>It decides whether the client adds its own transfer time to the offset. Without
         * that the offset is stale by however long the download took -- measured on a live
         * server on 2026-08-30 as ten to fifteen seconds for a player who had just joined, who
         * is downloading terrain at the same time.
         */
        boolean synchronised,
        /**
         * SHA-256 of the audio that follows (notes/CLIENT_AUDIO_CACHE.md). A client that already
         * keeps these bytes answers so and plays them without a transfer; the server sends the
         * audio only to a client that answers that it needs it.
         */
        byte[] contentHash,
        /**
         * When this packet was decoded, by this client's clock. Not on the wire.
         *
         * <p>Stamped in {@link #read}, on the network thread, as the packet is decoded. The session
         * that counts from it is prepared on the main thread, which for a player who has just joined
         * stalls for seconds while terrain loads. Measured on a live server on 2026-09-02, when the
         * handler itself still ran on the main thread: it ran 6.7 s after the packet had arrived, so
         * the transfer correction began counting 6.7 s late and the listener started that far behind
         * everyone else -- the "about seven seconds" the test reported. Since CLIENT_AUDIO_CACHE.md
         * the handler runs on the network thread; the stamp stays in read, the earliest point.
         */
        long receivedAtMillis) implements CustomPacketPayload {

    /** One value per face. Sizing an array from the wire without this is an allocation
     *  a peer chooses for us. */
    private static final int RANGE_COUNT = 6;

    /**
     * The largest offset a listener can be started at: seven days of ticks.
     *
     * <p>Not a musical limit -- an endless ambience really can be days old, so an offset far
     * beyond the audio's length is ordinary and correct. The client's cost does not scale with
     * it: after one pass the remaining offset is folded modulo that pass's real length, so the
     * discard is bounded by the audio, not by this number. The bound exists so that the value
     * driving that loop comes from this mod rather than from a peer.
     */
    public static final int MAX_START_OFFSET_MILLIS = 7 * 24 * 60 * 60 * 1000;

    /** Refused at construction, so no call site can announce a sound under no name or a short one. */
    public ClientPlayAudioPayload {
        if (contentHash == null || contentHash.length != AudioHashes.LENGTH) {
            throw new IllegalArgumentException("the content hash is " + AudioHashes.LENGTH + " bytes, got "
                    + (contentHash == null ? "none" : contentHash.length));
        }
    }

    public static final CustomPacketPayload.Type<ClientPlayAudioPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "client_play_audio"));

    public static final StreamCodec<FriendlyByteBuf, ClientPlayAudioPayload> STREAM_CODEC =
            StreamCodec.of(ClientPlayAudioPayload::write, ClientPlayAudioPayload::read);

    private static void write(FriendlyByteBuf buf, ClientPlayAudioPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeLong(p.playbackId);
        buf.writeInt(p.totalSize);
        buf.writeUtf(p.format);
        buf.writeBoolean(p.rangePos1 != null);
        if (p.rangePos1 != null) {
            buf.writeBlockPos(p.rangePos1);
            buf.writeBlockPos(p.rangePos2);
        }
        buf.writeBoolean(p.attenuationMode);
        buf.writeVarInt(p.attenuationRanges.length);
        for (int r : p.attenuationRanges) buf.writeVarInt(r);
        buf.writeBoolean(p.loop);
        buf.writeVarInt(p.startOffsetMillis);
        buf.writeBoolean(p.synchronised);
        buf.writeBytes(p.contentHash);
    }

    private static ClientPlayAudioPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        long playbackId = buf.readLong();
        int totalSize = buf.readInt();
        String format = buf.readUtf();
        boolean hasRange = buf.readBoolean();
        BlockPos rangePos1 = hasRange ? buf.readBlockPos() : null;
        BlockPos rangePos2 = hasRange ? buf.readBlockPos() : null;
        boolean attenuationMode = buf.readBoolean();
        int len = buf.readVarInt();
        if (len < 0 || len > RANGE_COUNT) {
            throw new DecoderException("Invalid attenuation range count: " + len);
        }
        if (totalSize <= 0 || totalSize > AudioStorage.MAX_AUDIO_SIZE) {
            throw new DecoderException("Invalid audio size: " + totalSize);
        }
        int[] attenuationRanges = new int[len];
        for (int i = 0; i < len; i++) attenuationRanges[i] = buf.readVarInt();
        boolean loop = buf.readBoolean();
        // Bounded at decode like every other field here: the offset sizes a read-and-discard
        // loop on the audio thread, so a peer must not be able to name an arbitrary one.
        int startOffsetMillis = buf.readVarInt();
        if (startOffsetMillis < 0 || startOffsetMillis > MAX_START_OFFSET_MILLIS) {
            throw new DecoderException("Invalid start offset: " + startOffsetMillis);
        }
        boolean synchronised = buf.readBoolean();
        byte[] contentHash = new byte[AudioHashes.LENGTH];
        buf.readBytes(contentHash);
        // The arrival stamp. This runs on the network thread, which keeps reading while the
        // main thread is busy; everything after it does not.
        return new ClientPlayAudioPayload(pos, playbackId, totalSize, format, rangePos1, rangePos2,
                attenuationMode, attenuationRanges, loop, startOffsetMillis, synchronised, contentHash,
                System.currentTimeMillis());
    }

    /**
     * ON THE NETWORK THREAD -- {@link ModNetworking} registers this payload so. The answer goes back
     * before anything waits on the main thread, which for a player who has just joined is seconds
     * behind (measured 6.7 s on 2026-09-02): answered from there, a player without the sound would
     * hear nothing for that stall and then for the whole transfer.
     */
    public static void handle(ClientPlayAudioPayload payload, IPayloadContext context) {
        boolean have = com.spatialaudiosystem.client.ClientAudioCacheHook.has(payload.contentHash, payload.totalSize);
        // Queued before anything can answer it: the chunks a "need" brings back, and the kept bytes
        // a "have" reads, both reach the main thread through this same queue, so neither can
        // arrive before the session they fill.
        context.enqueueWork(() ->
                ClientAudioChunkPayload.prepareSession(payload.pos, payload.playbackId,
                        payload.totalSize, payload.format,
                        payload.rangePos1, payload.rangePos2,
                        payload.attenuationMode, payload.attenuationRanges, payload.loop,
                        payload.startOffsetMillis, payload.synchronised, payload.receivedAtMillis,
                        payload.contentHash));
        context.reply(new AudioCacheAnswerPayload(payload.pos, payload.playbackId, have));
        if (have) com.spatialaudiosystem.client.ClientAudioCacheHook.fill(payload.pos, payload.playbackId,
                payload.contentHash);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
