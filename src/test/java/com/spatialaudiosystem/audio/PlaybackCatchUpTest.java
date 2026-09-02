package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.audio.AudioManager.PlaybackSession;
import com.spatialaudiosystem.network.CatchUpReportPayload;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /**
     * The clock the session reads, moved by hand. A real discard takes time and a test's
     * takes none, so the top-up -- which is that time -- would be invisible to a real clock.
     */
    private final long[] now = {1_000_000L};

    @BeforeEach
    void fakeClock() {
        PlaybackSession.clock = () -> now[0];
    }

    @AfterEach
    void realClock() {
        PlaybackSession.clock = System::currentTimeMillis;
    }

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
    private PlaybackSession announced(int offsetMillis, long ago, boolean synchronised) {
        return new PlaybackSession(POS, 1L, null, null, true, new int[6], false,
                offsetMillis, synchronised, now[0] - ago);
    }

    /** A late, synchronised listener whose metadata arrived just now, writing to the line. */
    private PlaybackSession sessionCatchingUp(int offsetMillis) {
        when(line.getFormat()).thenReturn(CD);
        when(line.write(any(), anyInt(), anyInt())).thenAnswer(call -> {
            written.add(call.getArgument(2));
            return call.<Integer>getArgument(2);
        });
        return new PlaybackSession(POS, 1L, null, null, true, new int[6], false,
                offsetMillis, true, now[0]);
    }

    @Test
    @DisplayName("SAS-AUDIO-014: the time the discard itself takes is discarded too")
    void theDiscardsOwnDurationIsDiscarded() {
        // Measured on a live server on 2026-09-02: the sized budget put the listener at the
        // right point as of the moment it was sized, and then decoding half a minute to throw
        // it away took real time, which the sound did not wait for.
        PlaybackSession session = sessionCatchingUp(1_000);
        feed(session, BYTES_PER_SECOND / 2);
        now[0] += 500;
        feed(session, BYTES_PER_SECOND / 2);
        assertThat(written).as("the budget is spent, but spending it took half a second").isEmpty();
        feed(session, BYTES_PER_SECOND / 2);
        assertThat(written).as("that half second is missed audio too").isEmpty();
        feed(session, 4_000);
        assertThat(written).containsExactly(4_000);
        assertThat(session.usedMillis).as("sized at one second, topped up by half").isEqualTo(1_500);
        assertThat(session.topUps).isEqualTo(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-014: a top-up that lands inside a buffer plays only what follows it")
    void aTopUpInsideABufferPlaysOnlyItsTail() {
        PlaybackSession session = sessionCatchingUp(1_000);
        feed(session, 1_000);
        now[0] += 500;
        // One buffer holding the rest of the budget, the whole top-up, and four thousand
        // bytes more. Stopping the discard at the first exhaustion would play the top-up.
        feed(session, (BYTES_PER_SECOND - 1_000) + BYTES_PER_SECOND / 2 + 4_000);
        assertThat(written).containsExactly(4_000);
    }

    @Test
    @DisplayName("SAS-AUDIO-014: a decoder slower than the sound starts late rather than never")
    void aSlowDecoderStartsLateRatherThanNever() {
        PlaybackSession session = sessionCatchingUp(1_000);
        feed(session, 0);
        // Every round the discard takes half as long again as the audio it discards, so each
        // top-up is larger than the last. Unbounded, this listener would never start.
        for (int round = 0; round < PlaybackSession.MAX_TOP_UPS + 2; round++) {
            long budget = session.skipBytes;
            assertThat(budget).as("round " + round + " has a budget to spend").isPositive();
            now[0] += budget * 1_000 / BYTES_PER_SECOND * 3 / 2;
            feed(session, (int) budget);
            if (session.skipBytes == 0) break;
        }
        feed(session, 4_000);
        assertThat(written).as("bounded: the listener starts, late").containsExactly(4_000);
        assertThat(session.topUps).isEqualTo(PlaybackSession.MAX_TOP_UPS);
    }

    @Test
    @DisplayName("SAS-AUDIO-014: a listener present from the start is never topped up")
    void aListenerPresentFromTheStartIsNotToppedUp() {
        PlaybackSession session = sessionCatchingUp(0);
        feed(session, 4_096);
        now[0] += 5_000;
        feed(session, 4_096);
        assertThat(written).as("no budget, so nothing to top up, whatever the clock did")
                .containsExactly(4_096, 4_096);
    }

    @Test
    @DisplayName("SAS-AUDIO-014: a preview is not topped up either")
    void aPreviewIsNotToppedUp() {
        // Unsynchronised with an offset: the shape a preview would have if it ever carried
        // one. The correction is off for it, and so is the top-up that belongs to it.
        PlaybackSession session = sessionSkipping(1_000);
        feed(session, BYTES_PER_SECOND / 2);
        now[0] += 500;
        feed(session, BYTES_PER_SECOND / 2 + 4_000);
        assertThat(written).containsExactly(4_000);
        assertThat(session.topUps).isZero();
    }

    @Test
    @DisplayName("SAS-AUDIO-014: the server is told the offset as finally used, once")
    void theReportCarriesTheOffsetAsFinallyUsed() {
        try (MockedStatic<PacketDistributor> wire = mockStatic(PacketDistributor.class)) {
            PlaybackSession session = sessionCatchingUp(1_000);
            feed(session, BYTES_PER_SECOND / 2);
            now[0] += 500;
            feed(session, BYTES_PER_SECOND / 2);
            // Reporting at sizing would say one second; the listener really used one and a
            // half, and the server's log is where the two halves of the story are compared.
            wire.verify(() -> PacketDistributor.sendToServer(any(CatchUpReportPayload.class)), never());

            feed(session, BYTES_PER_SECOND / 2 + 4_000);
            ArgumentCaptor<CatchUpReportPayload> report = ArgumentCaptor.forClass(CatchUpReportPayload.class);
            wire.verify(() -> PacketDistributor.sendToServer(report.capture()));
            assertThat(report.getValue().usedMillis()).isEqualTo(1_500);
            assertThat(report.getValue().skipBytes())
                    .as("what was discarded, top-up included")
                    .isEqualTo(BYTES_PER_SECOND + BYTES_PER_SECOND / 2L);

            feed(session, 4_000);
            wire.verify(() -> PacketDistributor.sendToServer(any(CatchUpReportPayload.class)), times(1));
        }
    }

    @Test
    @DisplayName("SAS-AUDIO-014: every decoder reports when its line closes, however it closed")
    void everyDecoderReportsWhenTheLineCloses() throws Exception {
        // runOnLine needs a real output line, so the rule is read rather than run: the report
        // sits in the finally that closes the line, ahead of the close, for all three decoders
        // at once. The OGG loop has no path to endOfPass on a stop, so without this a listener
        // stopped mid-catch-up left no line in the server's log -- review, 2026-09-02.
        java.nio.file.Path src = null;
        for (java.nio.file.Path base = java.nio.file.Paths.get("").toAbsolutePath();
             base != null; base = base.getParent()) {
            java.nio.file.Path c = base.resolve(
                    "src/main/java/com/spatialaudiosystem/audio/AudioManager.java");
            if (java.nio.file.Files.isRegularFile(c)) { src = c; break; }
        }
        assertThat(src).as("source not found from " + java.nio.file.Paths.get("").toAbsolutePath())
                .isNotNull();
        String text = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);
        int open = text.indexOf("private void runOnLine(");
        assertThat(open).isPositive();
        String method = text.substring(open, text.indexOf("private void streamMp3(", open));
        int finallyAt = method.indexOf("} finally {");
        assertThat(finallyAt).isPositive();
        String closing = method.substring(finallyAt);
        // The whole statement, predicate included. Presence and order alone were satisfied
        // by "if (false) session.reportOnce();" -- the second reading found the recorded run
        // in which only a tripwire on that literal went red -- and any other predicate that
        // never holds would have passed the same way.
        String statement = "if (session.skipSized) session.reportOnce();";
        assertThat(closing.indexOf(statement))
                .as("a sized session reports in the finally, before the line is stopped")
                .isPositive()
                .isLessThan(closing.indexOf("line.stop()"));
        // "Every decoder" holds only while every decoder opens its line through runOnLine.
        for (String decoder : new String[]{"streamMp3", "streamOgg", "streamWav"}) {
            int at = text.indexOf("private void " + decoder + "(");
            assertThat(at).as(decoder).isPositive();
            String body = text.substring(at, text.indexOf("\n    private ", at + 1));
            assertThat(body).as(decoder + " writes through runOnLine").contains("runOnLine(session,");
            assertThat(body).as(decoder + " opens no line of its own").doesNotContain("AudioSystem.getLine(");
        }
        // And file-wide, so a fourth decoder that opened its own line would go red here without
        // anyone remembering to add its name above: runOnLine's is the only line ever opened.
        assertThat(text.split("AudioSystem\\.getLine\\(", -1).length - 1)
                .as("exactly one place opens a line, and it is runOnLine")
                .isEqualTo(1);
        assertThat(text).doesNotContain("getSourceDataLine(");
    }

    @Test
    @DisplayName("SAS-AUDIO-014: a pass that stays inside the offset still reports")
    void anUnheardPassStillReports() {
        try (MockedStatic<PacketDistributor> wire = mockStatic(PacketDistributor.class)) {
            // A one-second sound, ten seconds late: nothing of it is audible, and the server
            // still gets its line, or a silent listener and a listener who never received the
            // sound would look the same in the log.
            PlaybackSession session = sessionCatchingUp(10_000);
            feed(session, BYTES_PER_SECOND);
            session.endOfPass();
            wire.verify(() -> PacketDistributor.sendToServer(any(CatchUpReportPayload.class)));
        }
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
