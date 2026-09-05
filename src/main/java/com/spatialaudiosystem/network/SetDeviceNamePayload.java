package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.handy.SoundDeviceLink;
import com.spatialaudiosystem.handy.SoundDeviceRegistry;
import com.spatialaudiosystem.item.SoundHandyItem;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S: the owner names one of their devices from the handy screen. The name is bounded on
 * the wire and sanitised by the registry's rule on the server; the device must be the
 * sender's and loaded (the block entity holds the name, the registry mirrors it).
 */
public record SetDeviceNamePayload(GlobalPos pos, String name) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetDeviceNamePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "set_device_name"));
    public static final StreamCodec<FriendlyByteBuf, SetDeviceNamePayload> STREAM_CODEC =
            StreamCodec.of(SetDeviceNamePayload::write, SetDeviceNamePayload::read);

    private static void write(FriendlyByteBuf buf, SetDeviceNamePayload p) {
        GlobalPos.STREAM_CODEC.encode(buf, p.pos);
        buf.writeUtf(p.name, HandyDeviceListPayload.MAX_NAME_CHARS);
    }

    private static SetDeviceNamePayload read(FriendlyByteBuf buf) {
        GlobalPos pos = GlobalPos.STREAM_CODEC.decode(buf);
        String name = buf.readUtf(HandyDeviceListPayload.MAX_NAME_CHARS);
        return new SetDeviceNamePayload(pos, name);
    }

    public static void handle(SetDeviceNamePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            PlaybackDeviceBlockEntity be = SoundDeviceLink.ownedDevice(player.server, player.getUUID(), payload.pos);
            if (be == null) return;
            // From the handy in the hand, or from the device's own screen while it is open.
            boolean fromScreen = player.containerMenu instanceof com.spatialaudiosystem.menu.PlaybackDeviceMenu menu
                    && menu.getBlockEntity() == be;
            if (SoundHandyItem.held(player).isEmpty() && !fromScreen) return;
            be.setDeviceName(SoundDeviceRegistry.sanitizeName(payload.name));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
