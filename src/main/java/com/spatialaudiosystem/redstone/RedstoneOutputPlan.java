package com.spatialaudiosystem.redstone;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Turns a device's playback events into a redstone level per tick.
 *
 * <p>Pure arithmetic over ticks, so the rules can be checked without a level: the block entity
 * feeds it {@link Event}s as they happen and asks {@link #levelAt(long)} every server tick. The
 * answer is the strongest of every active rule -- a lamp rule that is on and a pulse that is
 * mid-flight do not add, the louder one wins, which is what a comparator would read.
 *
 * <p>Not persisted. After a reload the pulses in flight are gone and a level rule starts from
 * "not playing"; the device re-arms its loops a second later and the plan hears that start.
 */
public final class RedstoneOutputPlan {

    public enum Event { START, STOP, END }

    private List<RedstoneRule> rules = List.of();
    private boolean enabled;

    private boolean playing;
    private long playingSince = Long.MIN_VALUE;
    private long stoppedAt = Long.MIN_VALUE;
    // The entry scope. What plays now, since when; and the entry before it, for its off-delay.
    private int curEntry = RedstoneRule.ANY_ENTRY;
    private long curEntrySince = Long.MIN_VALUE;
    private int prevEntry = RedstoneRule.ANY_ENTRY;
    private long prevEntrySince = Long.MIN_VALUE;
    private long prevEntryEndedAt = Long.MIN_VALUE;
    /** {@code {firstTick, endTickExclusive, strength}} per pulse in flight. */
    private final List<long[]> pulses = new ArrayList<>();

    /** Replaces the rule set. A copy is kept, so the caller's list can keep changing. */
    public void setRules(List<RedstoneRule> newRules) {
        this.rules = List.copyOf(newRules);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Records an event at {@code tick}: the level rules follow it, the pulse rules fire on it.
     *
     * <p>A start within a tick of a stop is a continuation, not a new start: the schedule
     * advances with an end at one tick and the next track's start at the next, and a
     * supersede stops and starts in the same tick. Counting those as new starts made a
     * delayed lamp go dark between every track for the length of its delay (review,
     * 2026-09-03). A stop or an end with no start seen since this plan was made is not a
     * transition -- a device saved mid-playback is stopped by its timeout on the first
     * tick after a load -- so it fires nothing.
     */
    /** An event of no particular entry: the single medium, or a caller that does not know. */
    public void onEvent(Event event, long tick) {
        onEvent(event, tick, RedstoneRule.ANY_ENTRY);
    }

    /**
     * @param entry the schedule entry (1-based) that starts, stops or ends; ANY_ENTRY when it
     *              is the single medium. Unscoped rules see every event; a scoped rule only
     *              its own entry's.
     */
    public void onEvent(Event event, long tick, int entry) {
        if (event == Event.START) {
            boolean continuous = playing || (stoppedAt != Long.MIN_VALUE && tick - stoppedAt <= 1);
            if (!continuous) playingSince = tick;
            if (!playing || entry != curEntry) {
                // A new entry, or a fresh start. A running entry is closed here; a stopped one
                // was closed by its stop. The same entry starting again within a tick of its
                // stop (a repeat count) keeps its since-tick, or a delayed lamp would blink.
                if (playing) closeEntry(tick);
                boolean sameAgain = !playing && entry == prevEntry
                        && prevEntryEndedAt != Long.MIN_VALUE && tick - prevEntryEndedAt <= 1;
                curEntry = entry;
                curEntrySince = sameAgain ? prevEntrySince : tick;
            }
            playing = true;
        } else {
            if (!playing) return;
            playing = false;
            stoppedAt = tick;
            closeEntry(tick);
        }
        // Nothing queues while the output is off; re-enabling must not replay old events.
        if (!enabled) return;
        for (RedstoneRule rule : rules) {
            if (firesOn(rule.trigger(), event) && rule.matches(entry)) {
                long first = tick + rule.delayTicks();
                pulses.add(new long[]{first, first + rule.lengthTicks(), rule.strength()});
            }
        }
    }

    /**
     * Picks up a sound that was already playing when this plan was made -- a chunk reload
     * mid-sound -- without treating it as a start: the level rules follow it from the tick
     * it really started at, and no pulse rule fires (a reload is not a start; feeding a
     * START event here refired every start pulse on every reload -- review, 2026-09-03).
     */
    public void resume(long startedAtTick) {
        resume(startedAtTick, RedstoneRule.ANY_ENTRY);
    }

    /**
     * @param entry the schedule entry (1-based) the resumed sound is, as the device saved it;
     *              ANY_ENTRY for the single medium or when it is not known. A scoped lamp
     *              lights again for its own entry, with its delay counted from the real start.
     */
    public void resume(long startedAtTick, int entry) {
        playing = true;
        playingSince = startedAtTick;
        curEntry = entry;
        curEntrySince = startedAtTick;
    }

    private void closeEntry(long tick) {
        prevEntry = curEntry;
        prevEntrySince = curEntrySince;
        prevEntryEndedAt = tick;
        curEntry = RedstoneRule.ANY_ENTRY;
        curEntrySince = Long.MIN_VALUE;
    }

    private static boolean firesOn(RedstoneRule.Trigger trigger, Event event) {
        return switch (trigger) {
            case START -> event == Event.START;
            case STOP -> event == Event.STOP;
            case END -> event == Event.END;
            case PLAYING -> false;
        };
    }

    /** The redstone level, 0..15, the device should output at {@code tick}. */
    public int levelAt(long tick) {
        if (!enabled) {
            pulses.clear();
            return 0;
        }
        int level = 0;
        for (Iterator<long[]> it = pulses.iterator(); it.hasNext(); ) {
            long[] p = it.next();
            if (tick >= p[1]) {
                it.remove();
            } else if (tick >= p[0]) {
                level = Math.max(level, (int) p[2]);
            }
        }
        for (RedstoneRule rule : rules) {
            if (rule.trigger() != RedstoneRule.Trigger.PLAYING) continue;
            if (levelRuleOn(rule, tick)) level = Math.max(level, rule.strength());
        }
        return level;
    }

    /**
     * A level rule is on from {@code delay} after the start until {@code delay} after the stop
     * -- and never at all if the sound stopped before its delay had elapsed, so a short sound
     * with a long delay does not flash after the fact.
     */
    private boolean levelRuleOn(RedstoneRule rule, long tick) {
        if (rule.entry() != RedstoneRule.ANY_ENTRY) return scopedLevelRuleOn(rule, tick);
        if (playing) return tick >= playingSince + rule.delayTicks();
        if (stoppedAt == Long.MIN_VALUE) return false;
        boolean hadTurnedOn = stoppedAt >= playingSince + rule.delayTicks();
        return hadTurnedOn && tick < stoppedAt + rule.delayTicks();
    }

    /** Same edges as the unscoped lamp, measured from the rule's own entry's start and end. */
    private boolean scopedLevelRuleOn(RedstoneRule rule, long tick) {
        if (playing && curEntry == rule.entry()) return tick >= curEntrySince + rule.delayTicks();
        if (prevEntry != rule.entry() || prevEntryEndedAt == Long.MIN_VALUE) return false;
        boolean hadTurnedOn = prevEntryEndedAt >= prevEntrySince + rule.delayTicks();
        return hadTurnedOn && tick < prevEntryEndedAt + rule.delayTicks();
    }

    /** Forgets everything in flight; used when the device is loaded from disk. */
    public void reset() {
        playing = false;
        playingSince = Long.MIN_VALUE;
        stoppedAt = Long.MIN_VALUE;
        curEntry = RedstoneRule.ANY_ENTRY;
        curEntrySince = Long.MIN_VALUE;
        prevEntry = RedstoneRule.ANY_ENTRY;
        prevEntrySince = Long.MIN_VALUE;
        prevEntryEndedAt = Long.MIN_VALUE;
        pulses.clear();
    }

    /** How many pulses are still in flight; for tests and the debug log only. */
    int pendingPulses() {
        return pulses.size();
    }
}
