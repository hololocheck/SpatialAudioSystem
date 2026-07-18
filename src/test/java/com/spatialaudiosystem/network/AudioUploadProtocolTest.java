package com.spatialaudiosystem.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static com.spatialaudiosystem.network.AudioUploadChunkPayload.CHUNK_SIZE;
import static com.spatialaudiosystem.network.AudioUploadChunkPayload.MAX_TOTAL_SIZE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction tests for the framing half of SAS-NET-004.
 *
 * <p>The upload session used to allocate from the announced size without bounding it,
 * index into the buffer without checking the index, and count receipts rather than
 * distinct chunks, so a repeated chunk finished a transfer whose gaps were still zero.
 */
class AudioUploadProtocolTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final BlockPos POS = BlockPos.ZERO;

    @AfterEach
    void clearSessions() throws Exception {
        Field f = AudioUploadChunkPayload.class.getDeclaredField("activeSessions");
        f.setAccessible(true);
        ((Map<?, ?>) f.get(null)).clear();
    }

    private static boolean start(int totalSize, int chunkCount) {
        return AudioUploadChunkPayload.startUpload(PLAYER, POS, "clip.ogg", "ogg", totalSize, chunkCount);
    }

    @Test
    @DisplayName("SAS-NET-004: a negative or oversized transfer is refused before anything is allocated")
    void refusesSizesThatCannotBeReal() {
        assertThat(start(-1, 1)).as("negative size").isFalse();
        assertThat(start(Integer.MIN_VALUE, 1)).as("overflowed size").isFalse();
        assertThat(start(0, 0)).as("empty transfer").isFalse();
        assertThat(start(MAX_TOTAL_SIZE + 1, 21)).as("over the 10 MB cap").isFalse();
    }

    @Test
    @DisplayName("SAS-NET-004: the chunk count must match the announced size")
    void refusesFramingThatDoesNotMatchTheSize() {
        assertThat(start(CHUNK_SIZE * 2, 1)).as("too few chunks for the size").isFalse();
        assertThat(start(CHUNK_SIZE * 2, 99)).as("more chunks than the size needs").isFalse();
        assertThat(start(1024, -5)).as("negative chunk count").isFalse();
    }

    @Test
    @DisplayName("SAS-NET-004: a transfer this server would produce is accepted")
    void acceptsWellFormedFraming() {
        // Pinned so the rejections above cannot be satisfied by refusing everything.
        assertThat(start(1024, 1)).as("single short chunk").isTrue();
        assertThat(start(CHUNK_SIZE, 1)).as("exactly one full chunk").isTrue();
        assertThat(start(CHUNK_SIZE + 1, 2)).as("one full chunk plus a byte").isTrue();
        assertThat(start(MAX_TOTAL_SIZE, 21)).as("the 10 MB boundary must still upload").isTrue();
    }

    @Test
    @DisplayName("SAS-NET-004: chunk count is derived, not taken on trust")
    void chunkCountIsCeilingOfSizeOverChunk() {
        assertThat(AudioUploadChunkPayload.expectedChunkCount(1)).isEqualTo(1);
        assertThat(AudioUploadChunkPayload.expectedChunkCount(CHUNK_SIZE)).isEqualTo(1);
        assertThat(AudioUploadChunkPayload.expectedChunkCount(CHUNK_SIZE + 1)).isEqualTo(2);
        assertThat(AudioUploadChunkPayload.expectedChunkCount(MAX_TOTAL_SIZE)).isEqualTo(21);
    }

    // ---- reassembly ----

    /** Two chunks: one full, then a 10-byte tail. */
    private Object twoChunkSession() {
        assertThat(start(CHUNK_SIZE + 10, 2)).isTrue();
        return session();
    }

    private static Object session() {
        try {
            Field f = AudioUploadChunkPayload.class.getDeclaredField("activeSessions");
            f.setAccessible(true);
            return ((Map<?, ?>) f.get(null)).get(PLAYER);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean accept(Object session, int index, int length) {
        try {
            var m = session.getClass().getDeclaredMethod("accept", int.class, byte[].class);
            m.setAccessible(true);
            return (boolean) m.invoke(session, index, new byte[length]);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isComplete(Object session) {
        try {
            var m = session.getClass().getDeclaredMethod("isComplete");
            m.setAccessible(true);
            return (boolean) m.invoke(session);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("SAS-NET-004: a repeated chunk cannot complete a transfer that still has gaps")
    void duplicateChunkDoesNotFinishTheTransfer() {
        Object session = twoChunkSession();

        assertThat(accept(session, 0, CHUNK_SIZE)).as("first arrival").isTrue();
        assertThat(accept(session, 0, CHUNK_SIZE)).as("same chunk again").isFalse();

        // Counting receipts would read 2 of 2 here and commit a buffer whose tail is zeros.
        assertThat(isComplete(session)).as("chunk 1 has not arrived").isFalse();

        assertThat(accept(session, 1, 10)).isTrue();
        assertThat(isComplete(session)).isTrue();
    }

    @Test
    @DisplayName("SAS-NET-004: chunk indices outside the transfer are refused")
    void refusesIndicesOutsideTheTransfer() {
        Object session = twoChunkSession();

        assertThat(accept(session, -1, CHUNK_SIZE)).as("negative index").isFalse();
        assertThat(accept(session, 2, 10)).as("one past the end").isFalse();
        assertThat(accept(session, Integer.MAX_VALUE, 10)).as("index that would overflow the offset").isFalse();
        assertThat(isComplete(session)).isFalse();
    }

    @Test
    @DisplayName("SAS-NET-004: a chunk whose length is not the one this index needs is refused")
    void refusesWrongChunkLengths() {
        Object session = twoChunkSession();

        assertThat(accept(session, 0, CHUNK_SIZE - 1)).as("short leading chunk").isFalse();
        assertThat(accept(session, 1, 11)).as("tail longer than the remainder").isFalse();
        assertThat(accept(session, 1, 0)).as("empty tail").isFalse();

        assertThat(accept(session, 0, CHUNK_SIZE)).as("the exact length is still accepted").isTrue();
        assertThat(accept(session, 1, 10)).as("the exact tail is still accepted").isTrue();
        assertThat(isComplete(session)).isTrue();
    }
}
