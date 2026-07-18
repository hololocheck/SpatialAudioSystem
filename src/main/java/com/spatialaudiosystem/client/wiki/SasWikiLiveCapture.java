package com.spatialaudiosystem.client.wiki;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spatialaudiosystem.screen.PlaybackDeviceScreenV2;
import com.spatialaudiosystem.screen.RecordingDeviceScreenV2;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Builds each SAS screen off-view and photographs it for the wiki, in both languages.
 *
 * <p>Shooting the real screen (rather than baking the layout JSON) means canvas work — the album
 * jacket, the owner face, the playing highlight — appears exactly as in game. Results live for the
 * session and are retaken on the next login; every step is guarded so a failure just leaves the
 * page without a picture.
 */
public final class SasWikiLiveCapture {

    private static final Logger LOGGER = LoggerFactory.getLogger("SAS-WikiLive");
    /** Already-shot keys (id/state/lang), so a session does not re-photograph the same view. */
    private static final Set<String> done = ConcurrentHashMap.newKeySet();
    private static final BiConsumer<Screen, String> NO_STATE = (s, st) -> { };

    private SasWikiLiveCapture() {}

    public static void clearCache() { done.clear(); }

    /** Photographs every documented view. Render-thread only; reschedules itself otherwise. */
    public static int captureAll(boolean savePng) {
        Minecraft mc = Minecraft.getInstance();
        if (!RenderSystem.isOnRenderThread()) {
            mc.execute(() -> captureAll(savePng));
            return 0;
        }
        if (mc.player == null || mc.level == null) return 0;

        net.minecraft.locale.Language original = net.minecraft.locale.Language.getInstance();
        int n = 0;
        // Settle dialog/overlay entry animations so the shot is the finished state.
        belugalab.mcss3.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE = true;
        try {
            for (String lang : new String[]{"ja_jp", "en_us"}) {
                try {
                    var injected = net.minecraft.client.resources.language.ClientLanguage.loadFrom(
                            mc.getResourceManager(), List.of("en_us", lang), false);
                    net.minecraft.locale.Language.inject(injected);
                } catch (Throwable t) {
                    LOGGER.warn("[SasWikiLive] language inject failed for {}: {}", lang, t.toString());
                }
                n += captureStates("memory-device", lang, savePng,
                        RecordingDeviceScreenV2::wikiCreate, NO_STATE, "main");
                n += captureStates("playback-device", lang, savePng,
                        PlaybackDeviceScreenV2::wikiCreate,
                        (s, st) -> ((PlaybackDeviceScreenV2) s).wikiApplyState(st),
                        "main", "schedule");
            }
        } finally {
            net.minecraft.locale.Language.inject(original);
            belugalab.mcss3.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE = false;
        }
        if (n > 0) LOGGER.info("[SasWikiLive] captured {} wiki screenshots", n);
        return n;
    }

    /**
     * State is applied before {@code init} so an open overlay is laid out during init — its slot
     * positions are derived from the overlay origin, which init is what establishes.
     */
    private static int captureStates(String id, String lang, boolean savePng,
                                     Supplier<? extends Screen> factory,
                                     BiConsumer<Screen, String> apply, String... states) {
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int ok = 0;
        for (String st : states) {
            String key = id + "/" + st + "/" + lang;
            if (done.contains(key)) { ok++; continue; }
            try {
                Screen screen = factory.get();
                if (screen == null) return ok;   // world not ready yet — retried on the next login
                apply.accept(screen, st);
                screen.init(mc, w, h);
                if (SasWikiCapture.captureScreen(screen, id, st, lang, savePng)) {
                    done.add(key);
                    ok++;
                }
            } catch (Throwable t) {
                LOGGER.warn("[SasWikiLive] {} failed: {}", key, t.toString());
            }
        }
        return ok;
    }
}
