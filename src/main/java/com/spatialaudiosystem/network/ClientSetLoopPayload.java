package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Changes whether a sound already playing keeps repeating.
 *
 * <p>Endlessness used to be fixed for the life of a playback: the button set it at the start and
 * turning it off did nothing until the next one. Reported from a live server on 2026-08-30 --
 * "無限再生ボタンをオフにしても無限再生され続ける" -- and it is the reasonable expectation, since
 * the button sits beside a stop and reads as a state of the device rather than of one start.
 *
 * <p>Turning it off lets the current pass finish rather than cutting the sound: the decoder
 * checks the flag when it reaches the end, so the sound ends where it was always going to end.
 * That also produces the completion report a one-shot would have produced, which is what lets a
 * schedule advance and what retires the session on the server.
 *
 * <p>Names the sound, not just the place, for the same reason the stop payload does: a message
 * about a playback that has already been replaced must not reach its successor.
 */
public record ClientSetLoopPayload(BlockPos pos, long playbackId, boolean loop)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientSetLoopPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "client_set_loop"));

    public static final StreamCodec<FriendlyByteBuf, ClientSetLoopPayload> STREAM_CODEC =
            StreamCodec.of(ClientSetLoopPayload::write, ClientSetLoopPayload::read);

    private static void write(FriendlyByteBuf buf, ClientSetLoopPayload payload) {
        buf.writeBlockPos(payload.pos);
        buf.writeLong(payload.playbackId);
        buf.writeBoolean(payload.loop);
    }

    private static ClientSetLoopPayload read(FriendlyByteBuf buf) {
        return new ClientSetLoopPayload(buf.readBlockPos(), buf.readLong(), buf.readBoolean());
    }

    public static void handle(ClientSetLoopPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                AudioManager.getInstance().setLoop(payload.pos, payload.playbackId, payload.loop));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
