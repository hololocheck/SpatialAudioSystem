package com.spatialaudiosystem.audio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reproduction tests for the P0 storage findings in
 * {@code notes/SPATIAL_AUDIO_SYSTEM_COMPREHENSIVE_REVIEW_2026-07-17.md}.
 *
 * <p>These assert invariants rather than current signatures, so they stay valid
 * once {@code save} grows an explicit result type and cleanup grows a durable
 * ownership index.
 */
class AudioStorageP0Test {

    private static final byte[] AUDIO = {'O', 'g', 'g', 'S', 1, 2, 3, 4};

    @TempDir
    Path worldRoot;

    private MinecraftServer server;
    private AudioIdRegistry registry;

    @BeforeEach
    void setUp() {
        server = mock(MinecraftServer.class);
        when(server.getWorldPath(any(LevelResource.class))).thenReturn(worldRoot);
        registry = new AudioIdRegistry();
        installRegistry(registry);
    }

    private void installRegistry(AudioIdRegistry data) {
        DimensionDataStorage storage = mock(DimensionDataStorage.class);
        when(storage.computeIfAbsent(any(SavedData.Factory.class), anyString())).thenReturn(data);
        ServerLevel overworld = mock(ServerLevel.class);
        when(overworld.getDataStorage()).thenReturn(storage);
        when(server.overworld()).thenReturn(overworld);
    }

    /**
     * Round-trips the registry through NBT and hands back a fresh instance, which is
     * what the server does across a restart. Anything the registry fails to persist
     * is lost here exactly as it would be in a real world.
     */
    private void simulateServerRestart() {
        CompoundTag tag = registry.save(new CompoundTag(), null);
        registry = AudioIdRegistry.load(tag, null);
        installRegistry(registry);
    }

    private void noPlayersOnline() {
        PlayerList list = mock(PlayerList.class);
        when(list.getPlayers()).thenReturn(List.<ServerPlayer>of());
        when(server.getPlayerList()).thenReturn(list);
    }

    private Path audioFile(UUID id) {
        return worldRoot.resolve("spatialaudiosystem_audio").resolve(id + ".audio");
    }

    private void ageBeyondCutoff(UUID id) throws Exception {
        Files.setAttribute(audioFile(id), "creationTime",
                FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));
    }

    @Test
    @DisplayName("SAS-STORAGE-002: a UUID handed back by save() must resolve to durable data")
    void saveMustNotReportSuccessWhenTheWriteFailed() {
        // Block directory creation by planting a regular file where the storage dir belongs.
        // This is the unit-test stand-in for disk-full / permission / I/O failure.
        try {
            Files.write(worldRoot.resolve("spatialaudiosystem_audio"), new byte[]{0});
        } catch (Exception e) {
            throw new AssertionError("test setup failed", e);
        }

        UUID id = AudioStorage.save(server, AUDIO);

        // The contract under test: callers key item state off this UUID, so if a UUID
        // is published at all, the bytes must be durably readable. Today save() logs
        // the IOException and returns a UUID anyway, and the caller then discards the
        // only copy of the audio.
        if (id != null) {
            assertThat(AudioStorage.load(server, id))
                    .as("save() published UUID %s, so the audio must be loadable", id)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("SAS-STORAGE-002: a successful save round-trips and leaves no temp file behind")
    void saveSucceedsOnTheHappyPath() throws Exception {
        UUID id = AudioStorage.save(server, AUDIO);

        // Pinned so the failure test above cannot be satisfied by never returning an id.
        assertThat(id).as("a writable directory must produce an id").isNotNull();
        assertThat(AudioStorage.load(server, id)).isEqualTo(AUDIO);

        try (var entries = Files.list(worldRoot.resolve("spatialaudiosystem_audio"))) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .as("the atomic-move staging file must not survive a successful save")
                    .containsExactly(id + ".audio");
        }
    }

    @Test
    @DisplayName("SAS-STORAGE-001: audio referenced only by an unloaded container survives a restart sweep")
    void cleanupMustNotDeleteAudioMissingOnlyFromTheProcessLocalSet() throws Exception {
        // A previous session recorded audio onto a medium, which now sits in a chest
        // inside an unloaded chunk. The file is legitimately referenced.
        UUID id = AudioStorage.save(server, AUDIO);
        assertThat(AudioStorage.load(server, id)).as("precondition: audio was saved").isNotNull();

        ageBeyondCutoff(id);
        simulateServerRestart();
        noPlayersOnline();

        AudioStorage.cleanupOrphans(server, AudioStorage.collectReferencedIds(server));

        // knownIds is process-local and the chest is not scanned, so the only surviving
        // evidence of the reference is gone. The file must not be deleted on that basis.
        assertThat(AudioStorage.load(server, id))
                .as("audio %s is still referenced by an unloaded container", id)
                .isNotNull();
    }

    @Test
    @DisplayName("SAS-STORAGE-001: audio from a world that predates the registry is adopted, not swept")
    void existingWorldAudioSurvivesTheFirstSweep() throws Exception {
        // A world upgraded from a build with no registry: the file is on disk, the medium
        // is in some chest, and nothing has ever told the registry this id exists.
        UUID id = AudioStorage.save(server, AUDIO);
        ageBeyondCutoff(id);
        registry = new AudioIdRegistry();
        installRegistry(registry);
        noPlayersOnline();

        AudioStorage.sweep(server);

        assertThat(AudioStorage.load(server, id))
                .as("audio %s already existed in this world and must be adopted, not deleted", id)
                .isNotNull();
        assertThat(registry.known()).as("the sweep must record what it adopted").contains(id);
    }

    @Test
    @DisplayName("SAS-STORAGE-001: the sweep is skipped while the world's audio is unreadable")
    void sweepIsSkippedWhenExistingAudioCannotBeRead() throws Exception {
        UUID id = AudioStorage.save(server, AUDIO);
        ageBeyondCutoff(id);
        registry = new AudioIdRegistry();
        installRegistry(registry);
        noPlayersOnline();

        // Directory listing fails. A half-built reference set must not be handed to cleanup.
        when(server.getWorldPath(any(LevelResource.class))).thenReturn(worldRoot.resolve("gone"));
        AudioStorage.sweep(server);
        when(server.getWorldPath(any(LevelResource.class))).thenReturn(worldRoot);

        assertThat(AudioStorage.load(server, id))
                .as("a sweep that could not read the world must not delete from it")
                .isNotNull();
    }

    @Test
    @DisplayName("SAS-STORAGE-001: a genuinely unreferenced old file is still collectable")
    void cleanupStillCollectsTrulyOrphanedFiles() throws Exception {
        UUID id = AudioStorage.save(server, AUDIO);
        ageBeyondCutoff(id);

        // Nothing anywhere references this id. Pinned here so a fix for the test above
        // cannot degenerate into "never delete anything".
        AudioStorage.cleanupOrphans(server, Set.of());

        assertThat(AudioStorage.load(server, id))
                .as("unreferenced audio %s should eventually be reclaimed", id)
                .isNull();
    }
}
