package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.audio.AudioDuration;
import com.spatialaudiosystem.audio.AudioIdRegistry;
import com.spatialaudiosystem.block.ModBlocks;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SAS-AUDIO-015: a recording medium's length ({@code AUDIO_DURATION_SEC}) belongs to the sound the medium carries, and
 * goes when that sound goes.
 *
 * <p>Found 2026-10-07 on the real client: the memory device's clear (clear-audio, clearMediaAudioData) took the
 * medium's audio id, file name and format but left its length, so the screen read "no file selected" beside
 * "length: 0:32" and {@code SasApi.getAudioDurationSeconds} answered a length for a medium with no sound. The write
 * (finishRecording) had the same hole: a written medium put back in and written with a sound the device cannot time
 * kept the length of the sound it replaced.
 *
 * <p>The written media come out of the device's own write (tickRecording, finishRecording) on a mocked server whose
 * world is a temporary directory, so they carry what a real write leaves. The one hand-built medium is the shape the
 * old clear left behind.
 */
class RecordingMediumDurationTest {

    @TempDir
    Path worldRoot;

    private RecordingDeviceBlockEntity device;

    @BeforeEach
    void setUp() {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.getWorldPath(any(LevelResource.class))).thenReturn(worldRoot);
        DimensionDataStorage storage = mock(DimensionDataStorage.class);
        when(storage.computeIfAbsent(any(SavedData.Factory.class), anyString()))
                .thenReturn(AudioIdRegistry.load(new CompoundTag(), null));
        ServerLevel overworld = mock(ServerLevel.class);
        when(overworld.getDataStorage()).thenReturn(storage);
        when(server.overworld()).thenReturn(overworld);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        device = new RecordingDeviceBlockEntity(BlockPos.ZERO, ModBlocks.RECORDING_DEVICE.get().defaultBlockState());
        device.setLevel(level);
    }

    /** Writes {@code audio} onto {@code medium} as the device does; the written medium is left in the output slot. */
    private ItemStack write(ItemStack medium, byte[] audio, String fileName, String format) {
        device.getInventory().setStackInSlot(RecordingDeviceBlockEntity.INPUT_SLOT, medium);
        device.setPendingAudio(audio, fileName, format);
        assertThat(device.startRecording()).as("the write starts").isEqualTo(RecordingDeviceBlockEntity.START_OK);
        for (int i = 0; i < device.getMaxRecordingProgress() && device.isRecording(); i++) {
            device.tickRecording();
        }
        ItemStack written = device.getInventory().getStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT);
        assertThat(written.has(ModDataComponents.AUDIO_ID.get())).as("the write finished and stored the sound").isTrue();
        return written;
    }

    /** A RIFF/WAVE file of {@code seconds} of 8 kHz mono 8-bit silence; AudioDuration reads its length from the header. */
    private static byte[] wav(int seconds) {
        int dataSize = 8000 * seconds;
        ByteBuffer b = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataSize).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1)        // PCM, mono
                .putInt(8000).putInt(8000)                      // sample rate, bytes per second
                .putShort((short) 1).putShort((short) 8);       // block align, bits per sample
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataSize);
        return b.array();
    }

    private static ItemStack blankMedium() {
        return new ItemStack(ModItems.RECORDING_MEDIUM.get());
    }

    @Test
    @DisplayName("SAS-AUDIO-015: the clear takes the medium's length with its sound")
    void theClearTakesTheLengthWithTheSound() {
        ItemStack written = write(blankMedium(), wav(2), "chime.wav", "wav");
        assertThat(written.get(ModDataComponents.AUDIO_DURATION_SEC.get())).as("the write timed the sound").isEqualTo(2);

        device.clearMediaAudioData();

        ItemStack cleared = device.getInventory().getStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT);
        assertThat(cleared.is(ModItems.RECORDING_MEDIUM.get())).as("the medium stays in its slot").isTrue();
        assertThat(cleared.has(ModDataComponents.AUDIO_ID.get())).as("audio id").isFalse();
        assertThat(cleared.has(ModDataComponents.AUDIO_FILE_NAME.get())).as("file name").isFalse();
        assertThat(cleared.has(ModDataComponents.AUDIO_FORMAT.get())).as("format").isFalse();
        assertThat(cleared.has(ModDataComponents.AUDIO_DURATION_SEC.get()))
                .as("a length for a sound the medium no longer has").isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-015: a medium the old clear left with only a length is cleared too")
    void aMediumLeftWithOnlyALengthIsClearedToo() {
        // What the clear left before 2026-10-07: the sound gone, its length kept. Nothing else on the medium names a
        // sound, so a clear that asks only after the sound's own components passes it by.
        ItemStack leftOver = blankMedium();
        leftOver.set(ModDataComponents.AUDIO_DURATION_SEC.get(), 32);
        device.getInventory().setStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT, leftOver);

        device.clearMediaAudioData();

        assertThat(device.getInventory().getStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT)
                .has(ModDataComponents.AUDIO_DURATION_SEC.get())).as("the stale length").isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-015: a rewrite the device cannot time does not keep the old sound's length")
    void aRewriteThatCannotBeTimedDropsTheOldLength() {
        ItemStack first = write(blankMedium(), wav(2), "chime.wav", "wav");
        assertThat(first.get(ModDataComponents.AUDIO_DURATION_SEC.get())).as("the first write timed its sound").isEqualTo(2);
        device.getInventory().setStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT, ItemStack.EMPTY);   // taken out

        // An RF64 header (a WAV past the RIFF size field): the device does not read its length.
        byte[] untimed = wav(3);
        System.arraycopy("RF64".getBytes(StandardCharsets.US_ASCII), 0, untimed, 0, 4);
        assertThat(AudioDuration.compute(untimed, "wav")).as("the fixture is a sound the device cannot time").isZero();
        ItemStack second = write(first, untimed, "announce.wav", "wav");

        assertThat(second.get(ModDataComponents.AUDIO_ID.get())).as("a new sound")
                .isNotEqualTo(first.get(ModDataComponents.AUDIO_ID.get()));
        assertThat(second.get(ModDataComponents.AUDIO_FILE_NAME.get())).isEqualTo("announce.wav");
        assertThat(second.has(ModDataComponents.AUDIO_DURATION_SEC.get()))
                .as("not the 2 s of the sound it replaced").isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-015: a rewrite the device can time carries the new sound's length")
    void aRewriteThatCanBeTimedCarriesTheNewLength() {
        ItemStack first = write(blankMedium(), wav(2), "chime.wav", "wav");
        device.getInventory().setStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT, ItemStack.EMPTY);

        ItemStack second = write(first, wav(5), "announce.wav", "wav");

        assertThat(second.get(ModDataComponents.AUDIO_DURATION_SEC.get())).isEqualTo(5);
    }
}
