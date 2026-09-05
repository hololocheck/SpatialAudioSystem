package com.spatialaudiosystem.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The mini HUD's target switch: what slides, which way, and how far along (spec §2.13, v2.4). */
class HandyTargetSwitchTest {

    private static final long MS = 1_000_000L;

    @Test
    @DisplayName("SAS-HANDY-020: the first frame, a lost target and a found target do not slide; a change does")
    void whatSlides() {
        HandyTargetSwitch s = new HandyTargetSwitch();
        assertThat(s.offer(2, 0)).as("first frame is the baseline").isFalse();
        assertThat(s.offer(2, 10 * MS)).as("same target").isFalse();
        assertThat(s.offer(-1, 20 * MS)).as("target lost").isFalse();
        assertThat(s.offer(0, 30 * MS)).as("target found from none").isFalse();
        assertThat(s.progress(31 * MS)).as("nothing runs").isEqualTo(1f);
        assertThat(s.offer(1, 40 * MS)).as("one device to another").isTrue();
        assertThat(s.progress(40 * MS)).isEqualTo(0f);
    }

    @Test
    @DisplayName("SAS-HANDY-021: the index order gives the direction, and the wheel's hint overrides it across a wrap")
    void direction() {
        HandyTargetSwitch s = new HandyTargetSwitch();
        s.offer(0, 0);
        s.offer(1, 10 * MS);
        assertThat(s.direction()).as("later in the list = rows move up").isEqualTo(1);
        s.offer(0, 20 * MS);
        assertThat(s.direction()).as("earlier = rows move down").isEqualTo(-1);

        // Wheel "next" from the last device lands on the first: the index goes down, the wheel went up.
        s.offer(3, 30 * MS);
        s.hint(1, 40 * MS);
        assertThat(s.offer(0, 41 * MS)).isTrue();
        assertThat(s.direction()).as("the wheel's direction wins").isEqualTo(1);

        // A stale hint is not used.
        s.hint(1, 50 * MS);
        assertThat(s.offer(2, 50 * MS + HandyTargetSwitch.HINT_NANOS + MS)).isTrue();
        assertThat(s.direction()).as("index order again: 0 -> 2 is up regardless").isEqualTo(1);
        s.hint(-1, 60 * MS);
        s.offer(3, 60 * MS + HandyTargetSwitch.HINT_NANOS + MS);
        assertThat(s.direction()).as("stale 'previous' hint ignored: 2 -> 3 is up").isEqualTo(1);
    }

    @Test
    @DisplayName("SAS-HANDY-022: progress eases out over the slide and settles at 1")
    void progress() {
        HandyTargetSwitch s = new HandyTargetSwitch();
        s.offer(0, 0);
        s.offer(1, 100 * MS);
        float half = s.progress(100 * MS + HandyTargetSwitch.SLIDE_NANOS / 2);
        assertThat(half).as("ease-out: past the midpoint at half time").isGreaterThan(0.5f).isLessThan(1f);
        assertThat(s.progress(100 * MS + HandyTargetSwitch.SLIDE_NANOS)).isEqualTo(1f);
        assertThat(s.progress(100 * MS + 2 * HandyTargetSwitch.SLIDE_NANOS)).isEqualTo(1f);
        // Monotonic.
        float prev = 0f;
        for (long t = 0; t <= HandyTargetSwitch.SLIDE_NANOS; t += HandyTargetSwitch.SLIDE_NANOS / 10) {
            float p = s.progress(100 * MS + t);
            assertThat(p).isGreaterThanOrEqualTo(prev);
            prev = p;
        }
    }
}
