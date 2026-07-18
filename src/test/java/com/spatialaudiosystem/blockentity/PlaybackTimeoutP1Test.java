package com.spatialaudiosystem.blockentity;

import net.minecraft.core.BlockPos;
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
}
