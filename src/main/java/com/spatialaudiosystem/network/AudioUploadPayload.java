package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AudioUploadPayload(BlockPos pos, byte[] audioData, String fileName, String format)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AudioUploadPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "audio_upload"));

    public static final StreamCodec<FriendlyByteBuf, AudioUploadPayload> STREAM_CODEC =
            StreamCodec.of(AudioUploadPayload::write, AudioUploadPayload::read);

    private static void write(FriendlyByteBuf buf, AudioUploadPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeByteArray(payload.audioData);
        buf.writeUtf(payload.fileName);
        buf.writeUtf(payload.format);
    }

    private static AudioUploadPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        byte[] audioData = buf.readByteArray(AudioStorage.MAX_AUDIO_SIZE); // Max 8MB
        String fileName = buf.readUtf();
        String format = buf.readUtf();
        return new AudioUploadPayload(pos, audioData, fileName, format);
    }

    public static void handle(AudioUploadPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var player = context.player();

            // Validate file size before accepting
            net.minecraft.network.chat.Component sizeError = AudioStorage.validateSize(payload.audioData);
            if (sizeError != null) {
                player.sendSystemMessage(sizeError.copy()
                        .withStyle(net.minecraft.ChatFormatting.RED));
                SpatialAudioSystem.LOGGER.warn("Player {} tried to upload oversized audio: {} bytes",
                        player.getName().getString(), payload.audioData.length);
                return;
            }

            BlockEntity entity = player.level().getBlockEntity(payload.pos);
            if (entity instanceof RecordingDeviceBlockEntity recordingDevice) {
                recordingDevice.setPendingAudio(payload.audioData, payload.fileName, payload.format);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
