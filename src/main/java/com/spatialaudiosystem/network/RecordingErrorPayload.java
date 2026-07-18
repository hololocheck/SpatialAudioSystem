package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.client.RecordingErrorState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: a recording could not start, with the reason code so the open Recording
 * screen can say why (see {@code RecordingDeviceBlockEntity.START_*}).
 */
public record RecordingErrorPayload(BlockPos pos, int reason) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RecordingErrorPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "recording_error"));

    public static final StreamCodec<FriendlyByteBuf, RecordingErrorPayload> STREAM_CODEC =
            StreamCodec.of(RecordingErrorPayload::write, RecordingErrorPayload::read);

    private static void write(FriendlyByteBuf buf, RecordingErrorPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeVarInt(p.reason);
    }

    private static RecordingErrorPayload read(FriendlyByteBuf buf) {
        return new RecordingErrorPayload(buf.readBlockPos(), buf.readVarInt());
    }

    public static void handle(RecordingErrorPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RecordingErrorState.set(payload.pos, payload.reason));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
