package com.spatialaudiosystem.client.wiki;

import belugalab.mcss3.debug.GuiScreenCapture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.spatialaudiosystem.SpatialAudioSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runtime wiki screenshots for SAS screens, mirroring TSU's pipeline.
 *
 * <p>A screen is rendered off-view through Manta's {@code GuiScreenCapture} and the result is
 * registered as a {@link DynamicTexture} under
 * {@code spatialaudiosystem:textures/wiki/screens/<name>.png}. Wiki markdown then reaches it with
 * {@code ![](bws:spatialaudiosystem:wiki/screens/<name>.png)}. Several aliases are registered per
 * capture, so pages can use a short stable name while language/state variants still resolve.
 */
public final class SasWikiCapture {

    private static final Logger LOGGER = LoggerFactory.getLogger("SAS-WikiCapture");

    private SasWikiCapture() {}

    /**
     * Captures {@code screen} and registers it under {@code id}/{@code state}/{@code lang}.
     * Must run on the render thread; failures are logged and never thrown (the page simply
     * falls back to having no image).
     */
    public static boolean captureScreen(Screen screen, String id, String state, String lang, boolean savePng) {
        Minecraft mc = Minecraft.getInstance();
        if (screen == null || id == null || id.isEmpty()) return false;
        if (!RenderSystem.isOnRenderThread()) return false;

        GuiScreenCapture.CaptureResult result;
        try {
            int scale = (int) Math.round(mc.getWindow().getGuiScale());
            result = GuiScreenCapture.captureVisibleContent(screen, scale);
        } catch (Throwable t) {
            LOGGER.warn("[SasWikiCapture] capture failed for {}: {}", id, t.toString());
            return false;
        }
        if (result == null || result.image() == null) return false;

        String resolvedLang = (lang == null || lang.isBlank()) ? currentLang() : lang;
        String resolvedState = safeName(state, "main");
        Set<String> names = textureNames(safeName(id, "screen"), resolvedState, resolvedLang);

        NativeImage img = result.image();
        try {
            if (savePng) saveImages(mc, img, names);
            registerTextures(mc, img, names);
        } finally {
            img.close();
        }
        return true;
    }

    private static String currentLang() {
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        return (lang == null || lang.isBlank()) ? "en_us" : lang;
    }

    /**
     * Aliases for one capture. A page can reference the bare {@code <id>} and still get the
     * main-state image of the player's language, because every variant maps to the same picture.
     */
    private static Set<String> textureNames(String id, String state, String lang) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add(id + "__" + state + "__" + lang);
        names.add(id + "__" + state);
        if ("main".equals(state)) {
            names.add(id + "__" + lang);
            names.add(id);
        } else {
            names.add(id + "-" + state + "__" + lang);
            names.add(id + "-" + state);
        }
        return names;
    }

    private static String safeName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = Character.toLowerCase(value.charAt(i));
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' ? c : '-');
        }
        String out = sb.toString().replaceAll("-+", "-");
        return out.isBlank() ? fallback : out;
    }

    /** Also drop the PNGs in {@code screenshots/wiki} so they can be shipped with the mod later. */
    private static void saveImages(Minecraft mc, NativeImage img, Set<String> names) {
        Path dir = Paths.get(mc.gameDirectory.getPath(), "screenshots", "wiki");
        try {
            Files.createDirectories(dir);
            for (String name : names) {
                img.writeToFile(dir.resolve(name + ".png"));
            }
            LOGGER.info("[SasWikiCapture] saved PNGs to {}", dir);
        } catch (Throwable t) {
            LOGGER.warn("[SasWikiCapture] PNG save failed: {}", t.getMessage());
        }
    }

    private static void registerTextures(Minecraft mc, NativeImage img, Set<String> names) {
        for (String name : names) {
            NativeImage copy = null;
            try {
                copy = new NativeImage(img.format(), img.getWidth(), img.getHeight(), false);
                copy.copyFrom(img);
                ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                        SpatialAudioSystem.MOD_ID, "textures/wiki/screens/" + name + ".png");
                DynamicTexture tex = new DynamicTexture(copy);
                mc.getTextureManager().register(loc, tex);
                applyBilinear(tex);
            } catch (Throwable t) {
                LOGGER.error("[SasWikiCapture] register failed for {}: {}", name, t.toString());
                if (copy != null) copy.close();
            }
        }
    }

    /** Screenshots are scaled down in the wiki, so filter them smoothly rather than nearest. */
    private static void applyBilinear(DynamicTexture tex) {
        try {
            int id = tex.getId();
            if (id <= 0) return;
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(id);
            com.mojang.blaze3d.platform.GlStateManager._texParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            com.mojang.blaze3d.platform.GlStateManager._texParameter(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        } catch (Throwable ignored) { }
    }
}
