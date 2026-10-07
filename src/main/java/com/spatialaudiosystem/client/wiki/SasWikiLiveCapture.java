package com.spatialaudiosystem.client.wiki;

import com.mojang.blaze3d.systems.RenderSystem;
import com.spatialaudiosystem.screen.PlaybackDeviceScreenV2;
import com.spatialaudiosystem.screen.RecordingDeviceScreenV2;
import com.spatialaudiosystem.screen.SoundHandyScreen;
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

    /**
     * One screen of this mod and the states it can be put in. {@code applyBeforeInit}: the state is applied before
     * {@code init} rather than after it — the playback device's schedule overlay is laid out during init, and its slot
     * positions come from the overlay origin init establishes. {@code photographed}: the wiki capture shoots it; a
     * screen no page shows is listed for the machine sweep alone (none since 改善1, 2026-10-07: the sound handy's page
     * shows it).
     *
     * <p>BelugaAOS reads {@link #table()} by reflection and SHOWS these screens on a world of its own run for its UI
     * sweep; the showing, and the containment it needs, live there and never here — the device factories build with
     * a throwaway block entity, and a container screen's menu carries containerId 0.
     */
    record Entry(String id, Supplier<Screen> factory, BiConsumer<Screen, String> apply,
                 boolean applyBeforeInit, boolean photographed, String... states) {}

    /** The catalogue. Order is the capture order, which is also the order a driver walks. */
    static List<Entry> table() {
        return List.of(
            new Entry("memory-device", RecordingDeviceScreenV2::wikiCreate, NO_STATE, true, true, "main"),
            // 改善1 (2026-10-07): the redstone dialog has a page of its own, and the sound handy's page shows the handy
            // operated on devices of its own (SoundHandyScreen.wikiCreate).
            new Entry("playback-device", PlaybackDeviceScreenV2::wikiCreate,
                    (s, st) -> ((PlaybackDeviceScreenV2) s).wikiApplyState(st), true, true, "main", "schedule",
                    "redstone"),
            new Entry("sound-handy", SoundHandyScreen::wikiCreate,
                    (s, st) -> ((SoundHandyScreen) s).wikiApplyState(st), false, true, "list", "settings")
        );
    }

    /**
     * The photographed screens, lent to Manta's wiki to draw each one live where a page shows its picture (Manta
     * {@code WikiLiveScreens}, MANTA_7_PHASE7_PLAYBACK 3.1): the wiki builds and draws them inside its playback
     * containment (no packet, no slot action on the player's own menu, no screen change). Once, at client setup.
     */
    public static void registerLive() {
        List<com.manta.api.wiki.WikiLiveScreens.Row> rows = new java.util.ArrayList<>();
        for (Entry e : table()) {
            if (e.photographed()) {
                rows.add(new com.manta.api.wiki.WikiLiveScreens.Row(e.id(), e.factory(), e.apply(),
                        e.applyBeforeInit(), e.states()));
            }
        }
        com.manta.api.wiki.WikiLiveScreens.register(com.spatialaudiosystem.SpatialAudioSystem.MOD_ID, rows);
    }

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
        com.manta.api.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE = true;
        try {
            for (String lang : new String[]{"ja_jp", "en_us"}) {
                try {
                    var injected = net.minecraft.client.resources.language.ClientLanguage.loadFrom(
                            mc.getResourceManager(), List.of("en_us", lang), false);
                    net.minecraft.locale.Language.inject(injected);
                } catch (Throwable t) {
                    LOGGER.warn("[SasWikiLive] language inject failed for {}: {}", lang, t.toString());
                }
                for (Entry e : table()) {
                    if (e.photographed()) {
                        n += captureStates(e.id(), lang, savePng, e.factory(), e.apply(), e.applyBeforeInit(),
                                e.states());
                    }
                }
            }
        } finally {
            net.minecraft.locale.Language.inject(original);
            com.manta.api.screen.JsonLayoutScreen.WIKI_CAPTURE_MODE = false;
        }
        if (n > 0) LOGGER.info("[SasWikiLive] captured {} wiki screenshots", n);
        return n;
    }

    /**
     * With {@code applyBeforeInit} (both devices) the state is applied before {@code init} so an open overlay is laid
     * out during init — its slot positions are derived from the overlay origin, which init is what establishes.
     */
    private static int captureStates(String id, String lang, boolean savePng,
                                     Supplier<? extends Screen> factory,
                                     BiConsumer<Screen, String> apply, boolean applyBeforeInit,
                                     String... states) {
        // The loop is the part (B11). apply runs BEFORE init here -- TSU does the opposite, and
        // which is right needs one real screenshot to settle, so both keep what they had.
        return com.manta.api.wiki.WikiCaptureLoop.captureStates(done, id, lang, factory, apply, applyBeforeInit,
                (screen, sid, st, lg) -> SasWikiCapture.captureScreen(screen, sid, st, lg, savePng),
                LOGGER, states);
    }
}
