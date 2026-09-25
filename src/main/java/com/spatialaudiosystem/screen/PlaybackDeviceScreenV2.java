package com.spatialaudiosystem.screen;

import com.manta.api.controller.ToggleSwitchController;
import com.manta.api.screen.JsonLayoutEngine;
import com.manta.api.screen.JsonLayoutScreen;
import com.manta.api.screen.PageLayer;
import com.manta.api.state.BoolSlot;
import com.manta.api.state.ColorSlot;
import com.manta.api.state.MantaState;
import com.manta.api.state.NumberSlot;
import com.manta.api.state.TextSlot;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.client.ClientArtCache;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.menu.PlaybackDeviceMenu;
import com.spatialaudiosystem.network.PlaybackDeviceData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * Playback device screen, on Manta's JSON layout engine.
 *
 * <p>The main view holds the single-play media slot, the range board, the attenuation / range
 * toggles, and the §4.17 header (title left; hint / wiki / close cluster; owner face). The
 * "Playlist Schedule" button opens an overlay ported from TSU's railway-management announcement
 * editor (amber theme, entry rows with reorder / play-count / test / delete) — without the
 * condition, detection-card, or station-share features. The overlay's per-entry media slots are
 * the block entity's playlist slots, repositioned each frame over their row frames.
 */
public class PlaybackDeviceScreenV2 extends JsonLayoutScreen<PlaybackDeviceMenu> {

    /** Every value on this screen's pages is pushed (MANTA_7_CONCEPT §4.2): a page asks it nothing. */
    @Override
    protected boolean pushOnly() {
        return true;
    }

    private static final int COLOR_PLAYING = 0xFF55FF55;
    private static final int COLOR_STOPPED = 0xFFAAAAAA;
    private static final int COLOR_SCHED_ON = 0xFFFFC107;        // amber: the ♪ button is armed
    private static final int COLOR_SCHED_OFF_TEXT = 0xFF777777;  // grey: toggle it on first
    private static final int COLOR_SCHED_OFF_BORDER = 0xFF555555;
    private static final int FILE_MAX_W = 190;
    private static final int ROW_STRIDE = 35;      // matches playback-schedule.json
    private static final int FIRST_ROW_Y = 52;     // first entry row Y in the overlay
    private static final int SLOT_ROW_X = 171;     // media-slot-frame x(170) + 1
    private static final int SLOT_ROW_Y = 56;      // media-slot-frame y(55) + 1
    /** Rows on screen at once, in both dialogs; the rest scroll (16 entries, 16 rules). */
    private static final int VISIBLE_ROWS = 6;
    private static final int LIST_Y = 50;          // playback-schedule.json pb-list-bg
    private static final int LIST_H = 212;
    private static final int RS_LIST_Y = 56;       // playback-redstone.json pb-rs-list-bg
    private static final int RS_LIST_H = 208;
    private static final int THUMB_H = 20;         // scrollbar-thumb h in both layouts
    private static final long OPEN_ANIM_NS = 220_000_000L;
    private static final int PLAYING_HL_BG = 0x224FC3F7;
    private static final int PLAYING_HL_BORDER = 0xFF4FC3F7;

    /**
     * Multiplier prefix for a play count, as in ×3. Content typography rather than a control
     * symbol (R4.23.1), and the single literal the control-glyph ledger carries for this file —
     * every count is built from it so the ledger does not have to grow an entry per value.
     */
    private static final String TIMES = "×";
    /** Shown in place of the number when an entry plays endlessly. */
    private static final String ENDLESS_COUNT = "∞";

    private boolean attenuationOn;
    private boolean rangeVisible;
    private final com.manta.api.controller.OverlayController schedulePopup =
            new com.manta.api.controller.OverlayController();
    /** The redstone dialog. One overlay at a time: opening either closes the other. */
    private final com.manta.api.controller.OverlayController redstonePopup =
            new com.manta.api.controller.OverlayController();
    private long scheduleOpenedAtNanos = 0L;
    // A4.19: the viewport owns the offset; rows resolve as repeat index + offset.
    private final com.manta.api.controller.ScrollViewport scheduleScroll =
            new com.manta.api.controller.ScrollViewport(() -> be().getEntryCount(), VISIBLE_ROWS)
                    .activeWhen(() -> schedulePopup.isOpen());
    private final com.manta.api.controller.ScrollViewport redstoneScroll =
            new com.manta.api.controller.ScrollViewport(() -> be().getRedstoneRules().size(), VISIBLE_ROWS)
                    .activeWhen(() -> redstonePopup.isOpen());
    /** The device's name, edited in the title box (sound handy, 1.1.0); Enter saves, Esc cancels. */
    private final com.manta.api.controller.TextInputController nameInput =
            new com.manta.api.controller.TextInputController(
                    com.spatialaudiosystem.handy.SoundDeviceRegistry.MAX_NAME_CODE_POINTS, "")
                    .onSubmit(this::submitName)
                    .onEscape(this::cancelName);
    /**
     * The sound handy screen this one was opened from, when it was: drawn behind this dialog
     * and returned to on close, so opening a device never "closes" the handy (user's
     * real-device note 2026-09-05). Escape closes this screen first, as the player expects.
     */
    private SoundHandyScreen handyBehind;
    // A list that grew scrolls to its new last row, so an add past the window is seen.
    private int lastEntryCount = -1;
    private int lastRuleCount = -1;

    private final ToggleSwitchController attenuationToggle = new ToggleSwitchController(
            "pb-atten-track", "pb-atten-knob",
            () -> attenuationOn,
            v -> {
                attenuationOn = v;
                send("toggle-attenuation");
            });

    private final ToggleSwitchController rangeToggle = new ToggleSwitchController(
            "pb-range-track", "pb-range-knob",
            () -> rangeVisible,
            v -> {
                rangeVisible = v;
                send("toggle-range-display");
            });

    /** Schedule mode: bars the media slot. Flipped from inside the schedule popup now. */
    private boolean scheduleModeOn;
    /** Endless play for the single medium, independent of the schedule's own endless entry. */
    private boolean normalLoopOn;

    /**
     * "Play with the schedule", living inside the schedule popup.
     *
     * <p>It used to sit on the main screen beside the ♪ button, where it read as arming that
     * button. Moved here because its real job is the other one it always had: deciding whether
     * the schedule owns playback, which is what bars the single media slot. Keeping media in a
     * schedule you are not currently playing is a reasonable thing to want.
     */
    private final ToggleSwitchController redstoneToggle = new ToggleSwitchController(
            "pb-rs-enabled-track", "pb-rs-enabled-knob",
            () -> be().isRedstoneEnabled(),
            v -> {
                be().setRedstoneEnabled(v);   // optimistic on the client entity; the update tag confirms
                send("redstone-rule", PlaybackDeviceData.RULE_TOGGLE_ENABLED, 0, 0);
            });

