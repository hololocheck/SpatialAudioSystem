package com.spatialaudiosystem.redstone;

import net.minecraft.nbt.CompoundTag;

/**
 * One redstone output rule of a playback device.
 *
 * <p>A device holds up to {@link #MAX_RULES} of these. {@link Trigger#PLAYING} is a level: the
 * output holds {@code strength} while the device plays, switching on {@code delayTicks} after
 * the start and off {@code delayTicks} after the stop or end. The other three triggers are
 * pulses: {@code delayTicks} after the event the output holds {@code strength} for
 * {@code lengthTicks}. When several rules are active at once the device outputs the strongest.
 *
 * <p>{@code entry} narrows a rule to one schedule entry (1-based); {@link #ANY_ENTRY} is
 * every start, stop and end, the single medium's included. A scoped rule only ever sees the
 * events of its own entry, so a lamp scoped to entry 3 is dark while entry 2 plays, and a
 * stop pulse scoped to entry 3 stays quiet when entry 2 is stopped.
 *
 * <p>Every field is clamped on construction, so a rule read from disk or from the wire is
 * always inside the bounds the screen can express.
 */
public record RedstoneRule(Trigger trigger, int strength, int delayTicks, int lengthTicks, int entry) {

    public enum Trigger {
        /** Level output for as long as the device is playing. */
        PLAYING,
        /** A pulse when a sound starts. */
        START,
        /** A pulse when the device is stopped by a player, redstone, the schedule or the API. */
        STOP,
        /** A pulse when a sound reaches its natural end. */
        END;

        public boolean isPulse() {
            return this != PLAYING;
        }

        /** The trigger {@code delta} steps away, wrapping at both ends (R4.13.0.8). */
        public Trigger cycle(int delta) {
            Trigger[] all = values();
            int n = all.length;
            return all[((ordinal() + delta) % n + n) % n];
        }
    }

    /** Rules per device: one per schedule entry, which is what the entry scope is for. */
    public static final int MAX_RULES = 16;
    /** The entry scope meaning "whatever plays". */
    public static final int ANY_ENTRY = 0;
    /**
     * The largest entry a rule can name: the playlist's capacity. Kept as a literal so this
     * package stays free of the block entity; RedstoneRulesTest pins the two together.
     */
    public static final int MAX_ENTRY = 16;
    public static final int MIN_STRENGTH = 1;
    public static final int MAX_STRENGTH = 15;
    /** Thirty seconds. Longer than any announcement lead-in; short enough to reason about. */
    public static final int MAX_DELAY_TICKS = 600;
    /** One wheel notch of delay: half a second. */
    public static final int DELAY_STEP_TICKS = 10;
    /** A pulse shorter than two ticks is not reliably seen by a repeater or a comparator. */
    public static final int MIN_LENGTH_TICKS = 2;
    /** Five seconds. */
    public static final int MAX_LENGTH_TICKS = 100;
    /** One wheel notch of pulse length: a tenth of a second. */
    public static final int LENGTH_STEP_TICKS = 2;

    public RedstoneRule {
        if (trigger == null) trigger = Trigger.PLAYING;
        strength = clampStrength(strength);
        delayTicks = clampDelay(delayTicks);
        lengthTicks = clampLength(lengthTicks);
        entry = clampEntry(entry);
    }

    /** An unscoped rule. */
    public RedstoneRule(Trigger trigger, int strength, int delayTicks, int lengthTicks) {
        this(trigger, strength, delayTicks, lengthTicks, ANY_ENTRY);
    }

    /** What a newly added rule is: a lamp that follows playback at full strength. */
    public static RedstoneRule defaults() {
        return new RedstoneRule(Trigger.PLAYING, MAX_STRENGTH, 0, 10);
    }

    public static int clampStrength(int v) {
        return Math.max(MIN_STRENGTH, Math.min(MAX_STRENGTH, v));
    }

    public static int clampDelay(int v) {
        return Math.max(0, Math.min(MAX_DELAY_TICKS, v));
    }

    public static int clampLength(int v) {
        return Math.max(MIN_LENGTH_TICKS, Math.min(MAX_LENGTH_TICKS, v));
    }

    public static int clampEntry(int v) {
        return Math.max(ANY_ENTRY, Math.min(MAX_ENTRY, v));
    }

    /** Whether an event of {@code entry} (1-based; ANY_ENTRY for the single medium) is this rule's. */
    public boolean matches(int entry) {
        return this.entry == ANY_ENTRY || this.entry == entry;
    }

    public RedstoneRule withTrigger(Trigger t) {
        return new RedstoneRule(t, strength, delayTicks, lengthTicks, entry);
    }

    public RedstoneRule withStrength(int v) {
        return new RedstoneRule(trigger, v, delayTicks, lengthTicks, entry);
    }

    public RedstoneRule withDelay(int v) {
        return new RedstoneRule(trigger, strength, v, lengthTicks, entry);
    }

    public RedstoneRule withLength(int v) {
        return new RedstoneRule(trigger, strength, delayTicks, v, entry);
    }

    /** The entry scope {@code delta} steps away, wrapping between ANY_ENTRY and MAX_ENTRY (R4.13.0.8). */
    public RedstoneRule cycleEntry(int delta) {
        int n = MAX_ENTRY + 1;
        return new RedstoneRule(trigger, strength, delayTicks, lengthTicks,
                ((entry + Integer.signum(delta)) % n + n) % n);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("trigger", trigger.ordinal());
        tag.putInt("strength", strength);
        tag.putInt("delay", delayTicks);
        tag.putInt("length", lengthTicks);
        tag.putInt("entry", entry);
        return tag;
    }

    /** Reads a rule; an unknown trigger ordinal (a newer save) falls back to PLAYING. */
    public static RedstoneRule load(CompoundTag tag) {
        int ord = tag.getInt("trigger");
        Trigger[] all = Trigger.values();
        Trigger t = ord >= 0 && ord < all.length ? all[ord] : Trigger.PLAYING;
        // A rule saved before entry scopes existed has no "entry": getInt gives 0 = ANY_ENTRY.
        return new RedstoneRule(t, tag.getInt("strength"), tag.getInt("delay"), tag.getInt("length"),
                tag.getInt("entry"));
    }
}
