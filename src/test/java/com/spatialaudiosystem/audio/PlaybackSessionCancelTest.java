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
import static org.mockito.Mockito.when;

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
        return new PlaybackSession(POS, PLAYBACK_ID, null, null, true, new int[6], false, 0, false, 0L);
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

    @Test
    @DisplayName("SAS-AUDIO-005: a sound re-delivered under the same id does not report itself over")
    void aResentSoundDoesNotReportTheOldCopyFinished() {
        AudioManager manager = AudioManager.getInstance();
        PlaybackSession first = newSession();
        PlaybackSession second = newSession();          // same pos, same playbackId

        supersedeAndCancel(manager, first, second);

        // A same-dimension respawn makes the server forget the delivery, and the sweep re-sends
        // the same sound. The client replaces its session and cancels the old worker; that
        // worker's finally used to report completion under the unchanged id, which the server
        // accepts -- ending the sound for everyone still hearing it and advancing the schedule.
        assertThat(first.superseded)
                .as("the replaced session's end is the new session's to report, not its own")
                .isTrue();
        assertThat(second.superseded)
                .as("the session that is actually playing must still report when it ends")
                .isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a different sound at the same block still reports the old one over")
    void aDifferentSoundDoesNotSuppressTheOldReport() {
        AudioManager manager = AudioManager.getInstance();
        PlaybackSession first = newSession();
        PlaybackSession second = new PlaybackSession(
                POS, PLAYBACK_ID + 1, null, null, true, new int[6], false, 0, false, 0L);

        supersedeAndCancel(manager, first, second);

        // Suppressing here would lose the completion of a sound that really did end -- the
        // schedule would never advance past it. Only a re-delivery of the same id is silent.
        assertThat(first.superseded).isFalse();
    }

    /** Drives the same replacement decision playAudio makes, without starting a decode thread. */
    private static void supersedeAndCancel(AudioManager manager,
                                           PlaybackSession previous, PlaybackSession next) {
        manager.publishSession(previous);
        manager.publishSession(next);
    }

    @Test
    @DisplayName("SAS-AUDIO-005: an ordinary one-shot reports its own end")
    void aOneShotReportsItself() {
        // The path the schedule advances on. Suppressing here would park the device forever.
        assertThat(AudioManager.shouldReportFinished(newSession())).isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a loop does not report an end it never reached")
    void aLoopDoesNotReport() {
        PlaybackSession loop = new PlaybackSession(
                POS, PLAYBACK_ID, null, null, true, new int[6], true, 0, false, 0L);
        // A loop ends only because something stopped it, and that something already knows.
        assertThat(AudioManager.shouldReportFinished(loop)).isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a superseded session does not report the sound that replaced it")
    void aSupersededSessionDoesNotReport() {
        PlaybackSession first = newSession();
        AudioManager.getInstance().publishSession(first);
        AudioManager.getInstance().publishSession(newSession());   // same pos, same id

        // Reading superseded is the half that was unchecked: setting it had a test, acting on
        // it did not, so the flag could be honoured everywhere except where it matters.
        assertThat(AudioManager.shouldReportFinished(first)).isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: turning the endless button off reaches the sound already playing")
    void setLoopClearsItOnTheRunningSession() {
        AudioManager manager = AudioManager.getInstance();
        PlaybackSession session = new PlaybackSession(
                POS, PLAYBACK_ID, null, null, true, new int[6], true, 0, false, 0L);
        manager.publishSession(session);

        manager.setLoop(POS, PLAYBACK_ID, false);

        // The decoders read this when they reach the end of a pass, so clearing it lets the
        // current pass finish and then end normally. Before this the flag was fixed for the
        // life of the playback and the button did nothing until the next start.
        assertThat(session.loop).isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a loop change for a replaced sound does not reach its successor")
    void setLoopIgnoresAStaleId() {
        AudioManager manager = AudioManager.getInstance();
        PlaybackSession session = new PlaybackSession(
                POS, PLAYBACK_ID, null, null, true, new int[6], true, 0, false, 0L);
        manager.publishSession(session);

        manager.setLoop(POS, PLAYBACK_ID + 1, false);

        // A different sound now plays at this block. Acting on its predecessor's message would
        // silently stop the wrong one repeating.
        assertThat(session.loop).isTrue();
    }
}
