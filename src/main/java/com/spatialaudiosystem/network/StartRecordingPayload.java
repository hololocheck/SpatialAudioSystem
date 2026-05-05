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

public record StartRecordingPayload(BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StartRecordingPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "start_recording"));

    public static final StreamCodec<FriendlyByteBuf, StartRecordingPayload> STREAM_CODEC =
            StreamCodec.of(StartRecordingPayload::write, StartRecordingPayload::read);

    private static void write(FriendlyByteBuf buf, StartRecordingPayload payload) {
        buf.writeBlockPos(payload.pos);
    }

    private static StartRecordingPayload read(FriendlyByteBuf buf) {
        return new StartRecordingPayload(buf.readBlockPos());
    }

    public static void handle(StartRecordingPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            BlockEntity entity = player.level().getBlockEntity(payload.pos);
            if (entity instanceof RecordingDeviceBlockEntity recordingDevice) {
                recordingDevice.startRecording();
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
