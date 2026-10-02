package com.spatialaudiosystem.client;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioHashes;
import com.spatialaudiosystem.audio.AudioStorage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sounds this client has received, kept on disk under their content hash
 * (notes/CLIENT_AUDIO_CACHE.md §4).
 *
 * <p>On disk because the case it exists for is a player who has just joined: a cache in memory is
 * empty at exactly that moment. Kept under a budget, oldest use first; a hit refreshes the use.
 *
 * <p>{@link #has} answers from the file's presence and length alone, because it runs on the
 * network thread and must not read megabytes there. The bytes are checked against their hash when
 * they are read, and a file that does not hold them is deleted and read as absent -- so a damaged
 * file costs one late request, never a wrong sound.
 */
public final class AudioCache {

    /** The whole cache. Thirty-odd full-size sounds; typical ones are a fraction of the limit. */
    public static final long BUDGET_BYTES = 256L * 1024 * 1024;

    private static final Pattern NAME = Pattern.compile("[0-9a-f]{64}\\.bin");
    private static final HexFormat HEX = HexFormat.of();

    private static volatile AudioCache instance;

    private final Path dir;
    private final long budget;

    AudioCache(Path dir, long budget) {
        this.dir = dir;
        this.budget = budget;
    }

    /** The game's cache, under {@code <gamedir>/spatialaudiosystem/audio-cache}. */
    public static AudioCache get() {
        AudioCache c = instance;
        if (c == null) {
            synchronized (AudioCache.class) {
                if (instance == null) {
                    instance = new AudioCache(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                            .resolve(SpatialAudioSystem.MOD_ID).resolve("audio-cache"), BUDGET_BYTES);
                }
                c = instance;
            }
        }
        return c;
    }

    private Path fileFor(byte[] hash) {
        if (hash == null || hash.length != AudioHashes.LENGTH) {
            throw new IllegalArgumentException("a content hash is " + AudioHashes.LENGTH + " bytes");
        }
        return dir.resolve(HEX.formatHex(hash) + ".bin");
    }

    /** Whether a file of {@code size} bytes is kept under {@code hash}. Cheap: no bytes are read. */
    public boolean has(byte[] hash, int size) {
        try {
            Path file = fileFor(hash);
            return Files.isRegularFile(file) && Files.size(file) == size;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The bytes kept under {@code hash}, or null. A file whose bytes do not hash to its name is
     * deleted. A hit refreshes the file's use, so the budget does not evict what is still played.
     */
    public byte[] read(byte[] hash) {
        Path file;
        try {
            file = fileFor(hash);
        } catch (IllegalArgumentException e) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
        if (!Arrays.equals(AudioHashes.sha256(bytes), hash)) {
            SpatialAudioSystem.LOGGER.warn("Audio cache file {} does not hold the bytes it is named for; deleting it",
                    file.getFileName());
            deleteQuietly(file);
            return null;
        }
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // the hit still counts; only its place in the eviction order is stale
        }
        return bytes;
    }

    /**
     * Keeps {@code data} under {@code hash} -- only if the bytes hash to it, and only up to the
     * size the server accepts. Written beside the target and moved in, so a write cut short never
     * leaves a file {@link #has} would answer for. Returns whether the bytes are now kept.
     */
    public boolean store(byte[] hash, byte[] data) {
        if (data == null || data.length == 0 || data.length > AudioStorage.MAX_AUDIO_SIZE) return false;
        Path file;
        try {
            file = fileFor(hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!Arrays.equals(AudioHashes.sha256(data), hash)) return false;
        if (has(hash, data.length)) return true;
        Path tmp = dir.resolve(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.write(tmp, data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.warn("Could not keep audio {} in the cache", file.getFileName(), e);
            deleteQuietly(tmp);
            return false;
        }
        evict();
        return true;
    }

    /** Deletes the least recently used kept files until the cache is within its budget. Touches nothing else. */
    void evict() {
        List<Path> kept = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path p : files) {
                if (NAME.matcher(p.getFileName().toString()).matches() && Files.isRegularFile(p)) kept.add(p);
            }
        } catch (IOException e) {
            return;
        }
        long total = 0;
        List<long[]> order = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            try {
                long size = Files.size(kept.get(i));
                total += size;
                order.add(new long[]{Files.getLastModifiedTime(kept.get(i)).toMillis(), size, i});
            } catch (IOException ignored) {
                // gone already, or unreadable: not counted, not deleted
            }
        }
        if (total <= budget) return;
        order.sort((a, b) -> Long.compare(a[0], b[0]));
        for (long[] entry : order) {
            if (total <= budget) break;
            if (deleteQuietly(kept.get((int) entry[2]))) total -= entry[1];
        }
    }

    private static boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }
}
