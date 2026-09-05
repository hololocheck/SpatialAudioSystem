package com.spatialaudiosystem.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAS-HANDY-004: a device screen opened through the handy (spec §2.5) stays open on the
 * handy and the ownership, not on distance - and closes the moment either goes.
 */
class HandyRemoteMenuTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    @DisplayName("SAS-HANDY-004: remote validity is the owner holding the handy over a device that still exists")
    void remoteValidityIsTheOwnerHoldingTheHandy() {
        assertThat(PlaybackDeviceMenu.remoteStillValid(false, true, ALICE, ALICE)).isTrue();
        assertThat(PlaybackDeviceMenu.remoteStillValid(false, true, BOB, ALICE)).as("not the owner").isFalse();
        assertThat(PlaybackDeviceMenu.remoteStillValid(false, false, ALICE, ALICE)).as("handy put away").isFalse();
        assertThat(PlaybackDeviceMenu.remoteStillValid(true, true, ALICE, ALICE)).as("device gone").isFalse();
        assertThat(PlaybackDeviceMenu.remoteStillValid(false, true, ALICE, null)).as("no owner yet").isFalse();
    }
}
