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

public record ToggleRangeDisplayPayload(BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ToggleRangeDisplayPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "toggle_range_display"));

    public static final StreamCodec<FriendlyByteBuf, ToggleRangeDisplayPayload> STREAM_CODEC =
            StreamCodec.of(ToggleRangeDisplayPayload::write, ToggleRangeDisplayPayload::read);

    private static void write(FriendlyByteBuf buf, ToggleRangeDisplayPayload payload) {
        buf.writeBlockPos(payload.pos);
    }

    private static ToggleRangeDisplayPayload read(FriendlyByteBuf buf) {
        return new ToggleRangeDisplayPayload(buf.readBlockPos());
    }

    public static void handle(ToggleRangeDisplayPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            PlaybackDeviceBlockEntity playbackDevice =
                    ServerInteractionGuard.playbackDevice(context.player(), payload.pos);
            if (playbackDevice != null) {
                playbackDevice.setShowRange(!playbackDevice.isShowRange());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
