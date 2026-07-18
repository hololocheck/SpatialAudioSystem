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
}
