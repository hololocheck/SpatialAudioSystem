package com.spatialaudiosystem.client;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.network.AudioUploadChunkPayload;
import com.spatialaudiosystem.network.AudioUploadStartPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs the native audio-file picker off the render thread and hands the result back
 * on it.
 *
 * <p>The old screen did all of this on the picker worker itself: it wrote the screen's
 * fields and sent the upload from a background thread, and kept running after the screen
 * closed. This service reads the file off-thread (the slow part) but never touches game
 * or network state there — the result crosses back with {@link Minecraft#execute}, is
 * dropped unless the requesting screen is still open, and only then updates the UI and
 * sends the upload.
 *
 * <p>The decision logic lives in {@link #runPick}, with its native, filesystem, network,
 * and render-thread dependencies passed in as seams so it can be driven synchronously in
 * tests (SAS-UI-009).
 */
public final class AudioFilePickerService {

    /** What the screen needs to show once a file has been picked. */
    public record Picked(String fileName, String format) {}

    /** Opens the native picker and returns the chosen path, or {@code null} if cancelled. */
    @FunctionalInterface
    interface FilePicker { String pick(); }

    /** Reads the file if it is non-empty and within {@code maxBytes}; returns {@code null} otherwise. */
    @FunctionalInterface
    interface BoundedReader { byte[] read(Path file, long maxBytes) throws IOException; }

    /** Sends the picked audio to the server. */
    @FunctionalInterface
    interface UploadSink { void upload(BlockPos target, String fileName, String format, byte[] data); }

    /** Runs a task on the render thread. */
    @FunctionalInterface
    interface MainThreadExecutor { void execute(Runnable task); }

    private AudioFilePickerService() {}

    /**
     * Opens the picker, reads the chosen file, and — if {@code stillOpen} still holds when
     * the result returns — uploads it to {@code target} and reports the display info.
     *
     * @param stillOpen checked on the render thread; false means the screen has closed and
     *                  the result (and upload) is discarded.
     * @param onPicked  invoked on the render thread with the picked file's display info.
     */
    public static void pickAndUpload(BlockPos target, BooleanSupplier stillOpen, Consumer<Picked> onPicked) {
        Thread worker = new Thread(() -> runPick(
                target, stillOpen, onPicked,
                AudioFilePickerService::openDialog,
                AudioFilePickerService::readBounded,
                AudioFilePickerService::upload,
                task -> Minecraft.getInstance().execute(task)),
                "SSS-FileChooser");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * The picker flow with its native, filesystem, network, and render-thread dependencies
     * injected. Reads off the calling thread and never touches game or network state there;
     * the result crosses to {@code main}, is dropped unless {@code stillOpen} still holds,
     * and only then uploads through {@code sink} and reports through {@code onPicked}.
     */
    static void runPick(BlockPos target, BooleanSupplier stillOpen, Consumer<Picked> onPicked,
                        FilePicker picker, BoundedReader reader, UploadSink sink, MainThreadExecutor main) {
        String path = picker.pick();
        if (path == null) return;  // cancelled

        Path file = Path.of(path);
        String fileName = file.getFileName().toString();
        String format = formatOf(fileName);

        byte[] data;
        try {
            data = reader.read(file, AudioUploadChunkPayload.MAX_TOTAL_SIZE);
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.error("Failed to read audio file {}", fileName, e);
            return;
        }
        if (data == null) return;  // empty or over the size cap: refused before any upload

        main.execute(() -> {
            if (!stillOpen.getAsBoolean()) return;  // screen closed: drop the result and the upload
            sink.upload(target, fileName, format, data);
            onPicked.accept(new Picked(fileName, format));
        });
    }

    private static String openDialog() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(3);
            filters.put(stack.UTF8("*.mp3"));
            filters.put(stack.UTF8("*.ogg"));
            filters.put(stack.UTF8("*.wav"));
            filters.flip();
            return TinyFileDialogs.tinyfd_openFileDialog(
                    "Select Audio File", "", filters, "Audio Files (*.mp3, *.ogg, *.wav)", false);
        }
    }

    /**
     * Reads {@code file} only if it is non-empty and within {@code maxBytes}; otherwise logs and
     * returns {@code null}, so an oversized file is never pulled into heap (the server would
     * reject it anyway).
     */
    static byte[] readBounded(Path file, long maxBytes) throws IOException {
        long size = Files.size(file);
        if (size <= 0 || size > maxBytes) {
            SpatialAudioSystem.LOGGER.warn("Audio file {} is {} bytes; over the {} MB limit, not read",
                    file.getFileName(), size, maxBytes / (1024 * 1024));
            return null;
        }
        return Files.readAllBytes(file);
    }

    private static String formatOf(String fileName) {
        String n = fileName.toLowerCase();
        if (n.endsWith(".mp3")) return "mp3";
        if (n.endsWith(".ogg")) return "ogg";
        if (n.endsWith(".wav")) return "wav";
        return "unknown";
    }

    /** Sends the start + chunk packets. Runs on the render thread, so networking is on the main thread. */
    private static void upload(BlockPos target, String fileName, String format, byte[] data) {
        int chunkSize = AudioUploadChunkPayload.CHUNK_SIZE;
        int chunkCount = (data.length + chunkSize - 1) / chunkSize;

        PacketDistributor.sendToServer(new AudioUploadStartPayload(
                target, fileName, format, data.length, chunkCount));

        for (int i = 0; i < chunkCount; i++) {
            int offset = i * chunkSize;
            int len = Math.min(chunkSize, data.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(data, offset, chunk, 0, len);
            PacketDistributor.sendToServer(new AudioUploadChunkPayload(i, chunk));
        }
        SpatialAudioSystem.LOGGER.info("Uploaded audio file: {} ({} chunks)", fileName, chunkCount);
    }
}
