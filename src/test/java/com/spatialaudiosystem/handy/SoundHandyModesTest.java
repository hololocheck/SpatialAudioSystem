package com.spatialaudiosystem.handy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SAS-HANDY-001: the wheel arithmetic the handy's Alt+wheel and Shift+wheel run on. */
class SoundHandyModesTest {

    @Test
    @DisplayName("SAS-HANDY-001: Shift+wheel walks the device list with wrap, lands on an end from nothing, stays unselected when empty")
    void selectionCyclesWithWrap() {
        assertThat(SoundHandyModes.cycleSelection(0, 1, 3)).isEqualTo(1);
        assertThat(SoundHandyModes.cycleSelection(2, 1, 3)).as("wraps to the first").isEqualTo(0);
        assertThat(SoundHandyModes.cycleSelection(0, -1, 3)).as("wraps to the last").isEqualTo(2);
        assertThat(SoundHandyModes.cycleSelection(-1, 1, 3)).as("nothing selected, scrolling down: the first").isEqualTo(0);
        assertThat(SoundHandyModes.cycleSelection(-1, -1, 3)).as("nothing selected, scrolling up: the last").isEqualTo(2);
        assertThat(SoundHandyModes.cycleSelection(5, 1, 3)).as("a selection past the list counts as none").isEqualTo(0);
        assertThat(SoundHandyModes.cycleSelection(0, 1, 0)).as("no devices: nothing to select").isEqualTo(-1);
        assertThat(SoundHandyModes.cycleSelection(0, 1, 1)).as("one device stays selected").isEqualTo(0);
    }
}