    private final ToggleSwitchController schedulePlaybackToggle = new ToggleSwitchController(
            "pb-schedplay-track", "pb-schedplay-knob",
            () -> scheduleModeOn,
            v -> {
                scheduleModeOn = v;
                send("playlist", PlaybackDeviceData.PLAYLIST_TOGGLE_MODE, 0, 0);
            });

    // ===== Manta 7 push (Phase 4, 2026-09-23) =====
    // Every value of the three pages is WRITTEN here, each frame before the engine draws (render):
    // the name box follows every key, so a frame is the cadence the pull had - an unchanged value
    // costs nothing. The screen overrides no getDynamic*. A page's handles are taken when it opens
    // (pageOpened): the main dialog once, the schedule / redstone dialog each time the overlay
    // opens. The rows of both lists are pushed per row (row r shows list entry r + the window's
    // offset, as entryAtRow / ruleIndexAtRow resolve it); a row the pull answered with null is
    // pushed "none" (clear), so it keeps the template's own colour. The toggles' knobs and the
    // scroll thumbs are OFFSETS from each node's static value (the pull answered default + delta).
    // The hint toggle, the entrance animations and the transitions are the base screen's.
    private MantaState pushed;
    private TextSlot tTitle, tStatus, tFile, tFormat, tAttenRange;
    private ColorSlot cTitleBorder, cStatus, cAttenTrack, cAttenKnob, cRangeTrack, cRangeKnob,
            cLoopColor, cLoopBorder, cSchedColor, cSchedBorder, cSchedBg, cAttenRangeColor, cOwner;
    private NumberSlot nAttenKnobX, nRangeKnobX;
    private BoolSlot bMediaLocked;
    /** The schedule dialog's page while it is the open overlay, else null. */
    private MantaState pushedSched;
    private NumberSlot sCount, sPlayKnobX, sThumbY, sFrameY;
    private TextSlot sIndex, sCountText, sMediaInfo;
    private ColorSlot sRowBg, sRowBorder, sFrameBg, sFrameBorder, sPlayTrack, sPlayKnob;
    private BoolSlot sFrameVisible, sScrollbar;
    /** The redstone dialog's page while it is the open overlay, else null. */
    private MantaState pushedRs;
    private NumberSlot rCount, rEnabledKnobX, rThumbY;
    private TextSlot rTrigger, rStrength, rDelay, rLength, rEntry;
    private ColorSlot rLengthColor, rEntryColor, rEnabledTrack, rEnabledKnob;
    private BoolSlot rScrollbar;

    public PlaybackDeviceScreenV2(PlaybackDeviceMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        // Constructed before setScreen: the handy is still the current screen exactly when this
        // one was opened from it, and only then does the handoff apply (a refused OPEN leaves a
        // stale handoff that a device opened from the world must not adopt - review 2026-09-05).
        Minecraft mc = Minecraft.getInstance();
        this.handyBehind = SoundHandyScreen.takeBehindIf(mc.screen);
    }

    @Override
    protected void init() {
        super.init();
        // The handoff was claimed in the constructor; a resize re-runs init and keeps the handy in step.
        if (handyBehind != null) handyBehind.resize(this.minecraft, this.width, this.height);
        PlaybackDeviceBlockEntity be = this.menu.getBlockEntity();
        this.attenuationOn = be.isAttenuationMode();
        this.rangeVisible = be.isShowRange();
        this.scheduleModeOn = be.isScheduleMode();
        this.normalLoopOn = be.isNormalLoop();
    }

    /**
     * The real close: the container closes as usual (the server learns the menu is gone), and
     * when the handy opened us the player lands back on it instead of on the world.
     */
    @Override
    protected void performClose() {
        SoundHandyScreen back = handyBehind;
        handyBehind = null;
        super.performClose();
        if (back != null && this.minecraft != null) this.minecraft.setScreen(back);
    }

    /**
     * The handy panel drawn behind this dialog is chrome the layout knows nothing about, so JEI
     * put its item list right over it (user's real-device note 2026-09-05). Declared here, JEI
     * keeps clear of it for as long as the handy is behind.
     *
     * <p>Declared as the whole band from this dialog's right edge to the screen's edge at the
     * handy's height, not just the panel: JEI crops its area to the larger free rectangle, and
     * with the panel alone that was the gap between this dialog and the handy (round 5). With
     * the band, the one free rectangle right of the dialog is the strip above the handy - where
     * the list belongs.
     */
    @Override
    public java.util.List<int[]> extraOccupiedAreas() {
        if (handyBehind == null) return java.util.List.of();
        int[] r = handyBehind.screenRect();
        int left = Math.min(r[0], dialogLocalToScreenX(dialogW()));
        int right = Math.max(r[0] + r[2], this.width);
        int bottom = Math.max(r[1] + r[3], this.height);
        return java.util.List.of(new int[] {left, r[1], right - left, bottom - r[1]});
    }

    @Override
    protected String wikiPageId() { return "playback-device"; }

