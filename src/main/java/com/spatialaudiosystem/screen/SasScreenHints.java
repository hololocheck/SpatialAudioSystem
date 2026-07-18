package com.spatialaudiosystem.screen;

import belugalab.tsu.api.HintRegistry;

/**
 * Central registration of SAS screen element classes to their hint text and wiki page.
 *
 * <p>With the Hint toggle on, hovering a registered element shows its description in the lower
 * left, and F1 opens the wiki page named here. Registration is by CSS class, so the JSON layouts
 * need no changes. Called once from {@link ModScreens#onClientSetup}.
 */
public final class SasScreenHints {

    private static final String WIKI_MEMORY = "memory-device";
    private static final String WIKI_PLAYBACK = "playback-device";
    private static final String WIKI_SCHEDULE = "playback-device/schedule";
    private static final String WIKI_RANGE = "tools/range-board";

    private SasScreenHints() {}

    /** Guard against a second pass on resource reload / client soft-reset. */
    private static volatile boolean registered = false;

    public static void registerAll() {
        if (registered) return;
        registered = true;
        registerCommon();
        registerMemoryDevice();
        registerPlaybackDevice();
        registerSchedule();
    }

    private static void registerCommon() {
        HintRegistry.register("mc-popup-close", "sas.hint.close");
        HintRegistry.register("wiki-btn", "sas.hint.wiki");
        HintRegistry.register("hint-toggle-track", "sas.hint.toggle");
        HintRegistry.register("hint-toggle-knob", "sas.hint.toggle");
        HintRegistry.register("hint-toggle-label", "sas.hint.toggle");
        HintRegistry.register("owner-face-box", "sas.hint.owner");
        HintRegistry.register("owner-face-canvas", "sas.hint.owner");
    }

    private static void registerMemoryDevice() {
        HintRegistry.register("rec-jacket", "sas.hint.rec_jacket", WIKI_MEMORY);
        HintRegistry.register("rec-status", "sas.hint.rec_status", WIKI_MEMORY);
        HintRegistry.register("rec-file", "sas.hint.rec_file", WIKI_MEMORY);
        HintRegistry.register("rec-type", "sas.hint.rec_type", WIKI_MEMORY);
        HintRegistry.register("rec-duration", "sas.hint.rec_duration", WIKI_MEMORY);
        HintRegistry.register("rec-file-btn", "sas.hint.rec_file_btn", WIKI_MEMORY);
        HintRegistry.register("rec-start-btn", "sas.hint.rec_start", WIKI_MEMORY);
        HintRegistry.register("rec-clear-btn", "sas.hint.rec_clear", WIKI_MEMORY);
        HintRegistry.register("rec-play-btn", "sas.hint.rec_play", WIKI_MEMORY);
        HintRegistry.register("rec-stop-btn", "sas.hint.rec_stop", WIKI_MEMORY);
        HintRegistry.register("rec-arrow-track", "sas.hint.rec_progress", WIKI_MEMORY);
        HintRegistry.register("rec-input-slot", "sas.hint.rec_input", WIKI_MEMORY);
        HintRegistry.register("rec-output-slot", "sas.hint.rec_output", WIKI_MEMORY);
    }

    private static void registerPlaybackDevice() {
        HintRegistry.register("pb-jacket", "sas.hint.pb_jacket", WIKI_PLAYBACK);
        HintRegistry.register("pb-status", "sas.hint.pb_status", WIKI_PLAYBACK);
        HintRegistry.register("pb-file", "sas.hint.pb_file", WIKI_PLAYBACK);
        HintRegistry.register("pb-format", "sas.hint.pb_format", WIKI_PLAYBACK);
        HintRegistry.register("pb-atten-range", "sas.hint.pb_atten_range", WIKI_RANGE);
        HintRegistry.register("pb-play-btn", "sas.hint.pb_play", WIKI_PLAYBACK);
        HintRegistry.register("pb-stop-btn", "sas.hint.pb_stop", WIKI_PLAYBACK);
        HintRegistry.register("pb-atten-label", "sas.hint.pb_atten", WIKI_PLAYBACK);
        HintRegistry.register("pb-atten-track", "sas.hint.pb_atten", WIKI_PLAYBACK);
        HintRegistry.register("pb-atten-knob", "sas.hint.pb_atten", WIKI_PLAYBACK);
        HintRegistry.register("pb-range-label", "sas.hint.pb_showrange", WIKI_RANGE);
        HintRegistry.register("pb-range-track", "sas.hint.pb_showrange", WIKI_RANGE);
        HintRegistry.register("pb-range-knob", "sas.hint.pb_showrange", WIKI_RANGE);
        HintRegistry.register("pb-sched-btn", "sas.hint.pb_sched", WIKI_SCHEDULE);
        HintRegistry.register("pb-media-slot", "sas.hint.pb_media_slot", WIKI_PLAYBACK);
        HintRegistry.register("pb-media-slot-label", "sas.hint.pb_media_slot", WIKI_PLAYBACK);
        HintRegistry.register("pb-range-slot", "sas.hint.pb_range_slot", WIKI_RANGE);
        HintRegistry.register("pb-range-slot-label", "sas.hint.pb_range_slot", WIKI_RANGE);
    }

    private static void registerSchedule() {
        HintRegistry.register("pb-sched-close", "sas.hint.close", WIKI_SCHEDULE);
        HintRegistry.register("pb-add-entry-btn", "sas.hint.add_entry", WIKI_SCHEDULE);
        HintRegistry.register("pb-sched-playall-btn", "sas.hint.play_all", WIKI_SCHEDULE);
        HintRegistry.register("pb-sched-stop-btn", "sas.hint.stop_all", WIKI_SCHEDULE);
        HintRegistry.register("pb-entry-index", "sas.hint.entry_index", WIKI_SCHEDULE);
        HintRegistry.register("pb-count-display", "sas.hint.count", WIKI_SCHEDULE);
        HintRegistry.register("pb-entry-up-btn", "sas.hint.entry_up", WIKI_SCHEDULE);
        HintRegistry.register("pb-entry-down-btn", "sas.hint.entry_down", WIKI_SCHEDULE);
        HintRegistry.register("pb-entry-test-btn", "sas.hint.entry_test", WIKI_SCHEDULE);
        HintRegistry.register("pb-entry-del-btn", "sas.hint.entry_del", WIKI_SCHEDULE);
        HintRegistry.register("pb-media-slot-frame", "sas.hint.entry_slot", WIKI_SCHEDULE);
        HintRegistry.register("pb-media-info", "sas.hint.media_info", WIKI_SCHEDULE);
    }
}
