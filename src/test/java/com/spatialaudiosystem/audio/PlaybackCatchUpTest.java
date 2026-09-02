package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.audio.AudioManager.PlaybackSession;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The client half of "a listener who arrives late hears where the sound has got to".
 *
 * <p>The server sends an offset; this is the part that acts on it. It is exercised here rather
 * than on real audio because the three decoders all funnel their PCM through one method, so the
 * rule can be checked once, with a mocked line, instead of three times with three files.
 *
 * <p>Reported from a live server on 2026-08-30: a player joining while a sound played heard
 * nothing. Delivering to them was the server's half; starting them at the right point is this
 * one, and starting them at the top instead would have been the same bug wearing a fix.
 */
class PlaybackCatchUpTest {

    private static final BlockPos POS = new BlockPos(3, 70, 12);
    /** 16-bit stereo at 44.1 kHz: 4 bytes a frame, 176,400 bytes a second. */
    private static final AudioFormat CD = new AudioFormat(44_100f, 16, 2, true, false);
    private static final int BYTES_PER_SECOND = 176_400;

    private final List<Integer> written = new ArrayList<>();
    private final SourceDataLine line = mock(SourceDataLine.class);

    private PlaybackSession sessionSkipping(int offsetMillis) {
        when(line.getFormat()).thenReturn(CD);
        when(line.write(any(), anyInt(), anyInt())).thenAnswer(call -> {
            written.add(call.getArgument(2));
            return call.<Integer>getArgument(2);
        });
        // No beginSkip here on purpose: the sizing is part of the write path now, so these
        // tests drive the same code a real decoder does rather than a step beside it.
        return new PlaybackSession(POS, 1L, null, null, true, new int[6], false, offsetMillis, false, 0L);
    }

    private void feed(PlaybackSession session, int bytes) {
        AudioManager.writePcm(line, session, new byte[bytes], 0, bytes);
    }

    @Test
    @DisplayName("SAS-AUDIO-013: a listener present from the start hears the first byte")
    void noOffsetWritesEverything() {
        PlaybackSession session = sessionSkipping(0);
        feed(session, 4_096);
        // The overwhelmingly common path. If the discard ever bit here it would clip the opening
        // of every sound for everyone.
        assertThat(written).containsExactly(4_096);
    }

    @Test
    @DisplayName("SAS-AUDIO-013: the part the listener missed is discarded, not played")
    void theMissedPartIsDiscarded() {
        PlaybackSession session = sessionSkipping(1_000);
        feed(session, BYTES_PER_SECOND / 2);
        assertThat(written).as("still inside the missed second").isEmpty();
        feed(session, BYTES_PER_SECOND / 2);
        assertThat(written).as("still inside the missed second").isEmpty();
        feed(session, 4_000);
        assertThat(written).as("past it now, so this buffer plays").containsExactly(4_000);
    }

    @Test
    @DisplayName("SAS-AUDIO-013: a buffer straddling the point plays only its tail")
    void aStraddlingBufferPlaysOnlyItsTail() {
        PlaybackSession session = sessionSkipping(1_000);
        feed(session, BYTES_PER_SECOND + 4_000);
        // Writing the whole buffer would replay a second the listener already missed; dropping
        // the whole buffer would lose four thousand bytes nobody asked to lose.
        assertThat(written).containsExactly(4_000);
    }

    @Test
    @DisplayName("SAS-AUDIO-013: exactly the missed bytes are discarded, no more and no fewer")
    void theDiscardIsExact() {
        PlaybackSession session = sessionSkipping(1_000);
        // The number the arithmetic has to produce, not the field read back from it: one second
        // at 44.1 kHz stereo 16-bit. Reading the field would agree with a wrong computation.
        long budget = BYTES_PER_SECOND;
        // Fed in uneven pieces, because the decoders do not hand over round numbers.
        feed(session, 1_000);
        feed(session, 100_000);
        feed(session, 100_000);
        // One byte too few leaves a click of the old position; one too many clips the sound.
        assertThat(written.stream().mapToLong(Integer::longValue).sum())
                .isEqualTo(201_000 - budget);
        assertThat(session.skipBytes).isZero();
    }

    @Test
    @DisplayName("SAS-AUDIO-013: an offset longer than the sound is folded into one pass")
    void anOffsetBeyondThePassIsFoldedModuloIt() {
        // Two hours into a two-second loop. Discarding two hours of audio a buffer at a time
        // would pin the audio thread for minutes before the first sample.
        PlaybackSession session = sessionSkipping(2 * 60 * 60 * 1_000);
        feed(session, 2 * BYTES_PER_SECOND);
        session.endOfPass();

        assertThat(written).as("the whole first pass was inside the offset").isEmpty();
        assertThat(session.skipBytes)
                .as("what is left must be inside one pass, not two hours of it")
                .isLessThan(2L * BYTES_PER_SECOND);
    }

    @Test
    @DisplayName("SAS-AUDIO-013: a pass that produced nothing does not divide by zero")
    void anEmptyPassLeavesTheBudgetAlone() {
        PlaybackSession session = sessionSkipping(1_000);
        // The budget has to be sized first, or skipBytes is 0 and endOfPass short-circuits on
        // its own -- which is what this test used to do, so it could not see the guard at all.
        // A zero-length buffer is what a decoder hands over at the end of a stream.
        feed(session, 0);
        long before = session.skipBytes;
        assertThat(before).as("the budget must be real, or the guard below is never reached")
                .isPositive();

        session.endOfPass();

        // Folding modulo zero throws, on the audio thread, which is the one place an exception
        // goes unseen.
        assertThat(session.skipBytes).isEqualTo(before);
    }

    /** A session whose metadata arrived `ago` milliseconds back, the way a real transfer does. */
    private static PlaybackSession announced(int offsetMillis, long ago, boolean synchronised) {
        return new PlaybackSession(POS, 1L, null, null, true, new int[6], false,
                offsetMillis, synchronised, System.currentTimeMillis() - ago);
    }

    @Test
    @DisplayName("SAS-AUDIO-013: the time the download took is added to the server's offset")
    void theTransferTimeIsAddedToTheOffset() {
        // The server measures the offset when it sends; the client cannot play until the whole
        // file has arrived, and for a player who has just joined that competes with terrain
        // download. Measured on a live server: ten to fifteen seconds behind everyone else.
        long used = announced(30_550, 12_000, true).effectiveOffsetMillis();

        assertThat(used)
                .as("the offset alone would leave this listener a transfer behind")
                .isGreaterThanOrEqualTo(30_550 + 12_000);
        // An upper bound as well, or "add a minute" would pass: the correction is the elapsed
        // time, not an arbitrary padding.
        assertThat(used).isLessThan(30_550 + 12_000 + 5_000);
    }

    @Test
    @DisplayName("SAS-AUDIO-013: the player who started the sound still hears its opening")
    void aListenerPresentFromTheStartIsNotCorrected() {
        // Correcting them too would be perfect synchronisation and would also mean nobody ever
        // hears the start of anything: each listener would skip their own transfer, and on a
        // short announcement that is most of it.
        assertThat(announced(0, 9_000, true).effectiveOffsetMillis())
                .as("a delivery that was not late has nothing to catch up on")
                .isZero();
    }

    @Test
    @DisplayName("SAS-AUDIO-013: a preview is not corrected and starts at the top")
    void anUnsynchronisedSoundIsNotCorrected() {
        // The recording screen's preview is a check on the medium in your hand. Skipping into
        // it by however long it took to arrive would cut off the start of what you just made.
        assertThat(announced(0, 9_000, false).effectiveOffsetMillis()).isZero();
    }
}
