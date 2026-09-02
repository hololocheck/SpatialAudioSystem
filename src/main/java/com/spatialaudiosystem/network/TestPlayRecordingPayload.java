package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.server.ServerInteractionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S: preview ("test play") the finished medium in a recording device's output slot, or stop
 * it. Reuses the normal playback broadcast, so the preview plays at the device for every
 * nearby client just like a playback device would.
 */
public record TestPlayRecordingPayload(BlockPos pos, boolean start) implements CustomPacketPayload {

    private static final int[] NO_ATTENUATION = {8, 8, 8, 8, 8, 8};

    public static final CustomPacketPayload.Type<TestPlayRecordingPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "test_play_recording"));

    public static final StreamCodec<FriendlyByteBuf, TestPlayRecordingPayload> STREAM_CODEC =
            StreamCodec.of(TestPlayRecordingPayload::write, TestPlayRecordingPayload::read);

    private static void write(FriendlyByteBuf buf, TestPlayRecordingPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeBoolean(p.start);
    }

    private static TestPlayRecordingPayload read(FriendlyByteBuf buf) {
        return new TestPlayRecordingPayload(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(TestPlayRecordingPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            RecordingDeviceBlockEntity device =
                    ServerInteractionGuard.recordingDevice(context.player(), payload.pos);
            if (device == null || !(context.player().level() instanceof ServerLevel level)) return;

            if (payload.start) {
                startPreview(level, device, payload.pos);
            } else {
                stopPreview(level, payload.pos);
            }
        });
    }

    private static void startPreview(ServerLevel level, RecordingDeviceBlockEntity device, BlockPos pos) {
        byte[] audio;
        String format;
        if (device.getPendingAudioData() != null) {
            // A file was picked/uploaded but not yet written: preview it directly.
            audio = device.getPendingAudioData();
            format = device.getPendingFormat() != null ? device.getPendingFormat() : "ogg";
        } else {
            // Otherwise preview the finished medium in the output slot.
            ItemStack medium = device.getInventory().getStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT);
            audio = AudioStorage.loadForItem(level.getServer(), medium);
            if (audio == null) return;   // nothing to preview (no pending audio, empty output slot)
            format = medium.getOrDefault(ModDataComponents.AUDIO_FORMAT, "ogg");
        }

        long playbackId = PlaybackSessionRegistry.begin(level, pos);
        // A preview from the recording screen is one-shot, and deliberately not registered as
        // replayable: it is a check on the medium you are holding, not a sound placed in the
        // world for others to walk into.
        ClientPlayAudioPayload meta = new ClientPlayAudioPayload(
                pos, playbackId, audio.length, format, null, null, false, NO_ATTENUATION,
                // No loop, and no catching up: the preview always starts at the top,
                // because it is a check on the medium rather than a sound already running.
                // Not synchronised: a preview is a check on the medium in your hand, so it
                // starts at the top rather than wherever a shared sound has got to.
                false, 0, false);
        for (ServerPlayer sp : level.players()) {
            PacketDistributor.sendToPlayer(sp, meta);
            ClientAudioChunkPayload.sendChunked(sp, pos, playbackId, audio);
        }
    }

    private static void stopPreview(ServerLevel level, BlockPos pos) {
        ClientStopAudioPayload stop =
                new ClientStopAudioPayload(pos, PlaybackSessionRegistry.currentId(level, pos));
        PlaybackSessionRegistry.end(level, pos);
        for (ServerPlayer sp : level.players()) {
            PacketDistributor.sendToPlayer(sp, stop);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
