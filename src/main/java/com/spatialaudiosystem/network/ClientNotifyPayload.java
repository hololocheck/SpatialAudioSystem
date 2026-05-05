package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.screen.RangeBoardHudRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: show a notification on the range board HUD (below item name).
 */
public record ClientNotifyPayload(String message, int color) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientNotifyPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "client_notify"));

    public static final StreamCodec<FriendlyByteBuf, ClientNotifyPayload> STREAM_CODEC =
            StreamCodec.of(ClientNotifyPayload::write, ClientNotifyPayload::read);

    private static void write(FriendlyByteBuf buf, ClientNotifyPayload p) {
        buf.writeUtf(p.message);
        buf.writeInt(p.color);
    }

    private static ClientNotifyPayload read(FriendlyByteBuf buf) {
        return new ClientNotifyPayload(buf.readUtf(), buf.readInt());
    }

    public static void handle(ClientNotifyPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                RangeBoardHudRenderer.showNotification(payload.message, payload.color, 2000));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
