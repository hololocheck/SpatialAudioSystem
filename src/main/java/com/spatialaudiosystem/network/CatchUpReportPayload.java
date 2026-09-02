package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * What one listener's client actually did with the catch-up offset.
 *
 * <p>The server logs the offset it sends; whether the client acts on it was only visible in that
 * client's own log. On a real test that log is on somebody else's machine, so "the offset is
 * ignored" and "the offset never arrived" could not be told apart from the one log reachable
 * from here -- which is how two rounds went by on a symptom that a single number would have
 * settled. This closes the loop: the server can now see both halves.
 *
 * <p>Sent once per playback per listener, at the moment the discard is sized.
 */
public record CatchUpReportPayload(BlockPos pos, long playbackId, int usedMillis, long skipBytes)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CatchUpReportPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "catchup_report"));

    public static final StreamCodec<FriendlyByteBuf, CatchUpReportPayload> STREAM_CODEC =
            StreamCodec.of(CatchUpReportPayload::write, CatchUpReportPayload::read);

    private static final org.slf4j.Logger SIGNAL =
            org.slf4j.LoggerFactory.getLogger("SAS-CatchUp");

    private static void write(FriendlyByteBuf buf, CatchUpReportPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeLong(p.playbackId);
        buf.writeVarInt(p.usedMillis);
        buf.writeVarLong(p.skipBytes);
    }

    private static CatchUpReportPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        long playbackId = buf.readLong();
        // Bounded like every other field a peer supplies. These only reach a log line, but a
        // log line a peer can size is still a log line a peer can flood.
        int usedMillis = Math.max(0, Math.min(ClientPlayAudioPayload.MAX_START_OFFSET_MILLIS,
                buf.readVarInt()));
        long skipBytes = Math.max(0, buf.readVarLong());
        return new CatchUpReportPayload(pos, playbackId, usedMillis, skipBytes);
    }

    public static void handle(CatchUpReportPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player().level() instanceof net.minecraft.server.level.ServerLevel level)) return;
            // Gated the way the finish report is: the line exists so the server's log can be
            // trusted about what a client did, so a client that was never sent the sound must
            // not be able to write one, and one that was must not be able to write a thousand.
            // Found by review on 2026-09-02, when this logged whatever any client sent.
            if (!com.spatialaudiosystem.audio.PlaybackSessionRegistry.acceptCatchUpReport(
                    level, payload.pos, payload.playbackId, context.player().getUUID())) return;
            SIGNAL.info("applied pos={},{},{} id={} by={} usedMs={} skipBytes={}",
                    payload.pos.getX(), payload.pos.getY(), payload.pos.getZ(),
                    String.format("%016x", payload.playbackId),
                    context.player().getGameProfile().getName(),
                    payload.usedMillis, payload.skipBytes);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
