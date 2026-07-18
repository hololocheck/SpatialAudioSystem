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

public record PlaybackControlPayload(BlockPos pos, boolean play) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlaybackControlPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "playback_control"));

    public static final StreamCodec<FriendlyByteBuf, PlaybackControlPayload> STREAM_CODEC =
            StreamCodec.of(PlaybackControlPayload::write, PlaybackControlPayload::read);

    private static void write(FriendlyByteBuf buf, PlaybackControlPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeBoolean(payload.play);
    }

    private static PlaybackControlPayload read(FriendlyByteBuf buf) {
        return new PlaybackControlPayload(buf.readBlockPos(), buf.readBoolean());
    }

    public static void handle(PlaybackControlPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            PlaybackDeviceBlockEntity be =
                    ServerInteractionGuard.playbackDevice(context.player(), payload.pos);
            if (be == null) return;

            // The device owns starting and stopping. This handler used to carry its own
            // copy of the start sequence, which left out the start timestamp and so had
            // playback time out on the next tick in any world past 12,000 ticks.
            if (payload.play) {
                be.startPlayback();
            } else {
                be.stopPlayback();
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
