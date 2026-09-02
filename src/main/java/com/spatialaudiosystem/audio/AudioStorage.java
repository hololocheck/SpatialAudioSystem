package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.item.ModDataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side file-based audio storage.
 * Audio data is stored as individual files in {@code <world>/spatialaudiosystem_audio/<uuid>.audio}
 * instead of in ItemStack DataComponents, preventing corruption from network sync.
 */
public class AudioStorage {

    private static final String STORAGE_DIR = "spatialaudiosystem_audio";
    /** Where 1.0.3 and earlier kept their recordings, under the pre-rename mod id. */
    private static final String LEGACY_STORAGE_DIR = "stationsoundsystem_audio";

    private static Path getStorageDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(STORAGE_DIR);
    }

    private static Path getLegacyStorageDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(LEGACY_STORAGE_DIR);
    }

    /**
     * Save audio data to a new file and return the generated UUID.
     *
     * <p>Returns null if the audio was not durably written. Callers must not put the
     * id on an item or drop their copy of the bytes until they have a non-null id:
     * an id whose file is missing reads as a medium that has audio but cannot play it.
     */
    @Nullable
    public static UUID save(MinecraftServer server, byte[] audioData) {
        UUID id = UUID.randomUUID();
        Path tmp = null;
        try {
            Path dir = getStorageDir(server);
            Files.createDirectories(dir);
            // Write beside the target and swap it in, so an interrupted write cannot
            // leave a truncated file that later loads as valid audio.
            tmp = dir.resolve(id + ".audio.tmp");
            Files.write(tmp, audioData);
            Files.move(tmp, dir.resolve(id + ".audio"), StandardCopyOption.ATOMIC_MOVE);
            trackId(server, id);
            return id;
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.error("Failed to save audio {}", id, e);
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException cleanupFailure) {
                    SpatialAudioSystem.LOGGER.warn("Failed to remove temp file {}", tmp, cleanupFailure);
                }
            }
            return null;
        }
    }

    /**
     * Load audio data by UUID. Returns null if the file does not exist.
     */
    @Nullable
    public static byte[] load(MinecraftServer server, UUID id) {
        Path file = getStorageDir(server).resolve(id + ".audio");
        if (!Files.exists(file)) {
            // A recording made before the 1.0.4 rename still lives in the old directory.
            // It is read where it lies rather than moved: a move can half-succeed, and
            // there is nothing to gain by rewriting a file that reads correctly.
            Path legacy = getLegacyStorageDir(server).resolve(id + ".audio");
            if (!Files.exists(legacy)) return null;
            file = legacy;
        }
        trackId(server, id);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.error("Failed to load audio {}", id, e);
            return null;
        }
    }

    /**
     * Delete an audio file by UUID.
     */
    public static void delete(MinecraftServer server, UUID id) {
        try {
            Path dir = getStorageDir(server);
            Files.deleteIfExists(dir.resolve(id + ".audio"));
            Files.deleteIfExists(dir.resolve(id + ".art"));   // drop the cover art with its audio
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.error("Failed to delete audio {}", id, e);
        }
    }

    /**
     * Save cover art beside the audio under the same UUID ({@code <uuid>.art}). Best-effort:
     * art is optional, so a failure only means the jacket falls back to the placeholder.
     */
    public static void saveArt(MinecraftServer server, UUID id, byte[] art) {
        if (art == null || art.length == 0) return;
        Path tmp = null;
        try {
            Path dir = getStorageDir(server);
            Files.createDirectories(dir);
            tmp = dir.resolve(id + ".art.tmp");
            Files.write(tmp, art);
            Files.move(tmp, dir.resolve(id + ".art"), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.warn("Failed to save cover art {}", id, e);
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    /** Load cover art by UUID, or null if none was stored (checks the pre-rename directory too). */
    @Nullable
    public static byte[] loadArt(MinecraftServer server, UUID id) {
        Path file = getStorageDir(server).resolve(id + ".art");
        if (!Files.exists(file)) {
            Path legacy = getLegacyStorageDir(server).resolve(id + ".art");
            if (!Files.exists(legacy)) return null;
            file = legacy;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.warn("Failed to load cover art {}", id, e);
            return null;
        }
    }

    /**
     * Migrate an ItemStack from legacy AUDIO_DATA component to file-based storage.
     * If the stack has AUDIO_DATA (real data, not a network placeholder) but no AUDIO_ID,
     * saves the data to a file, sets AUDIO_ID, and removes AUDIO_DATA.
     *
     * @return true if migration was performed
     */
    public static boolean migrateIfNeeded(MinecraftServer server, ItemStack stack) {
        if (stack.has(ModDataComponents.AUDIO_ID)) return false; // already migrated
        if (!stack.has(ModDataComponents.AUDIO_DATA)) return false;

        byte[] data = stack.get(ModDataComponents.AUDIO_DATA);
        if (data == null || data.length < 32) return false; // skip network placeholders

        UUID id = save(server, data);
        if (id == null) {
            // Keep the legacy component. It is the only copy of this audio, and the
            // migration can be retried the next time the stack is touched.
            SpatialAudioSystem.LOGGER.error("Audio migration deferred: file write failed");
            return false;
        }
        stack.set(ModDataComponents.AUDIO_ID, id);
        stack.remove(ModDataComponents.AUDIO_DATA);
        SpatialAudioSystem.LOGGER.info("Migrated audio data to file: {}", id);
        return true;
    }

    /** Maximum allowed audio file size (10 MB). Chunked upload bypasses single-packet limit. */
    public static final int MAX_AUDIO_SIZE = 10 * 1024 * 1024;

    /**
     * Validate audio data size before saving. Returns an error message key or null if OK.
     */
    @Nullable
    public static net.minecraft.network.chat.Component validateSize(byte[] audioData) {
        if (audioData.length > MAX_AUDIO_SIZE) {
            // A Component, not a sentence: this is shown to a player, and the sentence form was
            // English for everyone. The maximum comes from MAX_AUDIO_SIZE rather than a literal
            // ten, so the message cannot go on claiming a limit the code no longer enforces.
            return net.minecraft.network.chat.Component.translatable(
                    "message.spatialaudiosystem.too_large",
                    audioData.length / 1024 / 1024, MAX_AUDIO_SIZE / 1024 / 1024);
        }
        return null;
    }

    /**
     * Clean up orphaned audio files that are not referenced by any known item.
     * Only deletes files older than 1 hour to avoid deleting files for items in unloaded chunks.
     *
     * @param referencedIds set of AUDIO_IDs found in loaded inventories
     */
    public static void cleanupOrphans(MinecraftServer server, Set<UUID> referencedIds) {
        Path dir = getStorageDir(server);
        if (!Files.exists(dir)) return;

        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int deleted = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.audio")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String uuidStr = name.substring(0, name.length() - ".audio".length());
                try {
                    UUID fileId = UUID.fromString(uuidStr);
                    if (referencedIds.contains(fileId)) continue;

                    // Only delete old files (safety margin for unloaded chunks)
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    if (attrs.creationTime().toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        Files.deleteIfExists(dir.resolve(fileId + ".art"));   // its cover art too
                        deleted++;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a valid UUID filename, skip
                }
            }
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.error("Failed to scan audio directory for cleanup", e);
        }

        if (deleted > 0) {
            SpatialAudioSystem.LOGGER.info("Cleaned up {} orphaned audio files", deleted);
        }
    }

    /** Register an ID as known (called on save, load, migrate). */
    public static void trackId(MinecraftServer server, UUID id) {
        AudioIdRegistry.get(server).track(id);
    }

    /**
     * The periodic reclamation pass.
     *
     * <p>Refuses to run until the world's pre-existing audio has been adopted. An
     * incomplete reference set is worse than not sweeping at all: every file the registry
     * has not heard of reads as a stray, and cleanup would delete it.
     */
    public static void sweep(MinecraftServer server) {
        AudioIdRegistry registry = AudioIdRegistry.get(server);
        adoptExistingAudio(server, registry);
        if (!registry.isSeeded()) return;

        cleanupOrphans(server, collectReferencedIds(server));
    }

    /**
     * Adopts audio that was already in the world the first time the registry runs.
     *
     * <p>Worlds created before the registry have files but no record of them, so without
     * this every one of them would read as a stray within minutes of the upgrade. The
     * files are themselves the record of what this world made, so the directory is the
     * only honest thing to seed from.
     */
    private static void adoptExistingAudio(MinecraftServer server, AudioIdRegistry registry) {
        if (registry.isSeeded()) return;

        Path dir = getStorageDir(server);
        if (!Files.exists(dir)) {
            registry.markSeeded();
            return;
        }

        int adopted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.audio")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                try {
                    registry.track(UUID.fromString(name.substring(0, name.length() - ".audio".length())));
                    adopted++;
                } catch (IllegalArgumentException ignored) {
                    // Not one of ours; leave it alone.
                }
            }
        } catch (IOException e) {
            // Stay unseeded so the sweep is skipped and retried. Marking it done here would
            // hand cleanup a half-built reference set to delete against.
            SpatialAudioSystem.LOGGER.error("Failed to adopt existing audio; cleanup postponed", e);
            return;
        }

        if (adopted > 0) {
            SpatialAudioSystem.LOGGER.info("Adopted {} existing audio files into the id registry", adopted);
        }
        registry.markSeeded();
    }

    /**
     * Collect all AUDIO_IDs that must not be reclaimed.
     *
     * <p>Backed by {@link AudioIdRegistry}, which persists with the world, so an id
     * stays referenced while its medium sits in an unloaded chunk or an offline
     * player's inventory. Player inventories are still scanned so that an id which
     * predates the registry is picked up on first sight.
     */
    public static Set<UUID> collectReferencedIds(MinecraftServer server) {
        Set<UUID> ids = new HashSet<>(AudioIdRegistry.get(server).known());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                UUID id = stack.get(ModDataComponents.AUDIO_ID);
                if (id != null) ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Load audio for a given ItemStack. Handles both new (AUDIO_ID) and legacy (AUDIO_DATA) items.
     * Automatically migrates legacy items when possible.
     */
    @Nullable
    public static byte[] loadForItem(MinecraftServer server, ItemStack stack) {
        // Try migration first
        migrateIfNeeded(server, stack);

        // New system: load from file
        UUID id = stack.get(ModDataComponents.AUDIO_ID);
        if (id != null) {
            return load(server, id);
        }

        // Legacy fallback: read directly from component (should not happen after migration)
        byte[] data = stack.get(ModDataComponents.AUDIO_DATA);
        if (data != null && data.length >= 32) {
            return data;
        }

        return null;
    }
}
