package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.redstone.RedstoneOutputPlan;
import com.spatialaudiosystem.redstone.RedstoneRule;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * Bare playback devices for tests.
 *
 * <p>Objenesis skips the constructor and every field initialiser, so a device built with it has
 * null where the class declares {@code new ArrayList<>()} or {@code new RedstoneOutputPlan()}.
 * Each test sets the fields it drives; the ones every playback path touches -- the redstone
 * plan and its rule list, fed on every start and stop since 2026-09-03 -- are set here once,
 * so a test about something else does not fail on a null in the redstone hook.
 */
public final class TestDevices {

    private TestDevices() {}

    /** A device with no world, no inventory and no level: only what every path needs. */
    public static PlaybackDeviceBlockEntity newBare() {
        PlaybackDeviceBlockEntity d = new ObjenesisStd().newInstance(PlaybackDeviceBlockEntity.class);
        set(d, "redstoneRules", new ArrayList<RedstoneRule>());
        set(d, "redstonePlan", new RedstoneOutputPlan());
        return d;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = PlaybackDeviceBlockEntity.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set " + field, e);
        }
    }
}
