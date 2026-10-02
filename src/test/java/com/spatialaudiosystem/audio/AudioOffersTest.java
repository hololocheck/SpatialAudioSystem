package com.spatialaudiosystem.audio;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAS-NET-009: the server sends a sound's bytes only to a player who answered that it needs them
 * (notes/CLIENT_AUDIO_CACHE.md §3).
 *
 * <p>Driven through the same static entry the payload handler calls, with the sender captured
 * instead of a network: what is pinned is which answers send, and how many times.
 */
class AudioOffersTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000b0b");
    private static final BlockPos POS = new BlockPos(12, 64, -3);
    private static final long ID = 0x5eedL;
    private static final byte[] AUDIO = {1, 2, 3, 4, 5};
    private static final long NOW = 1_000_000L;

    private final List<byte[]> sent = new ArrayList<>();

    @BeforeEach
    void clear() {
        AudioOffers.clearForTest();
        sent.clear();
    }

    private AudioOffers.Outcome answer(UUID player, long playbackId, boolean have, long at) {
        return AudioOffers.answer(player, POS, playbackId, have, at, sent::add);
    }

    @Test
    @DisplayName("SAS-NET-009: a player who needs the sound is sent it, once")
    void aNeedIsSentOnce() {
        AudioOffers.record(ALICE, POS, ID, AUDIO, NOW);
        assertThat(answer(ALICE, ID, false, NOW + 40)).isEqualTo(AudioOffers.Outcome.SENT);
        assertThat(answer(ALICE, ID, false, NOW + 80)).as("a repeated need").isEqualTo(AudioOffers.Outcome.IGNORED);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isEqualTo(AUDIO);
    }

    @Test
    @DisplayName("SAS-NET-009: a player who has the sound is sent nothing")
    void aHaveIsSentNothing() {
        AudioOffers.record(ALICE, POS, ID, AUDIO, NOW);
        assertThat(answer(ALICE, ID, true, NOW + 40)).isEqualTo(AudioOffers.Outcome.HIT);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("SAS-NET-009: a need after a have is the damaged-file fallback, sent once")
    void aLateNeedAfterAHaveIsSentOnce() {
        AudioOffers.record(ALICE, POS, ID, AUDIO, NOW);
        answer(ALICE, ID, true, NOW + 40);
        assertThat(answer(ALICE, ID, false, NOW + 90)).isEqualTo(AudioOffers.Outcome.SENT);
        assertThat(answer(ALICE, ID, false, NOW + 95)).isEqualTo(AudioOffers.Outcome.IGNORED);
        assertThat(sent).hasSize(1);
    }

    @Test
    @DisplayName("SAS-NET-009: after a have, the offer waits only a short grace for the late need")
    void aHaveShortensTheOffersLife() {
        AudioOffers.record(ALICE, POS, ID, AUDIO, NOW);
        answer(ALICE, ID, true, NOW + 40);
        // Well inside the minute an unanswered offer gets, but past the grace a have leaves: the
        // audio is not held for a player who said it did not need it.
        assertThat(answer(ALICE, ID, false, NOW + 40 + AudioOffers.HAVE_GRACE_MILLIS + 1))
                .isEqualTo(AudioOffers.Outcome.IGNORED);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("SAS-NET-009: an answer to nothing offered sends nothing")
    void anUnofferedAnswerSendsNothing() {
        assertThat(answer(ALICE, ID, false, NOW)).isEqualTo(AudioOffers.Outcome.IGNORED);
        AudioOffers.record(ALICE, POS, ID, AUDIO, NOW);
        // Another playback at the same place, and another player for the same playback.
        assertThat(answer(ALICE, ID + 1, false, NOW + 10)).isEqualTo(AudioOffers.Outcome.IGNORED);
        assertThat(answer(BOB, ID, false, NOW + 10)).isEqualTo(AudioOffers.Outcome.IGNORED);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("SAS-NET-009: an offer nobody answered in time is gone")
    void anExpiredOfferSendsNothing() {
        AudioOffers.record(ALICE, POS, ID, AUDIO, NOW);
        assertThat(answer(ALICE, ID, false, NOW + AudioOffers.OFFER_TTL_MILLIS + 1))
                .isEqualTo(AudioOffers.Outcome.IGNORED);
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("SAS-NET-009: each player's offer is its own")
    void eachPlayersOfferIsItsOwn() {
        AudioOffers.record(ALICE, POS, ID, AUDIO, NOW);
        AudioOffers.record(BOB, POS, ID, AUDIO, NOW);
        assertThat(answer(ALICE, ID, true, NOW + 5)).isEqualTo(AudioOffers.Outcome.HIT);
        assertThat(answer(BOB, ID, false, NOW + 5)).isEqualTo(AudioOffers.Outcome.SENT);
        assertThat(sent).hasSize(1);
    }
}
