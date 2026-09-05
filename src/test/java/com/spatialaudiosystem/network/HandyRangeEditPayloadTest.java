package com.spatialaudiosystem.network;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SAS-HANDY-005: the range edit round-trips, its bounds hold at decode, and a face never leaves the board's 0..15. */
class HandyRangeEditPayloadTest {

    private static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    @Test
    @DisplayName("SAS-HANDY-005: a corner and a face step round-trip")
    void roundTrip() {
        FriendlyByteBuf b = buf();
        HandyRangeEditPayload.STREAM_CODEC.encode(b, HandyRangeEditPayload.corner(HandyRangeEditPayload.SET_POS2, new BlockPos(1, -2, 3)));
        HandyRangeEditPayload back = HandyRangeEditPayload.STREAM_CODEC.decode(b);
        assertThat(back.op()).isEqualTo(HandyRangeEditPayload.SET_POS2);
        assertThat(back.corner()).contains(new BlockPos(1, -2, 3));

        b = buf();
        HandyRangeEditPayload.STREAM_CODEC.encode(b, HandyRangeEditPayload.stepFace(5, -1));
        back = HandyRangeEditPayload.STREAM_CODEC.decode(b);
        assertThat(back.op()).isEqualTo(HandyRangeEditPayload.STEP_FACE);
        assertThat(back.face()).isEqualTo(5);
        assertThat(back.value()).isEqualTo(-1);
        assertThat(back.corner()).isEmpty();
    }

    @Test
    @DisplayName("SAS-HANDY-005: op, face and step outside their bounds are refused at decode")
    void boundsAtDecode() {
        FriendlyByteBuf b = buf();
        HandyRangeEditPayload.STREAM_CODEC.encode(b, new HandyRangeEditPayload(HandyRangeEditPayload.MAX_OP + 1, 0, 0, Optional.empty()));
        assertThatThrownBy(() -> HandyRangeEditPayload.STREAM_CODEC.decode(b)).isInstanceOf(DecoderException.class);
        FriendlyByteBuf c = buf();
        HandyRangeEditPayload.STREAM_CODEC.encode(c, new HandyRangeEditPayload(HandyRangeEditPayload.STEP_FACE, HandyRangeEditPayload.FACES, 1, Optional.empty()));
        assertThatThrownBy(() -> HandyRangeEditPayload.STREAM_CODEC.decode(c)).isInstanceOf(DecoderException.class);
        FriendlyByteBuf d = buf();
        HandyRangeEditPayload.STREAM_CODEC.encode(d, new HandyRangeEditPayload(HandyRangeEditPayload.STEP_FACE, 0, HandyRangeEditPayload.MAX_STEP + 1, Optional.empty()));
        assertThatThrownBy(() -> HandyRangeEditPayload.STREAM_CODEC.decode(d)).isInstanceOf(DecoderException.class);
    }

    @Test
    @DisplayName("SAS-HANDY-005: a face steps by one and stays within 0..15 whatever the step says")
    void faceStepsByOneWithinTheBoard() {
        assertThat(HandyRangeEditPayload.steppedFace(8, 1)).isEqualTo(9);
        assertThat(HandyRangeEditPayload.steppedFace(8, -1)).isEqualTo(7);
        assertThat(HandyRangeEditPayload.steppedFace(8, 1000)).as("a big step is still one").isEqualTo(9);
        assertThat(HandyRangeEditPayload.steppedFace(15, 1)).isEqualTo(15);
        assertThat(HandyRangeEditPayload.steppedFace(0, -1)).isEqualTo(0);
    }
}
