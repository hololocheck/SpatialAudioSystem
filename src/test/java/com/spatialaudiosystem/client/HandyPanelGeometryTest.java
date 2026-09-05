package com.spatialaudiosystem.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The handy panel's on-screen rectangle: the bottom-right pivot the screen draws with (spec §2.13, v2.4). */
class HandyPanelGeometryTest {

    @Test
    @DisplayName("SAS-HANDY-024: at scale 1 the rectangle is the panel itself; scaled, it keeps its bottom-right corner")
    void bottomRightPivot() {
        assertThat(HandyPanelGeometry.screenRect(100, 200, 220, 360, 1f)).containsExactly(100, 200, 220, 360);
        int[] half = HandyPanelGeometry.screenRect(100, 200, 220, 360, 0.5f);
        assertThat(half).containsExactly(210, 380, 110, 180);
        assertThat(half[0] + half[2]).as("right edge unchanged").isEqualTo(320);
        assertThat(half[1] + half[3]).as("bottom edge unchanged").isEqualTo(560);
    }
}
