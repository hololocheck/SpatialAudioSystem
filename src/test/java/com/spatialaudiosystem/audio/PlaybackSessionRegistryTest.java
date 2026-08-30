package com.spatialaudiosystem.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The playback identity contract (SAS-AUDIO-005).
 *
 * <p>Before this, a playback was identified by its BlockPos alone. A report arriving
 * after the device had restarted ended the new sound; the same coordinates in two
 * dimensions were one playback; and TSU, which starts the next announcement the moment
 * it sees PlaybackEndedEvent, was a live consumer of exactly that confusion.
 */
class PlaybackSessionRegistryTest {

    private static final BlockPos POS = new BlockPos(10, 64, -30);

    private ServerLevel overworld;
    private ServerLevel nether;

    private static ServerLevel levelNamed(String path) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(
                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        ResourceLocation.fromNamespaceAndPath("minecraft", path)));
        return level;
    }

    @BeforeEach
    void setUp() {
        PlaybackSessionRegistry.clear();
        overworld = levelNamed("overworld");
        nether = levelNamed("the_nether");
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a report for the current sound is accepted exactly once")
    void completionIsAcceptedOnceAndOnlyOnce() {
        long id = PlaybackSessionRegistry.begin(overworld, POS);

        // Every client playing the sound reports it. Only one may end the playback.
        assertThat(PlaybackSessionRegistry.consumeIfCurrent(overworld, POS, id)).isTrue();
        assertThat(PlaybackSessionRegistry.consumeIfCurrent(overworld, POS, id))
                .as("a second client's report for the same sound must not fire again")
                .isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a late report cannot end the playback that replaced it")
    void staleReportDoesNotEndTheCurrentPlayback() {
        long first = PlaybackSessionRegistry.begin(overworld, POS);
        long second = PlaybackSessionRegistry.begin(overworld, POS);

        assertThat(second).as("a restart is a different sound").isNotEqualTo(first);

        // The first sound's client finally gets round to reporting. This is the TSU case:
        // the announcement after next must not be skipped.
        assertThat(PlaybackSessionRegistry.consumeIfCurrent(overworld, POS, first)).isFalse();
        assertThat(PlaybackSessionRegistry.currentId(overworld, POS))
                .as("the running sound survives the previous one's report")
                .isEqualTo(second);

        assertThat(PlaybackSessionRegistry.consumeIfCurrent(overworld, POS, second)).isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: the same coordinates in two dimensions are two sounds")
    void positionsInDifferentDimensionsDoNotCollide() {
        long overworldId = PlaybackSessionRegistry.begin(overworld, POS);
        long netherId = PlaybackSessionRegistry.begin(nether, POS);

        assertThat(PlaybackSessionRegistry.currentId(overworld, POS)).isEqualTo(overworldId);
        assertThat(PlaybackSessionRegistry.currentId(nether, POS)).isEqualTo(netherId);

        // Ending the Nether one must leave the Overworld one playing.
        assertThat(PlaybackSessionRegistry.consumeIfCurrent(nether, POS, netherId)).isTrue();
        assertThat(PlaybackSessionRegistry.currentId(overworld, POS)).isEqualTo(overworldId);
        assertThat(PlaybackSessionRegistry.consumeIfCurrent(overworld, POS, overworldId)).isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: a report naming a sound that was never started is refused")
    void invitedReportsOnly() {
        assertThat(PlaybackSessionRegistry.consumeIfCurrent(overworld, POS, 12345L))
                .as("nothing is playing here").isFalse();

        PlaybackSessionRegistry.begin(overworld, POS);
        assertThat(PlaybackSessionRegistry.consumeIfCurrent(overworld, POS, 12345L))
                .as("a guessed id must not end someone else's sound").isFalse();
        assertThat(PlaybackSessionRegistry.consumeIfCurrent(overworld, POS, PlaybackSessionRegistry.NO_PLAYBACK))
                .as("the sentinel is not a playback").isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: stopping forgets the sound, so its report is inert")
    void endMakesTheReportInert() {
        long id = PlaybackSessionRegistry.begin(overworld, POS);
        PlaybackSessionRegistry.end(overworld, POS);

        assertThat(PlaybackSessionRegistry.currentId(overworld, POS))
                .isEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);
        assertThat(PlaybackSessionRegistry.consumeIfCurrent(overworld, POS, id))
                .as("a sound stopped from the GUI must not also fire a completion")
                .isFalse();
    }

    // ===== who still needs this sound (SAS-AUDIO-009) =====
    //
    // A sound used to be sent once, to whoever was online at that instant. Someone joining,
    // arriving from another dimension or walking into range heard nothing, and the failure was
    // silence, so no other client could show it. The registry is the only thing that knows a
    // sound is still going, so it is what has to answer "who has not been sent this".

    private static final java.util.UUID ALICE = java.util.UUID.nameUUIDFromBytes("alice".getBytes());
    private static final java.util.UUID BOB = java.util.UUID.nameUUIDFromBytes("bob".getBytes());

    private static PlaybackSessionRegistry.Replay replay() {
        return new PlaybackSessionRegistry.Replay(
                net.minecraft.world.item.ItemStack.EMPTY, "ogg", null, null, true, new int[6], true);
    }

    @Test
    @DisplayName("SAS-AUDIO-009: an active sound is pending for a player who has not been sent it")
    void anActiveSoundIsPendingForSomeoneWhoLacksIt() {
        long id = PlaybackSessionRegistry.begin(overworld, POS, replay());

        assertThat(PlaybackSessionRegistry.pendingFor(overworld.dimension(), ALICE))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.pos()).isEqualTo(POS);
                    assertThat(p.playbackId()).isEqualTo(id);
                });
    }

    @Test
    @DisplayName("SAS-AUDIO-009: a sound stops being pending once the player has been sent it")
    void deliveryRemovesItFromPending() {
        long id = PlaybackSessionRegistry.begin(overworld, POS, replay());
        PlaybackSessionRegistry.markDelivered(overworld.dimension(), POS, id, ALICE);

        assertThat(PlaybackSessionRegistry.pendingFor(overworld.dimension(), ALICE)).isEmpty();
        assertThat(PlaybackSessionRegistry.pendingFor(overworld.dimension(), BOB))
                .as("delivery is per player, not per sound")
                .hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-009: marking against a replaced sound does not suppress the new one")
    void markingAStaleIdDoesNotSuppressTheCurrentSound() {
        long first = PlaybackSessionRegistry.begin(overworld, POS, replay());
        long second = PlaybackSessionRegistry.begin(overworld, POS, replay());
        assertThat(second).isNotEqualTo(first);

        // A delivery of the sound that has already been displaced arrives late. Accepting it
        // against the new sound would leave this player permanently without the one now playing.
        PlaybackSessionRegistry.markDelivered(overworld.dimension(), POS, first, ALICE);

        assertThat(PlaybackSessionRegistry.pendingFor(overworld.dimension(), ALICE))
                .singleElement()
                .satisfies(p -> assertThat(p.playbackId()).isEqualTo(second));
    }

    @Test
    @DisplayName("SAS-AUDIO-009: forgetting a player makes every active sound pending again")
    void forgettingAPlayerMakesSoundsPendingAgain() {
        long id = PlaybackSessionRegistry.begin(overworld, POS, replay());
        PlaybackSessionRegistry.markDelivered(overworld.dimension(), POS, id, ALICE);
        assertThat(PlaybackSessionRegistry.pendingFor(overworld.dimension(), ALICE)).isEmpty();

        // Logging out, changing dimension and respawning all unload the client level, and the
        // client forgets its sounds when that happens.
        PlaybackSessionRegistry.forgetPlayer(ALICE);

        assertThat(PlaybackSessionRegistry.pendingFor(overworld.dimension(), ALICE)).hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-009: pending is per dimension")
    void pendingIsPerDimension() {
        PlaybackSessionRegistry.begin(overworld, POS, replay());

        assertThat(PlaybackSessionRegistry.pendingFor(nether.dimension(), ALICE))
                .as("the same coordinates in another dimension are not this sound")
                .isEmpty();
        assertThat(PlaybackSessionRegistry.pendingFor(overworld.dimension(), ALICE)).hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-009: a sound registered without replay data is never pending")
    void aSoundWithoutReplayDataIsNeverPending() {
        // The recording screen's preview registers this way on purpose: it is a check on the
        // medium in your hand, not a sound placed in the world for others to walk into.
        PlaybackSessionRegistry.begin(overworld, POS);

        assertThat(PlaybackSessionRegistry.pendingFor(overworld.dimension(), ALICE)).isEmpty();
        assertThat(PlaybackSessionRegistry.currentId(overworld, POS))
                .as("it is still an active sound for every other purpose")
                .isNotEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);
    }

    @Test
    @DisplayName("SAS-AUDIO-009: an ended sound is pending for nobody")
    void anEndedSoundIsPendingForNobody() {
        PlaybackSessionRegistry.begin(overworld, POS, replay());
        PlaybackSessionRegistry.end(overworld, POS);

        assertThat(PlaybackSessionRegistry.pendingFor(overworld.dimension(), ALICE)).isEmpty();
        assertThat(PlaybackSessionRegistry.isEmpty()).isTrue();
    }
}
