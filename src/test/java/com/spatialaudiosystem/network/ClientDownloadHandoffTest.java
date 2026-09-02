package com.spatialaudiosystem.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAS-NET-006: what a completed audio download hands to the player.
 *
 * <p>The server's half of "start a late listener where the sound has got to" was covered from
 * the day it was written; this half was not. Between the play payload arriving and
 * {@code AudioManager.playAudio} being called, the offset passes through a transfer session and
 * a nine-argument call, and replacing it with a literal zero anywhere along there left the whole
 * suite green — while every late listener started at the top, which is what a live test then
 * reported on 2026-08-30.
 *
 * <p>The transfer is driven for real, chunk by chunk, rather than by constructing the finished
 * shape: a session that never completes hands over nothing at all, and that is a way this can
 * fail which a directly-built fixture cannot see.
 */
class ClientDownloadHandoffTest {

    private static final BlockPos POS = new BlockPos(-106, -58, -6);
    private static final long PLAYBACK_ID = 0xb8e540dccfc8e85eL;
    /** As sent by the server to a player who joined six seconds in. */
    private static final int OFFSET_MS = 6_050;

    @BeforeEach
    void clear() {
        ClientAudioChunkPayload.clearAllSessions();
    }

    /** Announces a transfer the way ClientPlayAudioPayload.handle does. */
    private static void announce(int totalSize, boolean loop, int offsetMillis) {
        ClientAudioChunkPayload.prepareSession(POS, PLAYBACK_ID, totalSize, "wav",
                new BlockPos(1, 2, 3), new BlockPos(4, 5, 6),
                true, new int[]{8, 8, 8, 8, 8, 8}, loop, offsetMillis, true);
    }

