package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.handy.SoundDeviceRegistry;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C: the owner's playback devices, as the server knows them right now. Sent when the handy
 * asks (taken in hand, screen opened) and whenever the registry changes for that owner.
 * The row count is bounded at decode, like every SAS payload (BELUGAEXPERIENCE R3.6.1).
 */
public record HandyDeviceListPayload(List<Row> rows) implements CustomPacketPayload {

    /**
     * One device: where, what it is called (empty = unnamed), what it is doing, whether it
     * holds something playable and a range board (the HUD's "cannot play / cannot edit"), and
     * the medium it would play - its file name and format, empty when there is none (the mini
     * HUD shows the medium's icon and file, user's real-device note 2026-09-05).
     * An unloaded device reports false and empty for all of them - the server cannot look inside it.
     */
    public record Row(GlobalPos pos, String name, boolean loaded, boolean playing, boolean hasMedium, boolean hasBoard,
                      String mediumFile, String mediumFormat) {
        /** The shape before the medium fields: no medium information. */
        public Row(GlobalPos pos, String name, boolean loaded, boolean playing, boolean hasMedium, boolean hasBoard) {
            this(pos, name, loaded, playing, hasMedium, hasBoard, "", "");
        }
    }

    public static final int MAX_ROWS = SoundDeviceRegistry.MAX_DEVICES_PER_OWNER;
    /** UTF-16 units allowed for a name on the wire; the registry keeps 32 code points. */
    public static final int MAX_NAME_CHARS = 64;
    /** UTF-16 units allowed for a medium's file name on the wire (the HUD clips it to its width anyway). */
    public static final int MAX_FILE_CHARS = 128;
    /** UTF-16 units allowed for a medium's format ("wav", "ogg", "mp3"). */
    public static final int MAX_FORMAT_CHARS = 16;

    public static final CustomPacketPayload.Type<HandyDeviceListPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "handy_device_list"));
    public static final StreamCodec<FriendlyByteBuf, HandyDeviceListPayload> STREAM_CODEC =
            StreamCodec.of(HandyDeviceListPayload::write, HandyDeviceListPayload::read);

    private static void write(FriendlyByteBuf buf, HandyDeviceListPayload p) {
        buf.writeVarInt(p.rows.size());
        for (Row r : p.rows) {
            GlobalPos.STREAM_CODEC.encode(buf, r.pos);
            buf.writeUtf(r.name, MAX_NAME_CHARS);
            buf.writeBoolean(r.loaded);
            buf.writeBoolean(r.playing);
            buf.writeBoolean(r.hasMedium);
            buf.writeBoolean(r.hasBoard);
            buf.writeUtf(r.mediumFile, MAX_FILE_CHARS);
            buf.writeUtf(r.mediumFormat, MAX_FORMAT_CHARS);
        }
    }

    private static HandyDeviceListPayload read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ROWS) throw new DecoderException("Invalid device count: " + n);
        List<Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            GlobalPos pos = GlobalPos.STREAM_CODEC.decode(buf);
            String name = buf.readUtf(MAX_NAME_CHARS);
            boolean loaded = buf.readBoolean();
            boolean playing = buf.readBoolean();
            boolean hasMedium = buf.readBoolean();
            boolean hasBoard = buf.readBoolean();
            String mediumFile = buf.readUtf(MAX_FILE_CHARS);
            String mediumFormat = buf.readUtf(MAX_FORMAT_CHARS);
            rows.add(new Row(pos, name, loaded, playing, hasMedium, hasBoard, mediumFile, mediumFormat));
        }
        return new HandyDeviceListPayload(rows);
    }

    public static void handle(HandyDeviceListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> com.spatialaudiosystem.client.HandyDeviceListClient.accept(payload.rows));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
