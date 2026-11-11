package com.spatialaudiosystem.blockentity;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Which chunks the range renderer walks: those of the client level's devices that show their range, found through the
 * device's own lifecycle ({@code setLevel} / {@code setRemoved}) - the calls LevelChunk makes - and never a device of the
 * server or of another level.
 */
class ShownRangeDevicesTest {

    private static Level level(boolean client) {
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(client);
        return level;
    }

    /** A device at {@code pos} put into {@code level} the way LevelChunk.setBlockEntity does: setLevel, clearRemoved. */
    private static PlaybackDeviceBlockEntity placed(Level level, BlockPos pos, boolean showRange) {
        PlaybackDeviceBlockEntity device = TestDevices.newBare();
        set(BlockEntity.class, device, "worldPosition", pos);
        set(PlaybackDeviceBlockEntity.class, device, "showRange", showRange);
        device.setLevel(level);
        device.clearRemoved();
        return device;
    }

    private static void set(Class<?> owner, Object target, String field, Object value) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set " + field, e);
        }
    }

    @Test
    @DisplayName("a client device that shows its range names its chunk; one that does not, nothing")
    void shownDeviceNamesItsChunk() {
        Level client = level(true);
        placed(client, new BlockPos(40, 64, -20), true);
        placed(client, new BlockPos(-100, 64, 300), false);

        LongSet chunks = ShownRangeDevices.chunksShowing(client);

        assertThat(chunks).as("the shown device's chunk, and only it").containsExactly(ChunkPos.asLong(2, -2));
    }

    @Test
    @DisplayName("no device showing its range: null, so the renderer walks no chunk")
    void noneShowingIsNull() {
        Level client = level(true);
        placed(client, new BlockPos(0, 64, 0), false);

        assertThat(ShownRangeDevices.chunksShowing(client)).isNull();
    }

    @Test
    @DisplayName("a server device is never tracked, and a device of another level is not this level's")
    void serverAndOtherLevelDevicesAreNotThisLevels() {
        Level server = level(false);
        placed(server, new BlockPos(16, 64, 16), true);
        Level otherClient = level(true);
        placed(otherClient, new BlockPos(32, 64, 32), true);
        Level client = level(true);

        assertThat(ShownRangeDevices.chunksShowing(server)).as("the server's devices").isNull();
        assertThat(ShownRangeDevices.chunksShowing(client)).as("another client level's device").isNull();
    }

    @Test
    @DisplayName("a removed device leaves; put back into a chunk it is found again")
    void removedLeavesAndReturns() {
        Level client = level(true);
        PlaybackDeviceBlockEntity device = placed(client, new BlockPos(5, 64, 5), true);

        device.setRemoved();
        assertThat(ShownRangeDevices.chunksShowing(client)).as("after setRemoved").isNull();

        device.setLevel(client);
        device.clearRemoved();
        assertThat(ShownRangeDevices.chunksShowing(client)).as("after setBlockEntity again")
                .containsExactly(ChunkPos.asLong(0, 0));
    }

    @Test
    @DisplayName("showRange turned on after the device loaded is seen on the next frame")
    void showRangeChangedLaterIsSeen() {
        Level client = level(true);
        PlaybackDeviceBlockEntity device = placed(client, new BlockPos(-1, 64, -1), false);
        assertThat(ShownRangeDevices.chunksShowing(client)).isNull();

        set(PlaybackDeviceBlockEntity.class, device, "showRange", true);   // what the update tag's loadAdditional does

        assertThat(ShownRangeDevices.chunksShowing(client)).containsExactly(ChunkPos.asLong(-1, -1));
    }
}
