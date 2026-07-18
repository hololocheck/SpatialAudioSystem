package com.spatialaudiosystem.client;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.spatialaudiosystem.network.AudioUploadChunkPayload.MAX_TOTAL_SIZE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction tests for SAS-UI-009: the picker worker must not touch a closed screen, must
 * not pull an oversized file into heap, and must hand a clean result back only while the
 * screen that asked is still open.
 *
 * <p>{@link AudioFilePickerService#runPick} is driven synchronously with fake picker / reader
 * / upload / render-thread seams, so none of the native dialog, filesystem, network, or
 * Minecraft render thread is involved. A stale run must send zero packets and report nothing.
 */
class AudioFilePickerServiceTest {

    private static final BlockPos POS = BlockPos.ZERO;
    private static final byte[] DATA = {1, 2, 3, 4};

    /** Runs the render-thread task inline so assertions are synchronous. */
    private static final AudioFilePickerService.MainThreadExecutor INLINE = Runnable::run;

    /** Records every upload the flow would send. */
    private static final class RecordingSink implements AudioFilePickerService.UploadSink {
        record Call(BlockPos target, String fileName, String format, byte[] data) {}
        final List<Call> calls = new ArrayList<>();
        @Override public void upload(BlockPos target, String fileName, String format, byte[] data) {
            calls.add(new Call(target, fileName, format, data));
        }
    }

    private RecordingSink sink;
    private List<AudioFilePickerService.Picked> reported;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        reported = new ArrayList<>();
    }

    private void run(AudioFilePickerService.FilePicker picker,
                     AudioFilePickerService.BoundedReader reader,
                     boolean stillOpen) {
        AudioFilePickerService.runPick(POS, () -> stillOpen, reported::add, picker, reader, sink, INLINE);
    }

    private static AudioFilePickerService.FilePicker picks(String path) {
        return () -> path;
    }

    private static AudioFilePickerService.BoundedReader returns(byte[] data) {
        return (file, maxBytes) -> data;
    }

    @Test
    @DisplayName("SAS-UI-009: a picked mp3/ogg/wav is uploaded once and reported with its format")
    void uploadsEachSupportedFormat() {
        for (String fmt : List.of("mp3", "ogg", "wav")) {
            setUp();
            run(picks("song." + fmt), returns(DATA), true);

            assertThat(sink.calls).as(fmt + " upload").hasSize(1);
            RecordingSink.Call call = sink.calls.get(0);
            assertThat(call.target()).isEqualTo(POS);
            assertThat(call.fileName()).isEqualTo("song." + fmt);
            assertThat(call.format()).isEqualTo(fmt);
            assertThat(call.data()).as("passed through, not copied").isSameAs(DATA);
            assertThat(reported).containsExactly(new AudioFilePickerService.Picked("song." + fmt, fmt));
        }
    }

    @Test
    @DisplayName("SAS-UI-009: cancelling the dialog reads nothing and uploads nothing")
    void cancelUploadsNothing() {
        boolean[] readerCalled = {false};
        run(picks(null), (file, maxBytes) -> { readerCalled[0] = true; return DATA; }, true);

        assertThat(readerCalled[0]).as("reader not reached").isFalse();
        assertThat(sink.calls).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    @DisplayName("SAS-UI-009: an unreadable file uploads nothing")
    void ioErrorUploadsNothing() {
        run(picks("song.mp3"), (file, maxBytes) -> { throw new IOException("boom"); }, true);

        assertThat(sink.calls).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    @DisplayName("SAS-UI-009: a file the reader refuses (empty/oversized) uploads nothing")
    void oversizeUploadsNothing() {
        run(picks("huge.wav"), returns(null), true);

        assertThat(sink.calls).isEmpty();
        assertThat(reported).isEmpty();
    }

    @Test
    @DisplayName("SAS-UI-009: a result that returns after the screen closed sends zero packets")
    void closedScreenDropsResultAndUpload() {
        run(picks("song.ogg"), returns(DATA), /* stillOpen = */ false);

        assertThat(sink.calls).as("stale controller sends no packets").isEmpty();
        assertThat(reported).as("stale controller reports nothing").isEmpty();
    }

    @Test
    @DisplayName("SAS-UI-009: picking again after a first upload uploads the second file too")
    void reSelectionUploadsEach() {
        run(picks("first.mp3"), returns(DATA), true);
        byte[] second = {9, 9};
        run(picks("second.ogg"), returns(second), true);

        assertThat(sink.calls).hasSize(2);
        assertThat(sink.calls.get(0).fileName()).isEqualTo("first.mp3");
        assertThat(sink.calls.get(1).fileName()).isEqualTo("second.ogg");
        assertThat(sink.calls.get(1).data()).isSameAs(second);
    }

    @Test
    @DisplayName("SAS-UI-009: the size guard reads a file within the cap and refuses empty/oversized without reading")
    void readBoundedEnforcesTheSizeCap(@TempDir Path dir) throws IOException {
        Path clip = dir.resolve("clip.wav");
        Files.write(clip, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10});

        assertThat(AudioFilePickerService.readBounded(clip, 100)).as("within limit").hasSize(10);
        assertThat(AudioFilePickerService.readBounded(clip, MAX_TOTAL_SIZE)).as("within the 10 MB cap").hasSize(10);
        assertThat(AudioFilePickerService.readBounded(clip, 4)).as("over the given cap").isNull();

        Path empty = dir.resolve("empty.wav");
        Files.write(empty, new byte[0]);
        assertThat(AudioFilePickerService.readBounded(empty, 100)).as("empty file").isNull();
    }
}