    /**
     * Wiki capture: a stand-alone screen over a dummy block entity with a loaded medium, a range
     * board and a few schedule entries, so both the main shot and the schedule shot show content.
     */
    public static PlaybackDeviceScreenV2 wikiCreate() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        PlaybackDeviceBlockEntity be = new PlaybackDeviceBlockEntity(mc.player.blockPosition(),
                com.spatialaudiosystem.block.ModBlocks.PLAYBACK_DEVICE.get().defaultBlockState());
        be.setLevel(mc.level);
        be.getInventory().setStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT,
                sampleMedium("departure_melody.mp3"));
        be.getInventory().setStackInSlot(PlaybackDeviceBlockEntity.RANGE_SLOT,
                new ItemStack(ModItems.RANGE_BOARD.get()));
        String[] entries = {"chime.ogg", "announce_next.mp3", "door_close.wav"};
        for (int i = 0; i < entries.length; i++) {
            be.addEntry();
            be.getPlaylist().setStackInSlot(i, sampleMedium(entries[i]));
            be.setPlayCount(i, i + 1);
        }
        Inventory inv = new Inventory(mc.player);   // empty: keep the player's own items out of the shot
        return new PlaybackDeviceScreenV2(new PlaybackDeviceMenu(0, inv, be), inv,
                Component.translatable("block.spatialaudiosystem.playback_device"));
    }

    private static ItemStack sampleMedium(String fileName) {
        ItemStack s = new ItemStack(ModItems.RECORDING_MEDIUM.get());
        s.set(ModDataComponents.AUDIO_FILE_NAME, fileName);
        s.set(ModDataComponents.AUDIO_FORMAT, fileName.substring(fileName.lastIndexOf('.') + 1));
        return s;
    }

    /** Wiki capture: force the shot to the main view or to the armed, open schedule popup. */
    public void wikiApplyState(String state) {
        boolean sched = "schedule".equals(state);
        schedulePopup.setOpen(sched);
        scheduleModeOn = sched;
        if (sched && !be().isScheduleMode()) be().toggleScheduleMode();   // dummy client BE: no packets
        scheduleOpenedAtNanos = 0L;   // no open animation, and slot items draw immediately
    }

    @Override
    protected String layoutJson() { return SasLayouts.load("layouts/playback-device.json"); }

    @Override
    protected String overlayJson() {
        if (schedulePopup.isOpen()) return SasLayouts.load("layouts/playback-schedule.json");
        if (redstonePopup.isOpen()) return SasLayouts.load("layouts/playback-redstone.json");
        return null;
    }

    /** The rule index under the repeat row being resolved (window offset applied), or -1. */
    private int ruleIndexAtRow() {
        int idx = JsonLayoutEngine.currentRepeatIndex();
        return idx < 0 ? -1 : idx + redstoneScroll.offset();
    }

    /** The schedule entry under the repeat row being resolved (window offset applied), or -1. */
    private int entryAtRow() {
        int idx = JsonLayoutEngine.currentRepeatIndex();
        return idx < 0 ? -1 : idx + scheduleScroll.offset();
    }

    /** The entry's row inside the window, or -1 when it is scrolled out of it. */
    private int windowRowOf(int entry) {
        int row = entry - scheduleScroll.offset();
        return entry >= 0 && row >= 0 && row < VISIBLE_ROWS ? row : -1;
    }

    /** The entry scope of a rule, for the row: "any" or #n. */
    private static String entryScopeText(com.spatialaudiosystem.redstone.RedstoneRule rule) {
        if (rule.entry() == com.spatialaudiosystem.redstone.RedstoneRule.ANY_ENTRY) {
            return Component.translatable("gui.spatialaudiosystem.rs_entry_any").getString();
        }
        return Component.translatable("gui.spatialaudiosystem.rs_entry_n", rule.entry()).getString();
    }

    private static String seconds(int ticks) {
        return Component.translatable("gui.spatialaudiosystem.rs_seconds",
                String.format(java.util.Locale.ROOT, "%.1f", ticks / 20.0)).getString();
    }

    @Override
    protected int[] overlayDefaultPosition(int overlayW, int overlayH) {
        // Open the schedule popup beside the main dialog so the player inventory stays reachable
        // for dragging recording media into the entry slots (matches TSU's side popups).
        return new int[]{ dialogLocalToScreenX(this.imageWidth + 8), dialogLocalToScreenY(0) };
    }

    private net.minecraft.core.BlockPos pos() {
        return this.menu.getBlockEntity().getBlockPos();
    }

    /** Palette: a wheel-editable value is #FFD54F; a value not in effect is the hint grey. */
    private static final int COLOR_RANGE_VALUE = 0xFFFFD54F;
    private static final int COLOR_RANGE_INACTIVE = 0xFF888888;

    /** True while a range board with both corners set is in the slot: its box is then in effect. */
    private boolean rangeBoardInserted() {
        return com.spatialaudiosystem.item.RangeBoardItem.hasRange(
                be().getInventory().getStackInSlot(PlaybackDeviceBlockEntity.RANGE_SLOT));
    }

    private PlaybackDeviceBlockEntity be() {
        return this.menu.getBlockEntity();
    }

    // getDynamicText / Color / Number / Bool: gone - see pushAll(). The expressions are the ones the
    // pull answered with, moved into the helpers below; a row's index is now the loop's, not
    // currentRepeatIndex's.

    @Override
    protected void pageOpened(Object page, PageLayer layer) {
        if (layer == PageLayer.PRIMARY) {
            pushed = MantaState.of(page);
            tTitle = pushed.text("pb-title");
            tStatus = pushed.text("pb-status");
            tFile = pushed.text("pb-file");
            tFormat = pushed.text("pb-format");
            tAttenRange = pushed.text("pb-atten-range");
            cTitleBorder = pushed.color("pb-title-border");
            cStatus = pushed.color("pb-status-color");
            cAttenTrack = pushed.color("pb-atten-track-bg");
            cAttenKnob = pushed.color("pb-atten-knob-bg");
            cRangeTrack = pushed.color("pb-range-track-bg");
            cRangeKnob = pushed.color("pb-range-knob-bg");
            cLoopColor = pushed.color("pb-loop-btn-color");
            cLoopBorder = pushed.color("pb-loop-btn-border");
            cSchedColor = pushed.color("pb-sched-btn-color");
            cSchedBorder = pushed.color("pb-sched-btn-border");
            cSchedBg = pushed.color("pb-sched-btn-bg");
            cAttenRangeColor = pushed.color("pb-atten-range-color");
            cOwner = pushed.color("owner-border");
            nAttenKnobX = pushed.number("pb-atten-knob-x");
            nRangeKnobX = pushed.number("pb-range-knob-x");
            bMediaLocked = pushed.bool("pb-media-locked");
            pushed.textByKeyOnly();
        } else if (layer == PageLayer.OVERLAY && schedulePopup.isOpen()) {
            // overlayJson(): the schedule wins when both flags are up, so the page is its layout.
            pushedRs = null;
            pushedSched = MantaState.of(page);
            sCount = pushedSched.number("pb-entry-count");
            sPlayKnobX = pushedSched.number("pb-schedplay-knob-x");
            sThumbY = pushedSched.number("pb-sched-thumb-y");
            sFrameY = pushedSched.number("pb-playing-frame-y");
            sIndex = pushedSched.text("pb-entry-index");
            sCountText = pushedSched.text("pb-count-display");
            sMediaInfo = pushedSched.text("pb-media-info");
            sRowBg = pushedSched.color("pb-entry-row-bg");
            sRowBorder = pushedSched.color("pb-entry-row-border");
            sFrameBg = pushedSched.color("pb-playing-frame-bg");
            sFrameBorder = pushedSched.color("pb-playing-frame-border");
            sPlayTrack = pushedSched.color("pb-schedplay-track-bg");
            sPlayKnob = pushedSched.color("pb-schedplay-knob-bg");
            sFrameVisible = pushedSched.bool("pb-playing-frame-visible");
            sScrollbar = pushedSched.bool("pb-sched-scrollbar");
            pushedSched.textByKeyOnly();
            // The row slots are the screen's with no entry yet (an empty schedule has no row to push): none is
            // what the pull answered outside a row. Each row that exists is pushed in pushSchedule.
            pushedSched.clear(sIndex);
            pushedSched.clear(sCountText);
            pushedSched.clear(sMediaInfo);
            pushedSched.clear(sRowBg);
            pushedSched.clear(sRowBorder);
        } else if (layer == PageLayer.OVERLAY && redstonePopup.isOpen()) {
            pushedSched = null;
            pushedRs = MantaState.of(page);
            rCount = pushedRs.number("pb-rs-count");
            rEnabledKnobX = pushedRs.number("pb-rs-enabled-knob-x");
            rThumbY = pushedRs.number("pb-rs-thumb-y");
            rTrigger = pushedRs.text("pb-rs-trigger");
            rStrength = pushedRs.text("pb-rs-strength");
            rDelay = pushedRs.text("pb-rs-delay");
            rLength = pushedRs.text("pb-rs-length");
            rEntry = pushedRs.text("pb-rs-entry");
            rLengthColor = pushedRs.color("pb-rs-length-color");
            rEntryColor = pushedRs.color("pb-rs-entry-color");
            rEnabledTrack = pushedRs.color("pb-rs-enabled-track-bg");
            rEnabledKnob = pushedRs.color("pb-rs-enabled-knob-bg");
            rScrollbar = pushedRs.bool("pb-rs-scrollbar");
            pushedRs.textByKeyOnly();
            // As the schedule's: the rules can be empty, and each row that exists is pushed in pushRedstone.
            pushedRs.clear(rTrigger);
            pushedRs.clear(rStrength);
            pushedRs.clear(rDelay);
            pushedRs.clear(rLength);
            pushedRs.clear(rEntry);
            pushedRs.clear(rLengthColor);
        }
        pushAll();
    }

    /** Every value of every open page, from the expressions the pull answered with. An unchanged value costs nothing. */
    private void pushAll() {
        pushMain();
        pushSchedule();
        pushRedstone();
    }

    private void pushMain() {
        if (pushed == null || !pushed.isOpen()) return;
        PlaybackDeviceBlockEntity be = be();
        pushed.set(tTitle, titleText());
        pushed.set(tStatus, be.isPlaying()
                ? Component.translatable("gui.spatialaudiosystem.status_playing").getString()
                : Component.translatable("gui.spatialaudiosystem.status_stopped").getString());
        pushed.set(tFile, fileText());
        pushed.set(tFormat, formatText());
        pushed.set(tAttenRange, attenuationRangeText());
        if (nameInput.isFocused()) {
            pushed.set(cTitleBorder, 0xFF4FC3F7);
        } else {
            pushed.clear(cTitleBorder);   // the pull's defaultArgb: the box's own border
        }
        pushed.set(cStatus, be.isPlaying() ? COLOR_PLAYING : COLOR_STOPPED);
        pushed.set(cAttenTrack, attenuationToggle.trackBg());
        pushed.set(cAttenKnob, attenuationToggle.knobBg());
        pushed.set(cRangeTrack, rangeToggle.trackBg());
        pushed.set(cRangeKnob, rangeToggle.knobBg());
        pushed.set(cLoopColor, normalLoopOn ? COLOR_SCHED_ON : COLOR_SCHED_OFF_TEXT);
        pushed.set(cLoopBorder, normalLoopOn ? COLOR_SCHED_ON : COLOR_SCHED_OFF_BORDER);
        pushed.set(cSchedColor, scheduleModeOn ? COLOR_SCHED_ON : COLOR_SCHED_OFF_TEXT);
        pushed.set(cSchedBorder, scheduleModeOn ? COLOR_SCHED_ON : COLOR_SCHED_OFF_BORDER);
        pushed.set(cSchedBg, scheduleModeOn ? 0x1AFFC107 : 0x0DFFFFFF);
        // Amber only while the sound is shaped by this value (no box, attenuation on).
        int attenRangeColor = PlaybackDeviceBlockEntity.presetInEffect(rangeBoardInserted(), be().isAttenuationMode())
                ? COLOR_RANGE_VALUE : COLOR_RANGE_INACTIVE;
        pushed.set(cAttenRangeColor, attenRangeColor);
        pushed.set(cOwner, com.manta.api.hud.OwnerAccess.ringColor(be.isPrivateMode()));
        // Offsets: knobX is default + delta, so its delta is its answer for 0.
        pushed.setOffset(nAttenKnobX, attenuationToggle.knobX(0));
        pushed.setOffset(nRangeKnobX, rangeToggle.knobX(0));
        // The media-slot ✕ is an engine element so it rides the open/close animation;
        // a Java overdraw sat still while the rest of the dialog scaled.
        pushed.set(bMediaLocked, scheduleModeOn);
    }

    private void pushSchedule() {
        if (pushedSched == null || !pushedSched.isOpen()) return;
        MantaState st = pushedSched;
        PlaybackDeviceBlockEntity be = be();
        int count = schedulePopup.isOpen() ? scheduleScroll.rowCount() : 0;
        st.set(sCount, count);
        int playing = be.getPlayingEntry();
        for (int r = 0; r < count; r++) {
            int idx = r + scheduleScroll.offset();
            st.set(sIndex, r, String.valueOf(idx + 1));
            st.set(sCountText, r, TIMES + (be.isLoopEntry(idx)
                    ? ENDLESS_COUNT
                    : String.valueOf(be.getPlayCount(idx))));
            st.set(sMediaInfo, r, mediaInfoText(idx));
            if (idx == playing) {
                st.set(sRowBg, r, PLAYING_HL_BG);
                st.set(sRowBorder, r, PLAYING_HL_BORDER);
            } else {
                st.clear(sRowBg, r);   // the pull answered null: the row's own colour
                st.clear(sRowBorder, r);
            }
        }
        st.set(sFrameBg, PLAYING_HL_BG);
        st.set(sFrameBorder, PLAYING_HL_BORDER);
        st.set(sPlayTrack, schedulePlaybackToggle.trackBg());
        st.set(sPlayKnob, schedulePlaybackToggle.knobBg());
        st.setOffset(sPlayKnobX, schedulePlaybackToggle.knobX(0));
        st.setOffset(sThumbY, scheduleScroll.thumbY(0, LIST_H - 2, THUMB_H));
        st.set(sFrameY, FIRST_ROW_Y + Math.max(0, windowRowOf(playing)) * ROW_STRIDE);
        st.set(sFrameVisible, schedulePopup.isOpen() && windowRowOf(playing) >= 0);
        st.set(sScrollbar, scheduleScroll.needsScrollbar());
    }

    private void pushRedstone() {
        if (pushedRs == null || !pushedRs.isOpen()) return;
        MantaState st = pushedRs;
        int count = redstonePopup.isOpen() ? redstoneScroll.rowCount() : 0;
        st.set(rCount, count);
        java.util.List<com.spatialaudiosystem.redstone.RedstoneRule> rules = be().getRedstoneRules();
        for (int r = 0; r < count; r++) {
            int idx = r + redstoneScroll.offset();
            com.spatialaudiosystem.redstone.RedstoneRule rule = idx >= 0 && idx < rules.size() ? rules.get(idx) : null;
            st.set(rTrigger, r, rule == null ? "" : Component.translatable(
                    "gui.spatialaudiosystem.rs_trigger_" + rule.trigger().name().toLowerCase(java.util.Locale.ROOT)).getString());
            st.set(rStrength, r, rule == null ? "" : String.valueOf(rule.strength()));
            st.set(rDelay, r, rule == null ? "" : seconds(rule.delayTicks()));
            // A lamp has no length; the column shows a dash and is greyed (colorKey).
            st.set(rLength, r, rule == null ? "" : rule.trigger().isPulse() ? seconds(rule.lengthTicks())
                    : Component.translatable("gui.spatialaudiosystem.rs_na").getString());
            st.set(rEntry, r, rule == null ? "" : entryScopeText(rule));
            st.set(rLengthColor, r, rule != null && rule.trigger().isPulse() ? COLOR_RANGE_VALUE : COLOR_RANGE_INACTIVE);
        }
        // Editable either way; grey says it has no effect until the schedule plays.
        st.set(rEntryColor, scheduleModeOn ? COLOR_RANGE_VALUE : COLOR_RANGE_INACTIVE);
        st.set(rEnabledTrack, redstoneToggle.trackBg());
        st.set(rEnabledKnob, redstoneToggle.knobBg());
        st.setOffset(rEnabledKnobX, redstoneToggle.knobX(0));
        st.setOffset(rThumbY, redstoneScroll.thumbY(0, RS_LIST_H - 2, THUMB_H));
        st.set(rScrollbar, redstoneScroll.needsScrollbar());
    }

    private String titleText() {
        if (nameInput.isFocused()) return nameInput.display();
        String name = be().getDeviceName();
        return name != null ? name
                : Component.translatable("gui.spatialaudiosystem.playback_name_placeholder").getString();
    }

    private String fileText() {
        PlaybackDeviceBlockEntity be = be();
        if (scheduleModeOn) {
            int tracks = 0;
            for (int i = 0; i < be.getEntryCount(); i++) {
                if (!be.getPlaylist().getStackInSlot(i).isEmpty()) tracks++;
            }
            return Component.translatable(
                    "gui.spatialaudiosystem.tracks_scheduled", tracks).getString();
        }
        ItemStack media = be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT);
        String name = media.get(ModDataComponents.AUDIO_FILE_NAME);
        if (name == null) {
            return Component.translatable("gui.spatialaudiosystem.no_media").getString();
        }
        return trimToFit(Component.translatable(
                "gui.spatialaudiosystem.file_prefix", name).getString(), FILE_MAX_W);
    }

    private String formatText() {
        PlaybackDeviceBlockEntity be = be();
        if (scheduleModeOn) {
            int idx = be.getPlayingEntry();
            String name = idx < 0 ? null
                    : be.getPlaylist().getStackInSlot(idx).get(ModDataComponents.AUDIO_FILE_NAME);
            if (name == null) return "";
            return trimToFit(Component.translatable(
                    "gui.spatialaudiosystem.now_playing", name).getString(), FILE_MAX_W);
        }
        ItemStack media = be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT);
        if (!media.has(ModDataComponents.AUDIO_FILE_NAME)) return "";
        String fmt = media.getOrDefault(ModDataComponents.AUDIO_FORMAT, "unknown");
        return Component.translatable(
                "gui.spatialaudiosystem.format_prefix", fmt.toUpperCase()).getString();
    }

    private String attenuationRangeText() {
        PlaybackDeviceBlockEntity be = be();
        if (rangeBoardInserted()) {
            return Component.translatable("gui.spatialaudiosystem.range_board_active").getString();
        }
        int range = be.getAttenuationRange();
        String text = Component.translatable(
                "gui.spatialaudiosystem.attenuation_range", range).getString();
        if (!be.isAttenuationMode()) {
            // Editable, kept, but not what the sound uses until attenuation is on. A
            // short suffix instead of the jukebox label: the row is 192 px wide and
            // nothing clips it, so the first version's long suffix ran past the dialog.
            text += Component.translatable("gui.spatialaudiosystem.range_unused").getString();
        } else if (range == com.spatialaudiosystem.audio.SpatialGain.JUKEBOX_RANGE_BLOCKS) {
            text += Component.translatable("gui.spatialaudiosystem.range_jukebox").getString();
        }
        return trimToFit(text, FILE_MAX_W);
    }

    private String mediaInfoText(int idx) {
        String name = be().getPlaylist().getStackInSlot(idx).get(ModDataComponents.AUDIO_FILE_NAME);
        if (name == null) return Component.translatable("gui.spatialaudiosystem.entry_empty").getString();
        return trimToFit(name, 148);
    }

    @Override
    public boolean onElementWheel(String[] classes, String key, int mouseX, int mouseY, double scrollY) {
        // The dialog roots carry the list wheel: a row's own value cells are tried first by the
        // engine (innermost first), so this only sees a wheel that no cell took. Outside the
        // list it scrolls nothing (the base still swallows a wheel inside the overlay).
        if ("pb-sched-scroll".equals(key)) {
            if (mouseY < LIST_Y || mouseY >= LIST_Y + LIST_H) return false;
            scheduleScroll.scroll(scrollY > 0 ? -1 : 1);
            return true;
        }
        if ("pb-rs-scroll".equals(key)) {
            if (mouseY < RS_LIST_Y || mouseY >= RS_LIST_Y + RS_LIST_H) return false;
            redstoneScroll.scroll(scrollY > 0 ? -1 : 1);
            return true;
        }
        if (key != null && key.startsWith("pb-rs-")) {
            int idx = ruleIndexAtRow();
            if (idx < 0) return false;
            int delta = scrollY > 0 ? 1 : -1;   // R4.13.0.4
            int op;
            switch (key) {
                case "pb-rs-trigger-wheel"  -> { be().cycleRedstoneTrigger(idx, delta);   op = PlaybackDeviceData.RULE_CYCLE_TRIGGER; }
                case "pb-rs-strength-wheel" -> { be().adjustRedstoneStrength(idx, delta); op = PlaybackDeviceData.RULE_ADJUST_STRENGTH; }
                case "pb-rs-delay-wheel"    -> { be().adjustRedstoneDelay(idx, delta);    op = PlaybackDeviceData.RULE_ADJUST_DELAY; }
                case "pb-rs-length-wheel"   -> { be().adjustRedstoneLength(idx, delta);   op = PlaybackDeviceData.RULE_ADJUST_LENGTH; }
                case "pb-rs-entry-wheel"    -> { be().adjustRedstoneEntry(idx, delta);    op = PlaybackDeviceData.RULE_ADJUST_ENTRY; }
                default -> { return false; }
            }
            // Optimistic on the client entity above; the server clamps and its update tag confirms.
            send("redstone-rule", op, idx, delta);
            return true;
        }
        if ("pb-range-wheel".equals(key)) {
            // Consumed even when a board is inserted: the value is not in effect then, and
            // letting the wheel fall through would change something else under the cursor.
            if (rangeBoardInserted()) return true;
            int delta = scrollY > 0 ? 1 : -1;   // R4.13.0.4
            int next = PlaybackDeviceBlockEntity.clampRange(be().getAttenuationRange() + delta);
            be().setAttenuationRange(next);     // optimistic; the server clamps and confirms
            send("set-attenuation-range", next);
            return true;
        }
        if ("pb-count-wheel".equals(key)) {
            int idx = entryAtRow();
            if (idx < 0) return false;
            int delta = scrollY > 0 ? 1 : -1;
            send("playlist", PlaybackDeviceData.PLAYLIST_ADJUST_PLAYCOUNT, idx, delta);
            be().setPlayCount(idx, be().getPlayCount(idx) + delta);   // optimistic; server confirms
            return true;
        }
        return false;
    }

    @Override
    protected void handleMainClick(String[] classes, int mouseX, int mouseY, int button) {
        if (attenuationToggle.handleClick(classes)) return;
        if (rangeToggle.handleClick(classes)) return;
        if (schedulePlaybackToggle.handleClick(classes)) return;
        if (redstonePopup.isOpen() && redstoneToggle.handleClick(classes)) return;
        if (com.manta.api.hud.OwnerAccess.isFaceClick(classes)) {   // toggle public/private
            sendButtonClick(com.manta.api.hud.OwnerAccess.TOGGLE_BUTTON);
            return;
        }
        for (String c : classes) {
            switch (c) {
                case "pb-title": beginName(); return;
                case "mc-popup-close": onClose(); return;
                case "pb-play-btn":
                    if (scheduleModeOn) {
                        // In schedule mode the main play button drives the schedule too.
                        send("playlist", PlaybackDeviceData.PLAYLIST_PLAY_ALL, 0, 0);
                    } else {
                        send("playback", true);
                    }
                    return;
                case "pb-loop-btn":
                    // Reaches the sound that is playing: off lets every listener finish the
                    // current pass and stops the device; on makes a playing one-shot endless.
                    // The server owns both halves (see toggleNormalLoop).
                    normalLoopOn = !normalLoopOn;
                    send("playlist", PlaybackDeviceData.PLAYLIST_TOGGLE_LOOP, 0, 0);
                    return;
                case "pb-stop-btn":
                case "pb-sched-stop-btn":
                    // One stop for everything: halts the single medium or a running playlist sequence.
                    send("playlist", PlaybackDeviceData.PLAYLIST_STOP, 0, 0);
                    return;
                case "pb-sched-playall-btn":
                    send("playlist", PlaybackDeviceData.PLAYLIST_PLAY_ALL, 0, 0);
                    return;
                case "pb-redstone-btn":
                    // The button is a canvas node (self-clickable in the engine), so this is
                    // the class the click arrives with.
                    closeSchedule();
                    redstonePopup.setOpen(true);
                    return;
                case "pb-rs-close":
                    closeRedstone();
                    return;
                case "pb-rs-add-btn":
                    be().addRedstoneRule();   // optimistic; refused past sixteen on both sides
                    send("redstone-rule", PlaybackDeviceData.RULE_ADD, 0, 0);
                    return;
                case "pb-rs-up-btn":
                    moveRule(-1);
                    return;
                case "pb-rs-down-btn":
                    moveRule(1);
                    return;
                case "pb-rs-del-btn": {
                    int idx = ruleIndexAtRow();
                    if (idx >= 0) {
                        be().removeRedstoneRule(idx);
                        send("redstone-rule", PlaybackDeviceData.RULE_REMOVE, idx, 0);
                    }
                    return;
                }
                case "pb-sched-btn":
                    redstonePopup.setOpen(false);
                    schedulePopup.setOpen(true);
                    scheduleOpenedAtNanos = System.nanoTime();
                    return;
                case "pb-sched-close":
                    closeSchedule();
                    return;
                case "pb-add-entry-btn":
                    send("playlist", PlaybackDeviceData.PLAYLIST_ADD_ENTRY, 0, 0);
                    return;
                case "pb-entry-up-btn": {
                    int idx = entryAtRow();
                    if (idx > 0) send("playlist", PlaybackDeviceData.PLAYLIST_REORDER, idx, idx - 1);
                    return;
                }
                case "pb-entry-down-btn": {
                    int idx = entryAtRow();
                    if (idx >= 0 && idx + 1 < be().getEntryCount()) {
                        send("playlist", PlaybackDeviceData.PLAYLIST_REORDER, idx, idx + 1);
                    }
                    return;
                }
                case "pb-entry-stop-btn":
                    // The row's stop: the same stop as the header's, the device plays one thing.
                    send("playlist", PlaybackDeviceData.PLAYLIST_STOP, 0, 0);
                    return;
                case "pb-entry-test-btn": {
                    int idx = entryAtRow();
                    if (idx >= 0) send("playlist", PlaybackDeviceData.PLAYLIST_TEST, idx, 0);
                    return;
                }
                case "pb-entry-del-btn": {
                    int idx = entryAtRow();
                    if (idx >= 0) send("playlist", PlaybackDeviceData.PLAYLIST_REMOVE_ENTRY, idx, 0);
                    return;
                }
                default:
            }
        }
    }

    /** A rule swaps places with its neighbour; at either end the click does nothing. */
    private void moveRule(int delta) {
        int idx = ruleIndexAtRow();
        int to = idx + delta;
        if (idx < 0 || to < 0 || to >= be().getRedstoneRules().size()) return;
        be().moveRedstoneRule(idx, delta);   // optimistic; the server does the same swap
        send("redstone-rule", PlaybackDeviceData.RULE_MOVE, idx, delta);
    }

    private void closeSchedule() {
        schedulePopup.setOpen(false);
        scheduleOpenedAtNanos = 0L;
        hideScheduleSlots();
    }

    private void closeRedstone() {
        redstonePopup.setOpen(false);
    }

    /**
     * 基底の ESC / {@code mc-popup-close} が最初に呼ぶ hook (R4.17.1)。
     *
     * <p>2026-09-13 まで ESC を自前で横取りしていたので、<b>枠の × では popup が閉じなかった</b>。
     */
    @Override
    protected boolean closeOpenOverlay() {
        if (schedulePopup.isOpen()) { closeSchedule(); return true; }
        if (redstonePopup.isOpen()) { closeRedstone(); return true; }
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (handyBehind != null) handyBehind.renderBehind(g, partialTick);
        followListSizes();
        if (schedulePopup.isOpen()) positionScheduleSlots(); else hideScheduleSlots();
        pushAll();
        super.render(g, mouseX, mouseY, partialTick);
        if (schedulePopup.isOpen()) {
            renderScheduleOverlayItems(g, mouseX, mouseY);
            renderCarriedAbovePopup(g, mouseX, mouseY);
            renderHoveredPlaylistTooltip(g, mouseX, mouseY);
        }
    }

    /**
     * An added entry or rule lands at the end of its list, which may be below the window:
     * scroll there when a list grows. When one shrinks, the offset is re-clamped so the
     * window never points past the end.
     */
    private void followListSizes() {
        int entries = be().getEntryCount();
        if (entries != lastEntryCount) {
            if (entries > lastEntryCount && lastEntryCount >= 0) {
                scheduleScroll.setOffset(entries - VISIBLE_ROWS);
            }
            scheduleScroll.clamp();
            lastEntryCount = entries;
        }
        int rules = be().getRedstoneRules().size();
        if (rules != lastRuleCount) {
            if (rules > lastRuleCount && lastRuleCount >= 0) {
                redstoneScroll.setOffset(rules - VISIBLE_ROWS);
            }
            redstoneScroll.clamp();
            lastRuleCount = rules;
        }
    }

    /**
     * Vanilla draws the carried stack under the popup panel, so an item dragged over the schedule
     * disappeared behind it. Draw it again above the panel — same spot and scale, so nothing shifts.
     */
    private void renderCarriedAbovePopup(GuiGraphics g, int mouseX, int mouseY) {
        ItemStack carried = this.menu.getCarried();
        if (carried.isEmpty()) return;
        // slot items 700 < carried 800 < tooltip 900
        com.manta.api.render.ItemDraw.carried(g, this.font, carried, mouseX, mouseY, dialogScale(), 800);
    }

    /** Base tooltips render under the popup too; redraw the hovered playlist slot's above it. */
    private void renderHoveredPlaylistTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!this.menu.getCarried().isEmpty()) return;
        Slot slot = hoveredPlaylistSlot(mouseX, mouseY);
        if (slot == null || !slot.hasItem()) return;
        ItemStack stack = slot.getItem();
        g.pose().pushPose();
        g.pose().translate(0, 0, 900);
        com.manta.api.render.ItemDraw.tooltip(g, this.font, this.getTooltipFromContainerItem(stack),
                stack.getTooltipImage(), stack, mouseX, mouseY);
        g.pose().popPose();
    }

    /**
     * Move each active playlist slot over its overlay row frame; the rest go off-screen.
     *
     * <p>Coordinates are raw screen offsets from {@code leftPos} (TSU's convention): popup slots
     * are never reached through vanilla's transformed click path — {@link #mouseClicked} hit-tests
     * them with the raw mouse and issues the click itself — so their positions must be raw too.
     * The overlay scale still multiplies the logical offsets so the hit areas track the visible
     * frames when the dialog is auto-scaled or resized.
     */
    private void positionScheduleSlots() {
        int n = be().getEntryCount();
        for (int i = 0; i < PlaybackDeviceBlockEntity.MAX_ENTRIES; i++) {
            int slotIdx = PlaybackDeviceMenu.PLAYLIST_MENU_BASE + i;
            int row = windowRowOf(i);
            if (i < n && row >= 0) {
                // Manta owns the overlay transform (origin-pivot scale); go through its API
                // instead of hand-rolling origin + scale, which is easy to get subtly wrong.
                int sx = Math.round(overlayLocalToScreenX(SLOT_ROW_X)) - this.leftPos;
                int sy = Math.round(overlayLocalToScreenY(SLOT_ROW_Y + row * ROW_STRIDE))
                        - this.topPos;
                setMenuSlotPos(slotIdx, sx, sy);
            } else {
                setMenuSlotPos(slotIdx, -1000, -1000);
            }
        }
    }

    /**
     * The base consumes every click inside the popup panel, so a click on a popup slot never
     * reaches vanilla slot handling — and the follow-up release used to fall through to the
     * base, which treated it as a quick-craft end and threw the carried item. Same cure as
     * TSU's announcement popup: hit-test the popup slots first and issue the click directly,
     * then swallow the matching release.
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (schedulePopup.isOpen() && button >= 0 && button <= 2) {
            positionScheduleSlots();   // fresh positions even right after opening / dragging the popup
            Slot slot = hoveredPlaylistSlot(mouseX, mouseY);
            if (slot != null) {
                net.minecraft.world.inventory.ClickType type;
                if (hasShiftDown()) {
                    type = net.minecraft.world.inventory.ClickType.QUICK_MOVE;
                } else if (button == 2 && this.minecraft != null && this.minecraft.player != null
                        && this.minecraft.player.getAbilities().instabuild) {
                    type = net.minecraft.world.inventory.ClickType.CLONE;
                } else {
                    type = net.minecraft.world.inventory.ClickType.PICKUP;
                }
                this.slotClicked(slot, slot.index, button, type);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Release over a popup slot: the click already ran on press, so the release is a no-op. */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (schedulePopup.isOpen() && button >= 0 && button <= 2
                && hoveredPlaylistSlot(mouseX, mouseY) != null) {
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** The active playlist slot under the raw mouse position, or null. */
    private Slot hoveredPlaylistSlot(double mouseX, double mouseY) {
        for (int i = 0; i < PlaybackDeviceBlockEntity.MAX_ENTRIES; i++) {
            int slotIdx = PlaybackDeviceMenu.PLAYLIST_MENU_BASE + i;
            if (slotIdx >= this.menu.slots.size()) break;
            Slot slot = this.menu.slots.get(slotIdx);
            if (!slot.isActive() || slot.x < -500) continue;
            if (isOverScheduleSlot(slot, mouseX, mouseY)) return slot;
        }
        return null;
    }

    /**
     * Hit-test a popup slot against the size it is actually drawn at.
     *
     * <p>{@link #renderScheduleOverlayItems} draws a 16×16 slot inside {@code scale(s)}, so the
     * visible slot is {@code 16 * s} px. Vanilla's {@code isHovering} only tests a fixed 16×16 box,
     * which leaves the outer ring unhighlighted and unclickable whenever the dialog is scaled up.
     * The scale source is Manta's overlay transform (see {@code JsonLayoutScreen.overlayScale()});
     * we read the same {@code dialogScale()} the drawing uses so visual and hit area cannot diverge.
     */
    private boolean isOverScheduleSlot(Slot slot, double mouseX, double mouseY) {
        return com.manta.api.hud.Rects.overSlot(mouseX, mouseY,
                this.leftPos + slot.x, this.topPos + slot.y, dialogScale());
    }

    private void hideScheduleSlots() {
        for (int i = 0; i < PlaybackDeviceBlockEntity.MAX_ENTRIES; i++) {
            setMenuSlotPos(PlaybackDeviceMenu.PLAYLIST_MENU_BASE + i, -1000, -1000);
        }
    }

    private void setMenuSlotPos(int slotIndex, int x, int y) {
        com.manta.api.screen.SlotMover.move(this.menu, slotIndex, x, y);
    }

    /** Overlay slots render under the popup panel, so redraw their items above it (z=700). */
    private void renderScheduleOverlayItems(GuiGraphics g, int mouseX, int mouseY) {
        // Hold off until the popup has finished scaling in, so items don't float before the panel.
        if (scheduleOpenedAtNanos > 0 && System.nanoTime() - scheduleOpenedAtNanos < OPEN_ANIM_NS) return;
        float s = dialogScale();
        for (int i = 0; i < PlaybackDeviceBlockEntity.MAX_ENTRIES; i++) {
            int slotIdx = PlaybackDeviceMenu.PLAYLIST_MENU_BASE + i;
            if (slotIdx >= this.menu.slots.size()) break;
            Slot slot = this.menu.slots.get(slotIdx);
            if (!slot.isActive() || slot.x < -500) continue;
            g.pose().pushPose();
            // Slot coords are raw screen offsets (see positionScheduleSlots); only the size scales.
            g.pose().translate(this.leftPos + slot.x, this.topPos + slot.y, 700);
            g.pose().scale(s, s, 1f);
            if (isOverScheduleSlot(slot, mouseX, mouseY)) {
                g.fillGradient(0, 0, 16, 16, 0x80FFFFFF, 0x80FFFFFF);
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                g.renderItem(stack, 0, 0);
                com.manta.api.render.ItemDraw.decorations(g, this.font, stack, 0, 0);
            }
            g.pose().popPose();
        }
    }

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        switch (key) {
            case "pb-title-caret" -> {
                if (nameInput.isFocused()) {
                    // value(), not display(): display() is the placeholder while empty, and
                    // the caret belongs at the start then (real-device note 2026-09-05).
                    com.manta.api.render.TextCaretRenderer.draw(
                            g, this.font, nameInput.value(), x, y, w, h, 0xFF4FC3F7, nameInput);
                }
            }
            case "pb-jacket" -> drawJacket(g, x, y, w, h);
            case "pb-redstone-icon" -> drawRedstoneButtonIcon(g, x, y, w, h, mouseX, mouseY);
            case "owner-face" -> com.manta.api.hud.OwnerFacePainter.draw(
                    g, x, y, w, h, be().getOwnerUUID());
            default -> { }
        }
    }

    /**
     * The redstone button's own icon: a four-pointed dust crystal with a darker heart, in the
     * palette's red. Drawn through Manta's SVG path so it scales with the dialog like the
     * registry icons do; a texture would have needed a second copy per scale.
     */
    private static final String REDSTONE_SVG = redstoneSvg("#ef5350", "#b71c1c");
    /** The hovered form: white like every other button's hoverColor, the heart kept red. */
    private static final String REDSTONE_SVG_HOVER = redstoneSvg("#ffffff", "#ef5350");

    private static String redstoneSvg(String fill, String heart) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\">"
                + "<path fill=\"" + fill + "\" d=\"M8 0.5 L10.6 5.4 L15.5 8 L10.6 10.6 L8 15.5 L5.4 10.6 L0.5 8 L5.4 5.4 Z\"/>"
                + "<path fill=\"" + heart + "\" d=\"M8 5 L10 8 L8 11 L6 8 Z\"/>"
                + "</svg>";
    }

    /**
     * The whole button is the canvas (its rect layer paints the frame and the hover fill), and
     * the icon is drawn inset, white while the mouse is over it. A canvas child inside a div
     * took the hover for itself, so the div's hover colours never showed (real device, 2026-09-03).
     */
    private static void drawRedstoneButtonIcon(GuiGraphics g, int x, int y, int w, int h, int mouseX, int mouseY) {
        boolean hovered = com.manta.api.hud.Rects.contains(mouseX, mouseY, x, y, w, h);
        int size = Math.min(w, h) - 2;
        com.manta.api.svg.SvgIcon.draw(g, hovered ? REDSTONE_SVG_HOVER : REDSTONE_SVG,
                x + (w - size) / 2, y + (h - size) / 2, size, size);
    }

    /** Draws the single-play medium's cover art, requesting it once, or a placeholder item. */
    private void drawJacket(GuiGraphics g, int x, int y, int w, int h) {
        ItemStack media = be().getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT);
        UUID id = media.get(ModDataComponents.AUDIO_ID);
        ClientArtCache.Art art = ClientArtCache.get(id);
        if (art != null) {
            g.flush();
            g.blit(art.loc(), x, y, w, h, 0f, 0f, art.w(), art.h(), art.w(), art.h());
            g.flush();
            return;
        }
        if (id != null) ClientArtCache.request(id);
        com.manta.api.render.ItemDraw.stackInBox(g, new ItemStack(ModItems.RECORDING_MEDIUM.get()),
                x, y, w, h, 0.62f, 0f);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (nameInput.isFocused() && nameInput.charTyped(codePoint)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    /** Click on the title: edit the name in place (the current name is the starting text). */
    private void beginName() {
        String name = be().getDeviceName();
        nameInput.setValue(name == null ? "" : name);
        nameInput.focus();
    }

    private void submitName() {
        send("rename", nameInput.value());
        nameInput.blur();
    }

    private void cancelName() {
        nameInput.blur();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (nameInput.isFocused()) {
            // Enter submits and Escape cancels inside the controller; every other key is the
            // input's too (the inventory key must not close the screen mid-name).
            nameInput.keyPressed(keyCode);
            return true;
        }
        // ESC は基底が処理する: closeOpenOverlay() (下で override) -> onClose()。
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.keyInventory != null
                && mc.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 幅に収まるよう "…" で省略する。 実体は {@code HudText.ellipsize} (省略記号を 1 種類に保つ)。 */
    private String trimToFit(String text, int maxWidth) {
        return com.manta.api.hud.HudText.ellipsize(this.font, text, maxWidth);
    }

    // ================================================================= server sync

    /**
     * The device's host on manta:data (MANTA_7_CONCEPT C4, network.PlaybackDeviceData): opened on the first
     * action, closed with the screen. The screen's values still come from the block entity's sync; only the
     * input moved (the payloads' own sends, one action each, the params in the layout's declaration order).
     */
    private com.manta.api.data.Mirror data;

    private void send(String action, Object... args) {
        if (data == null) {
            PlaybackDeviceBlockEntity be = be();
            if (be.getLevel() == null) return;
            data = com.manta.api.data.Mirror.open(PlaybackDeviceData.channel(be.getLevel(), be.getBlockPos()),
                    PlaybackDeviceData.schema());
        }
        data.send(action, args);
    }

    @Override
    public void removed() {
        super.removed();
        if (data != null) {
            data.close();
            data = null;
        }
    }
}
