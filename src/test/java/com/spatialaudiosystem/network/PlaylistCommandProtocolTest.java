package com.spatialaudiosystem.network;

import com.manta.api.data.Schema;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction tests for SAS-NET-005, on manta:data (MANTA_7_CONCEPT C4).
 *
 * <p>The playlist command decoded its op and arguments straight off the wire, so an entry index from a crafted
 * packet reached the scheduler unchecked and indexed the playlist slots directly - throwing out of the server
 * thread. The bounds moved with the command into the device layout's {@code playlist} action: asked here of
 * Manta's own codec ({@link Schema#accepts}), so a bound that drifted from the constants is red here.
 */
class PlaylistCommandProtocolTest {

    private static final Schema PLAYBACK = PlaybackDeviceData.schema();
    private static final int LAST_ENTRY = PlaybackDeviceBlockEntity.MAX_ENTRIES - 1;

    private static boolean accepts(int op, int a1, int a2) {
        return PLAYBACK.accepts("playlist", op, a1, a2);
    }

    @Test
    @DisplayName("SAS-NET-005: a command this client would send is accepted")
    void validCommandIsAccepted() {
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_TEST, LAST_ENTRY, 0)).isTrue();
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_REORDER, LAST_ENTRY - 1, LAST_ENTRY)).isTrue();
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_ADJUST_PLAYCOUNT, 0, -PlaybackDeviceBlockEntity.MAX_PLAY_COUNT))
                .isTrue();
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_TOGGLE_MODE, 0, 0)).isTrue();
    }

    @Test
    @DisplayName("SAS-NET-005: an entry index past the playlist never reaches a handler")
    void refusesEntryIndexPastTheEnd() {
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_TEST, 99_999, 0)).isFalse();
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_TEST, PlaybackDeviceBlockEntity.MAX_ENTRIES, 0)).isFalse();
    }

    @Test
    @DisplayName("SAS-NET-005: a negative entry index is refused")
    void refusesNegativeEntryIndex() {
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_REMOVE_ENTRY, -1, 0)).isFalse();
    }

    @Test
    @DisplayName("SAS-NET-005: an op outside the known set is refused")
    void refusesUnknownOp() {
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_TOGGLE_LOOP + 1, 0, 0)).isFalse();
        assertThat(accepts(-1, 0, 0)).isFalse();
    }

    @Test
    @DisplayName("SAS-NET-005: a reorder target or play-count delta outside its range is refused")
    void refusesSecondArgumentOutOfRange() {
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_REORDER, 0, 9_999)).isFalse();
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_REORDER, 0, PlaybackDeviceBlockEntity.MAX_ENTRIES + 1)).isFalse();
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_ADJUST_PLAYCOUNT, 0, -9_999)).isFalse();
        assertThat(accepts(PlaybackDeviceData.PLAYLIST_ADJUST_PLAYCOUNT, 0,
                -PlaybackDeviceBlockEntity.MAX_PLAY_COUNT - 1)).isFalse();
    }
}
