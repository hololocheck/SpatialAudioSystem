package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
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
        // Routed inside a client class: naming Minecraft / LocalPlayer here makes the verifier
        // load client classes when the payload handler is registered on a dedicated server
        // (measured 2026-09-04: "invalid dist DEDICATED_SERVER" in the test JVM).
        context.enqueueWork(() ->
                com.spatialaudiosystem.screen.SoundHandyHudRenderer.route(resolve(payload.message), payload.color));
    }

    /**
     * A message sent as a lang key ("message." prefix, arguments after tabs) is translated
     * here, in the client's own language; a plain text is shown as it is. The handy sends
     * keys; the range board still sends texts the server translated.
     */
    static String resolve(String message) {
        if (message == null || !message.startsWith("message.")) return message;
        String[] parts = message.split("\t");
        Object[] args = new Object[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return net.minecraft.network.chat.Component.translatable(parts[0], args).getString();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
