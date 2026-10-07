package com.spatialaudiosystem.client;

import static org.assertj.core.api.Assertions.assertThat;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The recording refusal is cleared only by the screen the player has open (second reading, 2026-10-07): the wiki's
 * stand-in of the memory device presses the buttons that clear it, and is never that screen.
 */
class RecordingErrorStateTest {

    @Test
    @DisplayName("a clear asked by a screen that is not the open one leaves the refusal showing")
    void onlyTheOpenScreenClearsTheRefusal() {
        BlockPos device = new BlockPos(3, 64, -7);
        Object open = new Object();
        Object standIn = new Object();
        RecordingErrorState.set(device, 2);
        RecordingErrorState.clear(standIn, open);
        assertThat(RecordingErrorState.reasonFor(device)).as("the stand-in asked: still showing").isEqualTo(2);
        RecordingErrorState.clear(null, null);
        assertThat(RecordingErrorState.reasonFor(device)).as("no requester: still showing").isEqualTo(2);
        RecordingErrorState.clear(open, open);
        assertThat(RecordingErrorState.reasonFor(device)).as("the open screen asked: cleared").isEqualTo(-1);
    }
}
