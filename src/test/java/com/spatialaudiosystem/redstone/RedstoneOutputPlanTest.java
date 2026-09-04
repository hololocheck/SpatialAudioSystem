package com.spatialaudiosystem.redstone;

import com.spatialaudiosystem.redstone.RedstoneOutputPlan.Event;
import com.spatialaudiosystem.redstone.RedstoneRule.Trigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAS-RS-001: what the device puts on the wire, tick by tick, for a set of rules.
 *
 * <p>Pure arithmetic, so every rule is checked at the tick where it turns -- the tick before
 * and the tick after -- rather than "somewhere in the middle", where an off-by-one cannot be
 * seen. The block entity feeds the same events this test does.
 */
class RedstoneOutputPlanTest {

    private static RedstoneOutputPlan plan(RedstoneRule... rules) {
        RedstoneOutputPlan p = new RedstoneOutputPlan();
        p.setRules(List.of(rules));
        p.setEnabled(true);
        return p;
    }

    @Test
    @DisplayName("SAS-RS-001: a lamp rule follows playback: on at the start, off at the stop")
    void aLevelRuleFollowsPlayback() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 15, 0, 10));
        assertThat(p.levelAt(0)).as("nothing has happened").isZero();
        p.onEvent(Event.START, 100);
        assertThat(p.levelAt(100)).as("the tick of the start").isEqualTo(15);
        assertThat(p.levelAt(500)).isEqualTo(15);
        p.onEvent(Event.STOP, 600);
        assertThat(p.levelAt(600)).as("the tick of the stop").isZero();
        assertThat(p.levelAt(601)).isZero();
    }

    @Test
    @DisplayName("SAS-RS-001: a lamp rule's delay shifts both edges")
    void aLevelRulesDelayShiftsBothEdges() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 7, 40, 10));
        p.onEvent(Event.START, 100);
        assertThat(p.levelAt(139)).as("one tick before the delay elapses").isZero();
        assertThat(p.levelAt(140)).as("the delay has elapsed").isEqualTo(7);
        p.onEvent(Event.END, 300);
        assertThat(p.levelAt(339)).as("still on until the delay after the end").isEqualTo(7);
        assertThat(p.levelAt(340)).isZero();
    }

    @Test
    @DisplayName("SAS-RS-001: a sound shorter than the lamp's delay never turns it on")
    void aSoundShorterThanTheDelayNeverTurnsTheLampOn() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 15, 100, 10));
        p.onEvent(Event.START, 0);
        p.onEvent(Event.END, 50);
        // Without this a two-second chime with a five-second delay would light the lamp three
        // seconds after it went silent, for the length of the delay.
        assertThat(p.levelAt(60)).isZero();
        assertThat(p.levelAt(120)).isZero();
    }

    @Test
    @DisplayName("SAS-RS-001: a start pulse fires after its delay and lasts its length")
    void aStartPulseHasADelayAndALength() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.START, 12, 20, 6));
        p.onEvent(Event.START, 1_000);
        assertThat(p.levelAt(1_019)).as("before the delay").isZero();
        assertThat(p.levelAt(1_020)).as("first tick of the pulse").isEqualTo(12);
        assertThat(p.levelAt(1_025)).as("last tick of the pulse").isEqualTo(12);
        assertThat(p.levelAt(1_026)).as("the pulse is over").isZero();
        assertThat(p.pendingPulses()).as("a finished pulse is forgotten").isZero();
    }

    @Test
    @DisplayName("SAS-RS-001: stop and end pulses fire on their own event only")
    void stopAndEndPulsesFireOnTheirOwnEvent() {
        RedstoneOutputPlan p = plan(
                new RedstoneRule(Trigger.STOP, 5, 0, 4),
                new RedstoneRule(Trigger.END, 9, 0, 4));
        p.onEvent(Event.START, 0);
        assertThat(p.levelAt(0)).as("neither fires on a start").isZero();
        p.onEvent(Event.END, 100);
        assertThat(p.levelAt(100)).as("the end rule, not the stop rule").isEqualTo(9);
        p.onEvent(Event.START, 200);
        p.onEvent(Event.STOP, 300);
        assertThat(p.levelAt(300)).as("the stop rule, not the end rule").isEqualTo(5);
    }

    @Test
    @DisplayName("SAS-RS-001: overlapping rules output the strongest, not a sum")
    void overlappingRulesOutputTheStrongest() {
        RedstoneOutputPlan p = plan(
                new RedstoneRule(Trigger.PLAYING, 6, 0, 10),
                new RedstoneRule(Trigger.START, 11, 0, 10));
        p.onEvent(Event.START, 0);
        // A sum would be 17, which redstone cannot carry; the weaker rule alone would be 6.
        assertThat(p.levelAt(5)).isEqualTo(11);
        assertThat(p.levelAt(10)).as("the pulse is over, the lamp remains").isEqualTo(6);
    }

    @Test
    @DisplayName("SAS-RS-001: disabled output is silent and drops what was in flight")
    void disabledOutputIsSilent() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.START, 15, 0, 100));
        p.onEvent(Event.START, 0);
        assertThat(p.levelAt(1)).isEqualTo(15);
        p.setEnabled(false);
        assertThat(p.levelAt(2)).isZero();
        // Events while off queue nothing: without this a device left off with rules defined
        // grew one pulse per event forever, and re-enabling replayed them.
        p.onEvent(Event.STOP, 3);
        p.onEvent(Event.START, 4);
        assertThat(p.pendingPulses()).as("nothing queued while off").isZero();
        p.setEnabled(true);
        assertThat(p.levelAt(5)).as("re-enabling does not resurrect the old pulse").isZero();
    }

    @Test
    @DisplayName("SAS-RS-001: a restart within a tick keeps a delayed lamp on without a gap")
    void aRestartWithinATickKeepsTheLampOn() {
        // The schedule advances with END at one tick and the next track's START at the next
        // (PlaybackScheduler.onPlaybackEnded fires one tick later); a supersede stops and
        // starts in the same tick. Counted as a new start, a lamp with a delay went dark
        // between every track for the length of the delay -- the second reading found the
        // first version of this test feeding both events in one tick, which never happens.
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 15, 40, 10));
        p.onEvent(Event.START, 0);
        assertThat(p.levelAt(400)).isEqualTo(15);
        p.onEvent(Event.END, 500);
        p.onEvent(Event.START, 501);
        assertThat(p.levelAt(500)).isEqualTo(15);
        assertThat(p.levelAt(501)).as("the tick of the next track's start").isEqualTo(15);
        assertThat(p.levelAt(520)).as("inside what would have been the new delay").isEqualTo(15);
        p.onEvent(Event.STOP, 600);
        p.onEvent(Event.START, 600);
        assertThat(p.levelAt(600)).as("a supersede, same tick").isEqualTo(15);
    }

    @Test
    @DisplayName("SAS-RS-001: a start while already playing continues, it does not re-run the delay")
    void aStartWhilePlayingIsAContinuation() {
        // Play All over a playing one-shot starts the sequence without a stop (the scheduler
        // leaves the single slot alone). The device kept playing throughout, so a delayed
        // lamp must not go dark for the length of its delay.
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 15, 40, 10));
        p.onEvent(Event.START, 0);
        p.onEvent(Event.START, 300);
        assertThat(p.levelAt(301)).isEqualTo(15);
        assertThat(p.levelAt(339)).isEqualTo(15);
    }

    @Test
    @DisplayName("SAS-RS-001: a restart after a gap is a new start, delay and all")
    void aRestartAfterAGapIsANewStart() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 15, 40, 10));
        p.onEvent(Event.START, 0);
        p.onEvent(Event.END, 500);
        p.onEvent(Event.START, 510);
        assertThat(p.levelAt(540)).as("the old hold is over, the new delay is not").isZero();
        assertThat(p.levelAt(550)).isEqualTo(15);
    }

    @Test
    @DisplayName("SAS-RS-001: a stop or an end with no start seen fires no pulse")
    void aStopWithoutAStartFiresNoPulse() {
        // A device saved mid-playback is stopped by its timeout on the first tick after a
        // load; the plan was made at that load and saw no start. Not a transition.
        RedstoneOutputPlan p = plan(
                new RedstoneRule(Trigger.STOP, 15, 0, 10),
                new RedstoneRule(Trigger.END, 15, 0, 10));
        p.onEvent(Event.STOP, 10);
        p.onEvent(Event.END, 11);
        assertThat(p.levelAt(10)).isZero();
        assertThat(p.levelAt(11)).isZero();
        assertThat(p.pendingPulses()).isZero();
    }

    // ===== entry scopes (phase 2.5) =====

    @Test
    @DisplayName("SAS-RS-003: a lamp scoped to an entry is dark while another entry plays")
    void aScopedLampFollowsItsOwnEntry() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 15, 0, 10, 2));
        p.onEvent(Event.START, 100, 1);
        assertThat(p.levelAt(100)).as("entry 1 is not this lamp's").isZero();
        assertThat(p.levelAt(199)).isZero();
        p.onEvent(Event.STOP, 200, 1);
        p.onEvent(Event.START, 201, 2);
        assertThat(p.levelAt(201)).as("entry 2 started").isEqualTo(15);
        p.onEvent(Event.STOP, 300, 2);
        assertThat(p.levelAt(300)).as("entry 2 stopped").isZero();
    }

    @Test
    @DisplayName("SAS-RS-003: a pulse scoped to an entry ignores the other entries' events")
    void aScopedPulseIgnoresOtherEntries() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.STOP, 9, 0, 10, 3));
        p.onEvent(Event.START, 100, 2);
        p.onEvent(Event.STOP, 200, 2);
        assertThat(p.levelAt(200)).as("entry 2's stop").isZero();
        assertThat(p.pendingPulses()).isZero();
        p.onEvent(Event.START, 201, 3);
        p.onEvent(Event.STOP, 300, 3);
        assertThat(p.levelAt(300)).as("entry 3's stop").isEqualTo(9);
        assertThat(p.levelAt(310)).isZero();
    }

    @Test
    @DisplayName("SAS-RS-003: a scoped lamp's delay is measured from its own entry's start and end")
    void aScopedLampsDelayIsMeasuredFromItsEntry() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 7, 40, 10, 2));
        p.onEvent(Event.START, 100, 1);
        assertThat(p.levelAt(500)).as("entry 1 has played for ages; not this lamp's").isZero();
        p.onEvent(Event.STOP, 600, 1);
        p.onEvent(Event.START, 601, 2);
        assertThat(p.levelAt(640)).as("the tick before entry 2's delay is up").isZero();
        assertThat(p.levelAt(641)).as("entry 2's delay is up").isEqualTo(7);
        p.onEvent(Event.STOP, 700, 2);
        assertThat(p.levelAt(739)).as("the tick before the off-delay is up").isEqualTo(7);
        assertThat(p.levelAt(740)).isZero();
    }

    @Test
    @DisplayName("SAS-RS-003: the same entry starting again within a tick keeps its delayed lamp on")
    void aRepeatOfTheSameEntryKeepsADelayedScopedLampOn() {
        // A play count of two: the entry stops and starts again the next tick.
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 7, 40, 10, 1));
        p.onEvent(Event.START, 100, 1);
        assertThat(p.levelAt(200)).isEqualTo(7);
        p.onEvent(Event.STOP, 300, 1);
        p.onEvent(Event.START, 301, 1);
        assertThat(p.levelAt(301)).as("no blink at the repeat").isEqualTo(7);
        assertThat(p.levelAt(340)).isEqualTo(7);
        p.onEvent(Event.STOP, 400, 1);
        assertThat(p.levelAt(440)).isZero();
    }

    @Test
    @DisplayName("SAS-RS-003: an unscoped rule sees every entry, and the single medium")
    void anUnscopedRuleSeesEveryEntry() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 15, 0, 10));
        p.onEvent(Event.START, 100, 3);
        assertThat(p.levelAt(100)).isEqualTo(15);
        p.onEvent(Event.STOP, 200, 3);
        p.onEvent(Event.START, 201, RedstoneRule.ANY_ENTRY);
        assertThat(p.levelAt(201)).isEqualTo(15);
    }

    @Test
    @DisplayName("SAS-RS-003: a resumed plan lights the saved entry's lamp, and no other")
    void resumeWithTheSavedEntryLightsItsLamp() {
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 15, 0, 10, 1),
                new RedstoneRule(Trigger.PLAYING, 9, 0, 10, 2));
        p.resume(50, 1);
        assertThat(p.levelAt(100)).as("entry 1 was sounding when the chunk was saved").isEqualTo(15);
        p.onEvent(Event.STOP, 150, 1);
        assertThat(p.levelAt(150)).isZero();

        // Unknown entry (the single medium, or an older save): scoped lamps wait for a real start.
        RedstoneOutputPlan q = plan(new RedstoneRule(Trigger.PLAYING, 15, 0, 10, 1));
        q.resume(50);
        assertThat(q.levelAt(100)).as("resumed, entry unknown").isZero();
        q.onEvent(Event.STOP, 150, 1);
        q.onEvent(Event.START, 151, 1);
        assertThat(q.levelAt(151)).as("a real start of entry 1").isEqualTo(15);
    }

    @Test
    @DisplayName("SAS-RS-003: a resumed scoped lamp counts its delay from the real start")
    void aResumedScopedLampCountsItsDelayFromTheRealStart() {
        // The scoped twin of aResumedLampDoesNotReRunItsDelay: the saved start tick is the
        // entry's since-tick, so the delay is neither re-run from the reload nor skipped.
        RedstoneOutputPlan p = plan(new RedstoneRule(Trigger.PLAYING, 7, 40, 10, 1));
        p.resume(100, 1);
        assertThat(p.levelAt(139)).as("the tick before the delay is up").isZero();
        assertThat(p.levelAt(140)).as("the delay, counted from the real start").isEqualTo(7);
        p.onEvent(Event.STOP, 200, 1);
        assertThat(p.levelAt(239)).as("the off-delay, from the stop").isEqualTo(7);
        assertThat(p.levelAt(240)).isZero();

        // Resumed and stopped before the delay is up: the lamp never turned on, so no off-delay.
        RedstoneOutputPlan q = plan(new RedstoneRule(Trigger.PLAYING, 7, 40, 10, 1));
        q.resume(100, 1);
        q.onEvent(Event.STOP, 120, 1);
        assertThat(q.levelAt(120)).isZero();
        assertThat(q.levelAt(150)).as("a sound shorter than the delay never lit it").isZero();
    }

    @Test
    @DisplayName("SAS-RS-001: reset forgets the playback state and the pulses")
    void resetForgetsEverything() {
        RedstoneOutputPlan p = plan(
                new RedstoneRule(Trigger.PLAYING, 15, 0, 10),
                new RedstoneRule(Trigger.START, 15, 0, 100));
        p.onEvent(Event.START, 0);
        p.reset();
        assertThat(p.levelAt(1)).isZero();
        assertThat(p.pendingPulses()).isZero();
    }
}
