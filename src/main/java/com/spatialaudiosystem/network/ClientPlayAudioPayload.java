package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: metadata-only playback start signal.
 * Audio data follows via {@link ClientAudioChunkPayload} chunks.
 */
public record ClientPlayAudioPayload(
        BlockPos pos,
        long playbackId,
        int totalSize,
        String format,
        BlockPos rangePos1,
        BlockPos rangePos2,
        boolean attenuationMode,
        int[] attenuationRanges) implements CustomPacketPayload {

    /** One value per face. Sizing an array from the wire without this is an allocation
     *  a peer chooses for us. */
    private static final int RANGE_COUNT = 6;

    public static final CustomPacketPayload.Type<ClientPlayAudioPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "client_play_audio"));

    public static final StreamCodec<FriendlyByteBuf, ClientPlayAudioPayload> STREAM_CODEC =
            StreamCodec.of(ClientPlayAudioPayload::write, ClientPlayAudioPayload::read);

    private static void write(FriendlyByteBuf buf, ClientPlayAudioPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeLong(p.playbackId);
        buf.writeInt(p.totalSize);
        buf.writeUtf(p.format);
        buf.writeBoolean(p.rangePos1 != null);
        if (p.rangePos1 != null) {
            buf.writeBlockPos(p.rangePos1);
            buf.writeBlockPos(p.rangePos2);
        }
        buf.writeBoolean(p.attenuationMode);
        buf.writeVarInt(p.attenuationRanges.length);
        for (int r : p.attenuationRanges) buf.writeVarInt(r);
    }

    private static ClientPlayAudioPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        long playbackId = buf.readLong();
        int totalSize = buf.readInt();
        String format = buf.readUtf();
        boolean hasRange = buf.readBoolean();
        BlockPos rangePos1 = hasRange ? buf.readBlockPos() : null;
        BlockPos rangePos2 = hasRange ? buf.readBlockPos() : null;
        boolean attenuationMode = buf.readBoolean();
        int len = buf.readVarInt();
        if (len < 0 || len > RANGE_COUNT) {
            throw new DecoderException("Invalid attenuation range count: " + len);
        }
        if (totalSize <= 0 || totalSize > AudioStorage.MAX_AUDIO_SIZE) {
            throw new DecoderException("Invalid audio size: " + totalSize);
        }
        int[] attenuationRanges = new int[len];
        for (int i = 0; i < len; i++) attenuationRanges[i] = buf.readVarInt();
        return new ClientPlayAudioPayload(pos, playbackId, totalSize, format, rangePos1, rangePos2,
                attenuationMode, attenuationRanges);
    }

    public static void handle(ClientPlayAudioPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                ClientAudioChunkPayload.prepareSession(payload.pos, payload.playbackId,
                        payload.totalSize, payload.format,
                        payload.rangePos1, payload.rangePos2,
                        payload.attenuationMode, payload.attenuationRanges));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
