package com.spatialaudiosystem.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The playback safety-net timeout contract (SAS-AUDIO-006, SAS-LIFE-010).
 *
 * <p>The block entity is built without its constructor so the test does not need the
 * mod's registries. {@code tick} only reads the {@code Level} it is handed, and the
 * entity's own {@code level} field stays null, which makes {@code stopPlayback}'s
 * broadcast a no-op.
 */
class PlaybackTimeoutP1Test {

    private static final long TIMEOUT_TICKS = 12_000L;

    private static PlaybackDeviceBlockEntity newDevice() {
        return new ObjenesisStd().newInstance(PlaybackDeviceBlockEntity.class);
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

    private static Level levelAtGameTime(long gameTime) {
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getGameTime()).thenReturn(gameTime);
        return level;
    }

    @Test
    @DisplayName("SAS-LIFE-010: a playback restored from disk with no start time is cleaned up")
    void playbackRestoredWithoutAStartTimeIsCleanedUp() {
        PlaybackDeviceBlockEntity device = newDevice();

        // saveAdditional writes isPlaying but not playbackStartTick, so a world saved
        // mid-sound reloads as "playing" with a start time of 0. No client is rendering
        // that sound any more, so the timeout is what clears the phantom.
        set(device, "isPlaying", true);

        PlaybackDeviceBlockEntity.tick(levelAtGameTime(20_000L), BlockPos.ZERO, null, device);

        assertThat(device.isPlaying())
                .as("a playback with no start time cannot be real and must not stick")
                .isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-006: a correctly stamped playback runs until the timeout")
    void freshlyStampedPlaybackKeepsRunning() {
        PlaybackDeviceBlockEntity device = newDevice();
        set(device, "isPlaying", true);
        set(device, "playbackStartTick", 19_000L);

        PlaybackDeviceBlockEntity.tick(levelAtGameTime(20_000L), BlockPos.ZERO, null, device);

        assertThat(device.isPlaying())
                .as("1,000 ticks in is well inside the %d tick budget", TIMEOUT_TICKS)
                .isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-006: the safety-net timeout still fires when genuinely exceeded")
    void timeoutStillStopsRunawayPlayback() {
        PlaybackDeviceBlockEntity device = newDevice();
        set(device, "isPlaying", true);
        set(device, "playbackStartTick", 1_000L);

        // 19,000 ticks elapsed. Pinned so a fix for the test above cannot just delete the timeout.
        PlaybackDeviceBlockEntity.tick(levelAtGameTime(20_000L), BlockPos.ZERO, null, device);

        assertThat(device.isPlaying())
                .as("playback past the %d tick budget must be stopped", TIMEOUT_TICKS)
                .isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-006: an endless single medium is not stopped by the safety-net timeout")
    void theEndlessSingleMediumOutlivesTheTimeout() {
        PlaybackDeviceBlockEntity device = newDevice();
        set(device, "isPlaying", true);
        set(device, "playbackStartTick", 1_000L);
        set(device, "normalLoop", true);
        set(device, "playingSingle", true);
        set(device, "loopingEntry", -1);          // no schedule entry is armed; this is the slot
        set(device, "nextLoopArmTick", Long.MAX_VALUE);   // do not try to restart it in this test
        ItemStackHandler slots = new ItemStackHandler(8);
        slots.setStackInSlot(0, new ItemStack(net.minecraft.world.item.Items.PAPER));
        set(device, "inventory", slots);

        // Far past the budget: the timeout would have fired several times over.
        PlaybackDeviceBlockEntity.tick(levelAtGameTime(200_000L), BlockPos.ZERO, null, device);

        // The whole point of the endless button. Before 2026-08-30 nothing armed the single
        // medium, so the timeout below reached it and "endless" ended at ten minutes -- with the
        // button still lit, because a stop clears isPlaying and never the flag.
        assertThat(device.isPlaying())
                .as("an endless sound has no end for the timeout to be a safety net for")
                .isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-006: the endless flag alone does not exempt a stopped device")
    void theEndlessFlagAloneDoesNotArmAnything() {
        PlaybackDeviceBlockEntity device = newDevice();
        set(device, "isPlaying", true);
        set(device, "playbackStartTick", 1_000L);
        set(device, "normalLoop", true);
        set(device, "playingSingle", true);
        set(device, "loopingEntry", -1);
        // Slot empty: the button is on but there is nothing to play.
        set(device, "inventory", new ItemStackHandler(8));

        PlaybackDeviceBlockEntity.tick(levelAtGameTime(200_000L), BlockPos.ZERO, null, device);

        // Without reading the slot, a device left with the button on would claim to be playing
        // for ever and start sounding on its own at the next tick.
        assertThat(device.isPlaying()).isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-006: a playlist track does not inherit the single medium's endless flag")
    void aScheduleTrackIsNotTheEndlessSingleMedium() {
        PlaybackDeviceBlockEntity device = newDevice();
        set(device, "isPlaying", true);
        set(device, "playbackStartTick", 1_000L);
        set(device, "normalLoop", true);
        set(device, "loopingEntry", -1);
        // What is sounding came from the schedule, not the slot -- but the slot still holds a
        // medium, because schedule mode bars that slot without emptying it.
        set(device, "playingSingle", false);
        ItemStackHandler slots = new ItemStackHandler(8);
        slots.setStackInSlot(0, new ItemStack(net.minecraft.world.item.Items.PAPER));
        set(device, "inventory", slots);

        PlaybackDeviceBlockEntity.tick(levelAtGameTime(200_000L), BlockPos.ZERO, null, device);

        // Reading only the flag and the slot would suppress the runaway timeout for every
        // playlist track on such a device, and start the single medium by itself on restart.
        assertThat(device.isPlaying())
                .as("the endless button describes the single medium, not whatever is sounding")
                .isFalse();
    }
}