    private static byte[] audio(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * 31);
        return data;
    }

    @Test
    @DisplayName("SAS-NET-006: the offset the server sent is what reaches the player")
    void theOffsetSurvivesTheTransfer() {
        byte[] data = audio(1_024);
        announce(data.length, false, OFFSET_MS);
        ClientAudioChunkPayload.deliverForTest(POS, PLAYBACK_ID, 0, 1, data);

        ClientAudioChunkPayload.Ready ready = ClientAudioChunkPayload.readyFor(POS);
        assertThat(ready).as("a complete transfer must be ready to play").isNotNull();
        // The number, not merely "non-zero": a fixed constant here would satisfy that and start
        // every listener at the same wrong place.
        assertThat(ready.startOffsetMillis()).isEqualTo(OFFSET_MS);
        assertThat(ready.audio()).isEqualTo(data);
        assertThat(ready.playbackId()).isEqualTo(PLAYBACK_ID);
    }

    @Test
    @DisplayName("SAS-NET-006: the endless flag survives the transfer too")
    void theEndlessFlagSurvivesTheTransfer() {
        for (boolean loop : new boolean[]{true, false}) {
            clear();
            byte[] data = audio(64);
            announce(data.length, loop, 0);
            ClientAudioChunkPayload.deliverForTest(POS, PLAYBACK_ID, 0, 1, data);
            assertThat(ClientAudioChunkPayload.readyFor(POS).loop()).isEqualTo(loop);
        }
    }

    @Test
    @DisplayName("SAS-NET-006: a transfer still arriving hands over nothing")
    void anIncompleteTransferIsNotReady() {
        byte[] half = audio(64);
        announce(half.length * 2, false, OFFSET_MS);
        ClientAudioChunkPayload.deliverForTest(POS, PLAYBACK_ID, 0, 2, half);

        // Playing a half-downloaded file is a decode error at best; the guard that prevents it
        // is the same one that decides whether the offset has arrived at all.
        assertThat(ClientAudioChunkPayload.readyFor(POS)).isNull();
    }

    @Test
    @DisplayName("SAS-NET-006: chunks of a replaced sound do not complete its successor")
    void chunksOfAReplacedSoundAreRefused() {
        byte[] data = audio(1_024);
        announce(data.length, false, OFFSET_MS);
        ClientAudioChunkPayload.deliverForTest(POS, PLAYBACK_ID + 1, 0, 1, data);

        assertThat(ClientAudioChunkPayload.readyFor(POS))
                .as("a chunk naming another sound must not fill this one's buffer")
                .isNull();
    }

    @Test
    @DisplayName("SAS-NET-006: when the transfer was announced is what reaches the player")
    void theAnnouncementTimeSurvivesTheTransfer() {
        byte[] data = audio(256);
        announce(data.length, false, OFFSET_MS);
        // A real transfer takes seconds and a test's takes none, so the gap has to be made.
        // Without it, handing over "now" instead of the announcement lands in the same
        // millisecond and nothing can tell the two apart -- measured 2026-08-30.
        ClientAudioChunkPayload.backdateForTest(POS, 12_000);
        ClientAudioChunkPayload.deliverForTest(POS, PLAYBACK_ID, 0, 1, data);

        // The catch-up adds the time between this instant and the first sample. Handing over
        // "now" instead would make that difference zero and put the listener back where the
        // live test found them: a whole transfer behind everyone else.
        long age = System.currentTimeMillis() - ClientAudioChunkPayload.readyFor(POS).announcedAtMillis();
        assertThat(age).isBetween(12_000L, 17_000L);
    }

    @Test
    @DisplayName("SAS-NET-006: whether the sound is synchronised survives the transfer")
    void theSyncFlagSurvivesTheTransfer() {
        for (boolean sync : new boolean[]{true, false}) {
            clear();
            byte[] data = audio(64);
            ClientAudioChunkPayload.prepareSession(POS, PLAYBACK_ID, data.length, "wav",
                    null, null, true, new int[]{8, 8, 8, 8, 8, 8}, false, OFFSET_MS, sync);
            ClientAudioChunkPayload.deliverForTest(POS, PLAYBACK_ID, 0, 1, data);
            // False here turns the correction off entirely, which is the preview's behaviour
            // applied to a world sound -- and is indistinguishable from the bug it fixed.
            assertThat(ClientAudioChunkPayload.readyFor(POS).synchronised()).isEqualTo(sync);
        }
    }

    @Test
    @DisplayName("SAS-NET-006: the catch-up report survives the wire")
    void theCatchUpReportRoundTrips() {
        CatchUpReportPayload sent = new CatchUpReportPayload(POS, PLAYBACK_ID, 13_950, 2_460_150L);

        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        net.minecraft.network.FriendlyByteBuf wire = new net.minecraft.network.FriendlyByteBuf(buf);
        CatchUpReportPayload.STREAM_CODEC.encode(wire, sent);
        CatchUpReportPayload back = CatchUpReportPayload.STREAM_CODEC.decode(wire);

        // The whole point is that these two numbers reach the server's log intact; a codec that
        // dropped one would make the report say nothing while still arriving.
        assertThat(back).isEqualTo(sent);
    }

    @Test
    @DisplayName("SAS-NET-006: a report cannot name a value outside its bounds")
    void theCatchUpReportIsBounded() {
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        net.minecraft.network.FriendlyByteBuf wire = new net.minecraft.network.FriendlyByteBuf(buf);
        wire.writeBlockPos(POS);
        wire.writeLong(PLAYBACK_ID);
        wire.writeVarInt(Integer.MAX_VALUE);
        // Negative on purpose: Long.MAX_VALUE is already non-negative, so a control that wrote
        // it could not tell the clamp from its absence -- measured green with the clamp deleted.
        wire.writeVarLong(-1L);

        CatchUpReportPayload back = CatchUpReportPayload.STREAM_CODEC.decode(wire);

        // It only reaches a log line, but a log line a peer can size is one a peer can flood.
        assertThat(back.usedMillis()).isLessThanOrEqualTo(ClientPlayAudioPayload.MAX_START_OFFSET_MILLIS);
        assertThat(back.skipBytes()).as("a negative byte count is clamped, not passed through").isZero();
    }

    @Test
    @DisplayName("SAS-NET-006: a loop change reaches a transfer that is still in flight")
    void aLoopChangeReachesATransferStillInFlight() {
        clear();
        byte[] data = audio(64);
        announce(data.length, true, 0);
        // The endless button goes off while this player is still downloading. There is no
        // playing session to change yet, so the only copy of the flag is the transfer's.
        com.spatialaudiosystem.audio.AudioManager.getInstance().setLoop(POS, PLAYBACK_ID, false);
        ClientAudioChunkPayload.deliverForTest(POS, PLAYBACK_ID, 0, 1, data);

        // Without this the change is lost for whoever was still downloading, and that player
        // starts the sound looping with no server session left to end it. The method that
        // carries it had no caller in any test (review, 2026-09-02).
        assertThat(ClientAudioChunkPayload.readyFor(POS).loop())
                .as("the transfer hands over the flag as it is now, not as it was announced")
                .isFalse();
    }
}
