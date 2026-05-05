package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClearAudioPayload(BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClearAudioPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "clear_audio"));

    public static final StreamCodec<FriendlyByteBuf, ClearAudioPayload> STREAM_CODEC =
            StreamCodec.of(ClearAudioPayload::write, ClearAudioPayload::read);

    private static void write(FriendlyByteBuf buf, ClearAudioPayload payload) {
        buf.writeBlockPos(payload.pos);
    }

    private static ClearAudioPayload read(FriendlyByteBuf buf) {
        return new ClearAudioPayload(buf.readBlockPos());
    }

    public static void handle(ClearAudioPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            BlockEntity entity = player.level().getBlockEntity(payload.pos);
            if (entity instanceof RecordingDeviceBlockEntity recordingDevice) {
                recordingDevice.clearPendingAudio();
                recordingDevice.clearMediaAudioData();
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
