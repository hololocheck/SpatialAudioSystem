package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: stop a sound.
 *
 * <p>Names the sound, not just the place: a stop for a playback that has already been
 * replaced must not silence its successor.
 */
public record ClientStopAudioPayload(BlockPos pos, long playbackId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientStopAudioPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "client_stop_audio"));

    public static final StreamCodec<FriendlyByteBuf, ClientStopAudioPayload> STREAM_CODEC =
            StreamCodec.of(ClientStopAudioPayload::write, ClientStopAudioPayload::read);

    private static void write(FriendlyByteBuf buf, ClientStopAudioPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeLong(payload.playbackId);
    }

    private static ClientStopAudioPayload read(FriendlyByteBuf buf) {
        return new ClientStopAudioPayload(buf.readBlockPos(), buf.readLong());
    }

    public static void handle(ClientStopAudioPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> AudioManager.getInstance().stopAudio(payload.pos, payload.playbackId));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
