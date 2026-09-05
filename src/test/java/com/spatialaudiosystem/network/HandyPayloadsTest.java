package com.spatialaudiosystem.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SAS-HANDY-003: the handy's three payloads round-trip, and every bound is enforced at decode
 * (BELUGAEXPERIENCE R3.6.1): the action range, the argument range, the row cap, the name length.
 */
class HandyPayloadsTest {

    private static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static GlobalPos at(int x) {
        return GlobalPos.of(Level.OVERWORLD, new BlockPos(x, 64, -3));
    }

    @Test
    @DisplayName("SAS-HANDY-003: an action round-trips with and without a position")
    void actionRoundTrip() {
        FriendlyByteBuf b = buf();
        HandyActionPayload.STREAM_CODEC.encode(b, HandyActionPayload.at(HandyActionPayload.PLAY, at(5)));
        HandyActionPayload back = HandyActionPayload.STREAM_CODEC.decode(b);
        assertThat(back.action()).isEqualTo(HandyActionPayload.PLAY);
        assertThat(back.pos()).contains(at(5));

        b = buf();
        HandyActionPayload.STREAM_CODEC.encode(b, HandyActionPayload.of(HandyActionPayload.SET_HUD, 1));
        back = HandyActionPayload.STREAM_CODEC.decode(b);
        assertThat(back.action()).isEqualTo(HandyActionPayload.SET_HUD);
        assertThat(back.arg()).isEqualTo(1);
        assertThat(back.pos()).isEmpty();
    }

    @Test
    @DisplayName("SAS-HANDY-003: an action or argument outside its bound is refused at decode, not by the handler")
    void actionBoundsAtDecode() {
        FriendlyByteBuf b = buf();
        HandyActionPayload.STREAM_CODEC.encode(b, new HandyActionPayload(HandyActionPayload.MAX_ACTION + 1, 0, Optional.empty()));
        assertThatThrownBy(() -> HandyActionPayload.STREAM_CODEC.decode(b)).isInstanceOf(DecoderException.class);

        FriendlyByteBuf c = buf();
        HandyActionPayload.STREAM_CODEC.encode(c, new HandyActionPayload(-1, 0, Optional.empty()));
        assertThatThrownBy(() -> HandyActionPayload.STREAM_CODEC.decode(c)).isInstanceOf(DecoderException.class);

        FriendlyByteBuf d = buf();
        HandyActionPayload.STREAM_CODEC.encode(d, new HandyActionPayload(0, HandyActionPayload.MAX_ARG + 1, Optional.empty()));
        assertThatThrownBy(() -> HandyActionPayload.STREAM_CODEC.decode(d)).isInstanceOf(DecoderException.class);

        FriendlyByteBuf e = buf();
        HandyActionPayload.STREAM_CODEC.encode(e, new HandyActionPayload(HandyActionPayload.MAX_ACTION, -HandyActionPayload.MAX_ARG, Optional.empty()));
        assertThat(HandyActionPayload.STREAM_CODEC.decode(e).action()).as("the bounds themselves pass").isEqualTo(HandyActionPayload.MAX_ACTION);
    }

    @Test
    @DisplayName("SAS-HANDY-003: the device list round-trips and refuses a 65th row at decode")
    void listRoundTripAndCap() {
        List<HandyDeviceListPayload.Row> rows = new ArrayList<>();
        // The medium the mini HUD shows travels as its file name and format (v2.4); the
        // shorter constructor is the shape without one.
        rows.add(new HandyDeviceListPayload.Row(at(1), "Lobby", true, true, true, false, "教会の見える駅.wav", "wav"));
        rows.add(new HandyDeviceListPayload.Row(GlobalPos.of(Level.END, new BlockPos(0, 0, 0)), "", false, false, false, false));
        FriendlyByteBuf b = buf();
        HandyDeviceListPayload.STREAM_CODEC.encode(b, new HandyDeviceListPayload(rows));
        HandyDeviceListPayload back = HandyDeviceListPayload.STREAM_CODEC.decode(b);
        assertThat(back.rows()).containsExactlyElementsOf(rows);
        assertThat(back.rows().get(0).mediumFile()).isEqualTo("教会の見える駅.wav");
        assertThat(back.rows().get(0).mediumFormat()).isEqualTo("wav");
        assertThat(back.rows().get(1).mediumFile()).as("no medium = empty, never null").isEmpty();

        // A file name past the wire's bound is refused on the way out, like a device name.
        String longFile = "x".repeat(HandyDeviceListPayload.MAX_FILE_CHARS + 1);
        List<HandyDeviceListPayload.Row> longRows = List.of(
                new HandyDeviceListPayload.Row(at(1), "", true, false, true, false, longFile, "wav"));
        assertThatThrownBy(() -> HandyDeviceListPayload.STREAM_CODEC.encode(buf(), new HandyDeviceListPayload(longRows)))
                .isInstanceOf(io.netty.handler.codec.EncoderException.class);
        String longFormat = "x".repeat(HandyDeviceListPayload.MAX_FORMAT_CHARS + 1);
        List<HandyDeviceListPayload.Row> longFormatRows = List.of(
                new HandyDeviceListPayload.Row(at(1), "", true, false, true, false, "a.wav", longFormat));
        assertThatThrownBy(() -> HandyDeviceListPayload.STREAM_CODEC.encode(buf(), new HandyDeviceListPayload(longFormatRows)))
                .isInstanceOf(io.netty.handler.codec.EncoderException.class);

        // The decode side has its own bound: a peer that wrote past it without the limit is refused (review 2026-09-05).
        FriendlyByteBuf d = buf();
        d.writeVarInt(1);
        GlobalPos.STREAM_CODEC.encode(d, at(1));
        d.writeUtf("");
        d.writeBoolean(true); d.writeBoolean(false); d.writeBoolean(true); d.writeBoolean(false);
        d.writeUtf(longFile);   // no limit on the writer's side
        d.writeUtf("wav");
        assertThatThrownBy(() -> HandyDeviceListPayload.STREAM_CODEC.decode(d)).isInstanceOf(DecoderException.class);

        List<HandyDeviceListPayload.Row> tooMany = new ArrayList<>();
        for (int i = 0; i <= HandyDeviceListPayload.MAX_ROWS; i++) tooMany.add(new HandyDeviceListPayload.Row(at(i), "", false, false, false, false));
        FriendlyByteBuf c = buf();
        HandyDeviceListPayload.STREAM_CODEC.encode(c, new HandyDeviceListPayload(tooMany));
        assertThatThrownBy(() -> HandyDeviceListPayload.STREAM_CODEC.decode(c)).isInstanceOf(DecoderException.class);
    }

    @Test
    @DisplayName("SAS-HANDY-003: a device name longer than the wire allows is refused")
    void nameLengthOnTheWire() {
        FriendlyByteBuf b = buf();
        SetDeviceNamePayload.STREAM_CODEC.encode(b, new SetDeviceNamePayload(at(1), "Hall"));
        SetDeviceNamePayload back = SetDeviceNamePayload.STREAM_CODEC.decode(b);
        assertThat(back.name()).isEqualTo("Hall");
        assertThat(back.pos()).isEqualTo(at(1));

        String tooLong = "x".repeat(HandyDeviceListPayload.MAX_NAME_CHARS + 1);
        assertThatThrownBy(() -> SetDeviceNamePayload.STREAM_CODEC.encode(buf(), new SetDeviceNamePayload(at(1), tooLong)))
                .as("writeUtf with a limit refuses on the way out as well")
                .isInstanceOf(io.netty.handler.codec.EncoderException.class);
    }
}
