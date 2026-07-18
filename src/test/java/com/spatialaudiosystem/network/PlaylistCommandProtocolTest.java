package com.spatialaudiosystem.network;

import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reproduction tests for SAS-NET-005.
 *
 * <p>The playlist command decoded its op and arguments straight off the wire, so an entry index
 * from a crafted packet reached the scheduler unchecked and indexed the playlist slots directly —
 * throwing out of the server thread. Bounds now belong to decoding, as they do for the upload
 * protocol and the range board.
 */
class PlaylistCommandProtocolTest {

    private static final BlockPos POS = new BlockPos(4, 5, 6);
    private static final int LAST_ENTRY = PlaybackDeviceBlockEntity.MAX_ENTRIES - 1;

    private static PlaylistCommandPayload decode(int op, int a1, int a2) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(POS);
        buf.writeInt(op);
        buf.writeInt(a1);
        buf.writeInt(a2);
        return PlaylistCommandPayload.STREAM_CODEC.decode(buf);
    }

    @Test
    @DisplayName("SAS-NET-005: a command this client would send round-trips")
    void validCommandRoundTrips() {
        PlaylistCommandPayload p = decode(PlaylistCommandPayload.OP_TEST, LAST_ENTRY, 0);
        assertThat(p.pos()).isEqualTo(POS);
        assertThat(p.op()).isEqualTo(PlaylistCommandPayload.OP_TEST);
        assertThat(p.a1()).isEqualTo(LAST_ENTRY);
        assertThat(p.a2()).isZero();
    }

    @Test
    @DisplayName("SAS-NET-005: an entry index past the playlist never reaches a handler")
    void refusesEntryIndexPastTheEnd() {
        assertThatThrownBy(() -> decode(PlaylistCommandPayload.OP_TEST, 99_999, 0))
                .isInstanceOf(DecoderException.class);
        assertThatThrownBy(() -> decode(PlaylistCommandPayload.OP_TEST,
                PlaybackDeviceBlockEntity.MAX_ENTRIES, 0))
                .isInstanceOf(DecoderException.class);
    }

    @Test
    @DisplayName("SAS-NET-005: a negative entry index is refused")
    void refusesNegativeEntryIndex() {
        assertThatThrownBy(() -> decode(PlaylistCommandPayload.OP_REMOVE_ENTRY, -1, 0))
                .isInstanceOf(DecoderException.class);
    }

    @Test
    @DisplayName("SAS-NET-005: an op outside the known set is refused")
    void refusesUnknownOp() {
        assertThatThrownBy(() -> decode(99, 0, 0)).isInstanceOf(DecoderException.class);
        assertThatThrownBy(() -> decode(-1, 0, 0)).isInstanceOf(DecoderException.class);
    }

    @Test
    @DisplayName("SAS-NET-005: a reorder target or play-count delta outside its range is refused")
    void refusesSecondArgumentOutOfRange() {
        assertThatThrownBy(() -> decode(PlaylistCommandPayload.OP_REORDER, 0, 9_999))
                .isInstanceOf(DecoderException.class);
        assertThatThrownBy(() -> decode(PlaylistCommandPayload.OP_ADJUST_PLAYCOUNT, 0, -9_999))
                .isInstanceOf(DecoderException.class);
    }
}
