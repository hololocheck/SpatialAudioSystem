package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ToggleAttenuationPayload(BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ToggleAttenuationPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "toggle_attenuation"));

    public static final StreamCodec<FriendlyByteBuf, ToggleAttenuationPayload> STREAM_CODEC =
            StreamCodec.of(ToggleAttenuationPayload::write, ToggleAttenuationPayload::read);

    private static void write(FriendlyByteBuf buf, ToggleAttenuationPayload payload) {
        buf.writeBlockPos(payload.pos);
    }

    private static ToggleAttenuationPayload read(FriendlyByteBuf buf) {
        return new ToggleAttenuationPayload(buf.readBlockPos());
    }

    public static void handle(ToggleAttenuationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();
            BlockEntity entity = player.level().getBlockEntity(payload.pos);
            if (entity instanceof PlaybackDeviceBlockEntity playbackDevice) {
                playbackDevice.setAttenuationMode(!playbackDevice.isAttenuationMode());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
