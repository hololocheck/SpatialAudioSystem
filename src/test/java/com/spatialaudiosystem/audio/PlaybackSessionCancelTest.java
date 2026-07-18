package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.audio.AudioManager.AudioPlayback;
import com.spatialaudiosystem.audio.AudioManager.PlaybackSession;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.SourceDataLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The cancellation half of SAS-AUDIO-005.
 *
 * <p>A stop can arrive while the file is still being decoded, before there is any line
 * to stop. The old code looked for a published playback, found none, and let the sound
 * start anyway — so stopping a device, or breaking it, could be followed by audio.
 *
 * <p>Both orderings are pinned here because the two events run on different threads and
 * neither one gets to be "the" order.
 */
class PlaybackSessionCancelTest {

    private static final BlockPos POS = new BlockPos(3, 70, 12);
    private static final long PLAYBACK_ID = 0x5A5_1234_5678L;

    private static PlaybackSession newSession() {
        return new PlaybackSession(POS, PLAYBACK_ID, null, null, true, new int[6]);
    }

    private static AudioPlayback playbackOn(SourceDataLine line) {
        return new AudioPlayback(line, POS, null, null, null, true, new int[6]);
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a stop that arrives before the line opens still stops the sound")
    void cancelBeforeAttachStopsTheSoundOnceItOpens() {
        SourceDataLine line = mock(SourceDataLine.class);
        PlaybackSession session = newSession();

        // The worker is still decoding: nothing to stop yet.
        session.cancel();
        assertThat(session.isCancelled()).isTrue();

        // The line finally opens. It must not be allowed to play.
        AudioPlayback playback = playbackOn(line);
        session.attach(playback);

        assertThat(playback.isStopped())
                .as("a sound whose stop arrived first must not start")
                .isTrue();
        verify(line).close();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a stop after the line opens reaches it")
    void cancelAfterAttachStopsTheOpenLine() {
        SourceDataLine line = mock(SourceDataLine.class);
        PlaybackSession session = newSession();

        AudioPlayback playback = playbackOn(line);
        session.attach(playback);
        assertThat(playback.isStopped()).as("not stopped yet").isFalse();

        session.cancel();

        assertThat(playback.isStopped()).isTrue();
        verify(line).close();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a session that is never cancelled leaves its playback alone")
    void anUncancelledSessionDoesNotStopItself() {
        SourceDataLine line = mock(SourceDataLine.class);
        PlaybackSession session = newSession();

        AudioPlayback playback = playbackOn(line);
        session.attach(playback);

        // Pinned so "cancel everything" cannot pass the two tests above.
        assertThat(session.isCancelled()).isFalse();
        assertThat(playback.isStopped()).isFalse();
        assertThat(session.playback()).isSameAs(playback);
    }
}
