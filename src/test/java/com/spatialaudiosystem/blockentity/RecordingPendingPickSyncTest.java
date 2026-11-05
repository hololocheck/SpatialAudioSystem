package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The recording device's screen shows the picked file from the block entity the client holds, which only an update
 * from the server changes (getUpdateTag carries the pick, a client load reads it back). Found 2026-11-05 on the real
 * client (BelugaAOS, the SAS guide's set): after Select File the screen kept showing the previous file, or none, until
 * a medium put into the device sent an update for its own reasons - setPendingAudio sent none, and neither did
 * clearPendingAudio, the Clear button's server half.
 *
 * <p>The tests run both on a real block entity on a mocked server level, and read the update the server would send with
 * a second block entity standing for the client's. Not looked at here: that sendBlockUpdated reaches the client as a
 * block entity data packet (vanilla - measured on the real client by the screen showing the file after the fix).
 */
class RecordingPendingPickSyncTest {

    private static final BlockPos AT = new BlockPos(66, -60, 48);
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private ServerLevel level;
    private BlockState state;
    private RecordingDeviceBlockEntity device;

    @BeforeEach
    void setUp() {
        level = mock(ServerLevel.class);
        state = ModBlocks.RECORDING_DEVICE.get().defaultBlockState();
        device = new RecordingDeviceBlockEntity(AT, state);
        device.setLevel(level);
    }

    /** What a client's block entity shows after reading the update the server would send now. */
    private String pickTheClientShows(RecordingDeviceBlockEntity client) {
        client.handleUpdateTag(device.getUpdateTag(REGISTRIES), REGISTRIES);
        return client.getPendingFileName();
    }

    @Test
    @DisplayName("a picked file is sent to the client, which then shows its name")
    void aPickedFileReachesTheClient() {
        RecordingDeviceBlockEntity client = new RecordingDeviceBlockEntity(AT, state);

        device.setPendingAudio(new byte[] {1, 2, 3}, "departure_chime.wav", "wav");

        verify(level).sendBlockUpdated(AT, state, state, 3);
        assertThat(pickTheClientShows(client)).isEqualTo("departure_chime.wav");
        assertThat(client.getPendingFormat()).isEqualTo("wav");
    }

    @Test
    @DisplayName("a cleared pick is sent to the client, which then shows none")
    void aClearedPickReachesTheClient() {
        RecordingDeviceBlockEntity client = new RecordingDeviceBlockEntity(AT, state);
        device.setPendingAudio(new byte[] {1, 2, 3}, "departure_chime.wav", "wav");
        assertThat(pickTheClientShows(client)).isEqualTo("departure_chime.wav");
        clearInvocations(level);

        device.clearPendingAudio();

        verify(level).sendBlockUpdated(AT, state, state, 3);
        assertThat(pickTheClientShows(client)).isNull();
    }
}
