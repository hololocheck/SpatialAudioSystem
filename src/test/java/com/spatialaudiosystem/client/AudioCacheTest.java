package com.spatialaudiosystem.client;

import com.spatialaudiosystem.audio.AudioHashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAS-CACHE-001: the client's kept sounds (notes/CLIENT_AUDIO_CACHE.md §4). A real directory, real
 * files: what is pinned is what a damaged, foreign or over-budget file does.
 */
class AudioCacheTest {

    @TempDir
    Path dir;

    private static byte[] sound(int size, int seed) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) data[i] = (byte) (i * 31 + seed);
        return data;
    }

    private Path fileOf(byte[] hash) {
        return dir.resolve(HexFormat.of().formatHex(hash) + ".bin");
    }

    @Test
    @DisplayName("SAS-CACHE-001: kept bytes are there, by name and length, and read back whole")
    void keptBytesReadBack() {
        AudioCache cache = new AudioCache(dir, 1 << 20);
        byte[] data = sound(4_000, 1);
        byte[] hash = AudioHashes.sha256(data);
        assertThat(cache.has(hash, data.length)).as("nothing kept yet").isFalse();
        assertThat(cache.store(hash, data)).isTrue();
        assertThat(cache.has(hash, data.length)).isTrue();
        assertThat(cache.has(hash, data.length - 1)).as("the announced length is part of the question").isFalse();
        assertThat(cache.read(hash)).isEqualTo(data);
    }

    @Test
    @DisplayName("SAS-CACHE-001: bytes that do not hash to the name they were announced under are not kept")
    void bytesUnderTheWrongNameAreNotKept() {
        AudioCache cache = new AudioCache(dir, 1 << 20);
        byte[] data = sound(1_000, 2);
        byte[] other = AudioHashes.sha256(sound(1_000, 3));
        assertThat(cache.store(other, data)).isFalse();
        assertThat(cache.has(other, data.length)).isFalse();
        assertThat(Files.exists(fileOf(other))).isFalse();
    }

    @Test
    @DisplayName("SAS-CACHE-001: a damaged file reads as absent and is deleted")
    void aDamagedFileIsDeleted() throws Exception {
        AudioCache cache = new AudioCache(dir, 1 << 20);
        byte[] data = sound(2_000, 4);
        byte[] hash = AudioHashes.sha256(data);
        cache.store(hash, data);
        byte[] damaged = data.clone();
        damaged[700] ^= 0x40;
        Files.write(fileOf(hash), damaged);
        // Same length, so the network thread's cheap question still says yes...
        assertThat(cache.has(hash, data.length)).isTrue();
        // ...and the read, which checks the bytes, does not hand them over.
        assertThat(cache.read(hash)).isNull();
        assertThat(Files.exists(fileOf(hash))).as("deleted, so the next announcement asks for the sound").isFalse();
    }

    @Test
    @DisplayName("SAS-CACHE-001: over budget, the least recently used go first, and a hit counts as a use")
    void theBudgetEvictsTheLeastRecentlyUsed() throws Exception {
        AudioCache cache = new AudioCache(dir, 2_500);
        byte[] a = sound(1_000, 5);
        byte[] b = sound(1_000, 6);
        byte[] c = sound(1_000, 7);
        byte[] ha = AudioHashes.sha256(a);
        byte[] hb = AudioHashes.sha256(b);
        byte[] hc = AudioHashes.sha256(c);
        cache.store(ha, a);
        cache.store(hb, b);
        Files.setLastModifiedTime(fileOf(ha), FileTime.fromMillis(1_000_000L));
        Files.setLastModifiedTime(fileOf(hb), FileTime.fromMillis(2_000_000L));
        // a is the older one -- until it is played again.
        assertThat(cache.read(ha)).isEqualTo(a);
        cache.store(hc, c);
        assertThat(cache.has(ha, a.length)).as("played most recently").isTrue();
        assertThat(cache.has(hb, b.length)).as("the least recently used").isFalse();
        assertThat(cache.has(hc, c.length)).as("just kept").isTrue();
    }

    @Test
    @DisplayName("SAS-CACHE-001: eviction touches nothing but the cache's own files")
    void evictionTouchesOnlyItsOwnFiles() throws Exception {
        AudioCache cache = new AudioCache(dir, 100);
        Path foreign = dir.resolve("notes.txt");
        Path almost = dir.resolve("abc.bin");
        Files.write(foreign, sound(5_000, 8));
        Files.write(almost, sound(5_000, 9));
        Files.setLastModifiedTime(foreign, FileTime.fromMillis(1L));
        Files.setLastModifiedTime(almost, FileTime.fromMillis(1L));
        byte[] data = sound(1_000, 10);
        cache.store(AudioHashes.sha256(data), data);
        assertThat(Files.exists(foreign)).isTrue();
        assertThat(Files.exists(almost)).isTrue();
    }

    @Test
    @DisplayName("SAS-CACHE-001: a malformed hash is a miss, not an exception on the network thread")
    void aMalformedHashIsAMiss() {
        AudioCache cache = new AudioCache(dir, 1 << 20);
        assertThat(cache.has(new byte[5], 10)).isFalse();
        assertThat(cache.has(null, 10)).isFalse();
        assertThat(cache.read(new byte[5])).isNull();
    }
}
