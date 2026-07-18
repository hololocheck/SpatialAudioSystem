package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * C2S: the client asks for a recording's cover art by audio id. The server replies with an
 * {@link ArtDataPayload} (empty bytes when there is no art), which the client caches.
 */
public record RequestArtPayload(UUID audioId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestArtPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "request_art"));

    public static final StreamCodec<FriendlyByteBuf, RequestArtPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeUUID(p.audioId),
                    buf -> new RequestArtPayload(buf.readUUID()));

    public static void handle(RequestArtPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) return;
            MinecraftServer server = sp.getServer();
            if (server == null) return;
            byte[] art = AudioStorage.loadArt(server, payload.audioId);
            PacketDistributor.sendToPlayer(sp,
                    new ArtDataPayload(payload.audioId, art != null ? art : new byte[0]));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
