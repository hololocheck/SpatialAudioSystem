package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioArt;
import com.spatialaudiosystem.client.ClientArtCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * S2C: a recording's cover art (or empty bytes if it has none), keyed by audio id. The
 * client decodes it into a texture for the jacket, or remembers there is none.
 */
public record ArtDataPayload(UUID audioId, byte[] art) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ArtDataPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "art_data"));

    public static final StreamCodec<FriendlyByteBuf, ArtDataPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> { buf.writeUUID(p.audioId); buf.writeByteArray(p.art); },
                    buf -> new ArtDataPayload(buf.readUUID(), buf.readByteArray(AudioArt.MAX_ART_BYTES)));

    public static void handle(ArtDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientArtCache.receive(payload.audioId, payload.art));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
