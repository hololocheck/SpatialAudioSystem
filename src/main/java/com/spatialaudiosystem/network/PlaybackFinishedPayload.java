package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * A client reporting that a sound it was playing has run to its end.
 *
 * <p>Split out from {@link PlaybackControlPayload} because the two cannot carry the same
 * authority: a stop command comes from an open screen, while this arrives from a client
 * that has no screen open and may be nowhere near the device. Keeping them on one packet
 * meant the stop command could not be checked without breaking completion.
 *
 * <p>This packet is still only as trustworthy as the client that sent it. Tying it to a
 * server-issued playback id, so a report can name only a sound that is actually playing,
 * needs the session identity work (SAS-AUDIO-005) and is not done here.
 */
public record PlaybackFinishedPayload(BlockPos pos, long playbackId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlaybackFinishedPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "playback_finished"));

    public static final StreamCodec<FriendlyByteBuf, PlaybackFinishedPayload> STREAM_CODEC =
            StreamCodec.of(PlaybackFinishedPayload::write, PlaybackFinishedPayload::read);

    private static void write(FriendlyByteBuf buf, PlaybackFinishedPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeLong(payload.playbackId);
    }

    private static PlaybackFinishedPayload read(FriendlyByteBuf buf) {
        return new PlaybackFinishedPayload(buf.readBlockPos(), buf.readLong());
    }

    public static void handle(PlaybackFinishedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player().level() instanceof ServerLevel level)) return;

            // Every client playing this sound reports it, and a device that has already
            // restarted must not be ended by the previous sound's report. Exactly one
            // report for the sound that is actually playing here gets through.
            if (!PlaybackSessionRegistry.consumeIfCurrent(level, payload.pos, payload.playbackId)) return;

            // Sounds started through the public API play at positions with no block entity,
            // and their listeners still need the completion event.
            NeoForge.EVENT_BUS.post(new belugalab.sas.api.PlaybackEndedEvent(level, payload.pos));

            if (level.getBlockEntity(payload.pos) instanceof PlaybackDeviceBlockEntity device) {
                device.setIsPlaying(false);
                ClientStopAudioPayload stop = new ClientStopAudioPayload(payload.pos, payload.playbackId);
                for (ServerPlayer listener : level.players()) {
                    PacketDistributor.sendToPlayer(listener, stop);
                }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
