package com.spatialaudiosystem.handy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The device tick's "did the handy row change" rule (spec §2.13, v2.4). */
class RowChangeDetectorTest {

    @Test
    @DisplayName("SAS-HANDY-023: the first signature is the baseline; only a later difference reports a change")
    void firstIsSilentThenDifferencesReport() {
        RowChangeDetector d = new RowChangeDetector();
        assertThat(d.primed()).isFalse();
        assertThat(d.offer("pb||")).as("first offer: nothing to compare with").isFalse();
        assertThat(d.primed()).isTrue();
        assertThat(d.offer("pb||")).as("unchanged").isFalse();
        assertThat(d.offer("Pb|song.wav|wav")).as("started playing with a medium").isTrue();
        assertThat(d.offer("Pb|song.wav|wav")).as("still the same").isFalse();
        assertThat(d.offer("pb|song.wav|wav")).as("the sound ended on its own").isTrue();
        assertThat(d.offer("pb||")).as("the medium was taken out").isTrue();
        assertThat(d.offer("pB||")).as("a board was slotted").isTrue();
    }
}
