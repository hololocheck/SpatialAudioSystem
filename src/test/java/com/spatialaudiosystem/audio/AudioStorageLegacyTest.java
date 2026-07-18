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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reading recordings made before the 1.0.4 rename (SAS-COMPAT-008).
 *
 * <p>1.0.3 stored audio under {@code stationsoundsystem_audio}. The rename moved new
 * recordings to a new directory and left every existing one where the current code does
 * not look, so a medium that still names its UUID could no longer play.
 */
class AudioStorageLegacyTest {

    private static final byte[] AUDIO = {'O', 'g', 'g', 'S', 9, 8, 7};

    @TempDir
    Path worldRoot;

    private MinecraftServer server;
    private AudioIdRegistry registry;

    @BeforeEach
    void setUp() {
        server = mock(MinecraftServer.class);
        when(server.getWorldPath(any(LevelResource.class))).thenReturn(worldRoot);

        registry = new AudioIdRegistry();
        DimensionDataStorage storage = mock(DimensionDataStorage.class);
        when(storage.computeIfAbsent(any(SavedData.Factory.class), anyString())).thenReturn(registry);
        ServerLevel overworld = mock(ServerLevel.class);
        when(overworld.getDataStorage()).thenReturn(storage);
        when(server.overworld()).thenReturn(overworld);

        PlayerList players = mock(PlayerList.class);
        when(players.getPlayers()).thenReturn(List.<ServerPlayer>of());
        when(server.getPlayerList()).thenReturn(players);
    }

    /** Writes a recording exactly where 1.0.3 would have left it. */
    private UUID legacyRecording() throws Exception {
        UUID id = UUID.randomUUID();
        Path dir = worldRoot.resolve("stationsoundsystem_audio");
        Files.createDirectories(dir);
        Path file = dir.resolve(id + ".audio");
        Files.write(file, AUDIO);
        Files.setAttribute(file, "creationTime",
                FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));
        return id;
    }

    @Test
    @DisplayName("SAS-COMPAT-008: a recording from a 1.0.3 world still plays")
    void audioFromBeforeTheRenameIsFound() throws Exception {
        UUID id = legacyRecording();

        assertThat(AudioStorage.load(server, id))
                .as("the medium still names %s; the bytes are in the pre-rename directory", id)
                .isEqualTo(AUDIO);
    }

    @Test
    @DisplayName("SAS-COMPAT-008: the pre-rename directory is never written to or reclaimed")
    void legacyAudioIsReadOnly() throws Exception {
        UUID id = legacyRecording();
        Path legacyFile = worldRoot.resolve("stationsoundsystem_audio").resolve(id + ".audio");

        // A full sweep, with nothing online and no medium loaded anywhere.
        AudioStorage.sweep(server);
        assertThat(legacyFile).as("cleanup must not reach into the old world's directory").exists();

        // Clearing a medium drops its reference; the shared file still stays put.
        AudioStorage.delete(server, id);
        assertThat(legacyFile).as("delete only owns the current directory").exists();
        assertThat(AudioStorage.load(server, id)).isEqualTo(AUDIO);
    }

    @Test
    @DisplayName("SAS-COMPAT-008: current audio still wins over a pre-rename file of the same id")
    void currentDirectoryTakesPrecedence() throws Exception {
        UUID id = legacyRecording();

        // Same id present in both directories: the current one is the live copy.
        byte[] current = {'N', 'E', 'W'};
        Path dir = worldRoot.resolve("spatialaudiosystem_audio");
        Files.createDirectories(dir);
        Files.write(dir.resolve(id + ".audio"), current);

        assertThat(AudioStorage.load(server, id))
                .as("the fallback must not shadow a current recording")
                .isEqualTo(current);
    }

    @Test
    @DisplayName("SAS-COMPAT-008: an unknown id is still absent, in either directory")
    void unknownIdIsStillNull() {
        assertThat(AudioStorage.load(server, UUID.randomUUID()))
                .as("the fallback must not invent audio")
                .isNull();
    }

    @Test
    @DisplayName("SAS-COMPAT-008: the registry serialises the seeded flag")
    void seededFlagSurvivesNbt() {
        registry.markSeeded();
        CompoundTag tag = registry.save(new CompoundTag(), null);

        assertThat(AudioIdRegistry.load(tag, null).isSeeded())
                .as("losing this flag re-runs adoption every restart")
                .isTrue();
    }
}
