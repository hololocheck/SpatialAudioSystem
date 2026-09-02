package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.server.ServerInteractionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetAttenuationRangePayload(BlockPos pos, int range) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetAttenuationRangePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "set_attenuation_range"));

    public static final StreamCodec<FriendlyByteBuf, SetAttenuationRangePayload> STREAM_CODEC =
            StreamCodec.of(SetAttenuationRangePayload::write, SetAttenuationRangePayload::read);

    private static void write(FriendlyByteBuf buf, SetAttenuationRangePayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeInt(payload.range);
    }

    private static SetAttenuationRangePayload read(FriendlyByteBuf buf) {
        net.minecraft.core.BlockPos pos = buf.readBlockPos();
        int range = buf.readInt();
        // Bounded at decode, like every value a peer can name: the server clamps too, but a
        // refused packet is a refused packet and a clamped one is a silent correction.
        if (range < com.spatialaudiosystem.audio.SpatialGain.MIN_RANGE_BLOCKS
                || range > com.spatialaudiosystem.audio.SpatialGain.MAX_RANGE_BLOCKS) {
            throw new io.netty.handler.codec.DecoderException("Invalid playback range: " + range);
        }
        return new SetAttenuationRangePayload(pos, range);
    }

    public static void handle(SetAttenuationRangePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            PlaybackDeviceBlockEntity playbackDevice =
                    ServerInteractionGuard.playbackDevice(context.player(), payload.pos);
            if (playbackDevice != null) {
                playbackDevice.setAttenuationRange(payload.range);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
