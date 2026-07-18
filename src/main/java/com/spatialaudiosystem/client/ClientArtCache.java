package com.spatialaudiosystem.client;

import com.manta.api.image.MantaImage;
import com.mojang.blaze3d.platform.NativeImage;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.network.RequestArtPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client-side cache of recording cover art, keyed by audio id.
 *
 * <p>{@link #request} pulls art from the server once per id (via {@link RequestArtPayload});
 * {@link #receive} decodes the reply into a {@link DynamicTexture}, or records that the id has
 * no art so the jacket falls back to the music-note placeholder. Textures are released on world
 * unload / disconnect by {@code ClientLifecycleHandler}.
 */
public final class ClientArtCache {

    /** A decoded jacket texture and its native size (to scale into the frame). */
    public record Art(ResourceLocation loc, int w, int h) {}

    private static final Map<UUID, Art> TEXTURES = new HashMap<>();
    private static final Set<UUID> REQUESTED = new HashSet<>();
    private static final Set<UUID> NO_ART = new HashSet<>();

    private ClientArtCache() {}

    /** The decoded jacket for {@code id}, or null if not (yet) available. */
    public static Art get(UUID id) {
        return id == null ? null : TEXTURES.get(id);
    }

    /** Ask the server for {@code id}'s art, once. No-op if already loaded / requested / known-empty. */
    public static void request(UUID id) {
        if (id == null || TEXTURES.containsKey(id) || REQUESTED.contains(id) || NO_ART.contains(id)) return;
        REQUESTED.add(id);
        PacketDistributor.sendToServer(new RequestArtPayload(id));
    }

    /** Handle a server reply. Empty bytes mean "no art for this id". */
    public static void receive(UUID id, byte[] art) {
        REQUESTED.remove(id);
        if (art == null || art.length == 0) { NO_ART.add(id); return; }

        NativeImage img;
        try {
            // MantaImage 経由 (NativeImage.read 単体は PNG signature ゲートで JPEG を弾く。
            // 埋め込みカバーアートは JPEG が大半なので直接呼ぶと大半が無音で落ちる)。
            img = MantaImage.decode(art);
        } catch (IOException e) {
            NO_ART.add(id);
            SpatialAudioSystem.LOGGER.warn("Could not decode cover art for {}", id, e);
            return;
        }
        // DynamicTexture takes ownership of the image; width/height stay readable from it.
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                SpatialAudioSystem.MOD_ID, "jacket/" + id);
        Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
        TEXTURES.put(id, new Art(loc, img.getWidth(), img.getHeight()));
    }

    /** Release all jacket textures (world unload / disconnect). */
    public static void clear() {
        TextureManager tm = Minecraft.getInstance().getTextureManager();
        for (Art a : TEXTURES.values()) tm.release(a.loc());
        TEXTURES.clear();
        REQUESTED.clear();
        NO_ART.clear();
    }
}
