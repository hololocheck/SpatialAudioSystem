package com.spatialaudiosystem.screen;

import belugalab.experience.controller.ToggleSwitchController;
import belugalab.mcss3.screen.JsonLayoutEngine;
import belugalab.mcss3.screen.JsonLayoutScreen;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.client.ClientArtCache;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.menu.PlaybackDeviceMenu;
import com.spatialaudiosystem.network.PlaybackControlPayload;
import com.spatialaudiosystem.network.PlaylistCommandPayload;
import com.spatialaudiosystem.network.ToggleAttenuationPayload;
import com.spatialaudiosystem.network.ToggleRangeDisplayPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
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

    private static final int COLOR_PLAYING = 0xFF55FF55;
    private static final int COLOR_STOPPED = 0xFFAAAAAA;
    private static final int COLOR_SCHED_ON = 0xFFFFC107;        // amber: the ♪ button is armed
    private static final int COLOR_SCHED_OFF_TEXT = 0xFF777777;  // grey: toggle it on first
    private static final int COLOR_SCHED_OFF_BORDER = 0xFF555555;
    private static final int FILE_MAX_W = 190;
    private static final int ROW_STRIDE = 35;      // matches playback-schedule.json
    private static final int FIRST_ROW_Y = 52;     // first entry row Y in the overlay
    private static final int SLOT_ROW_X = 193;     // media-slot-frame x(192) + 1
    private static final int SLOT_ROW_Y = 56;      // media-slot-frame y(55) + 1
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

    private static java.lang.reflect.Field SLOT_X_FIELD;
    private static java.lang.reflect.Field SLOT_Y_FIELD;
    static {
        try {
            SLOT_X_FIELD = Slot.class.getDeclaredField("x");
            SLOT_Y_FIELD = Slot.class.getDeclaredField("y");
            SLOT_X_FIELD.setAccessible(true);
            SLOT_Y_FIELD.setAccessible(true);
        } catch (Exception ignored) { }
    }

    private boolean attenuationOn;
    private boolean rangeVisible;
    private boolean showSchedule = false;
    private long scheduleOpenedAtNanos = 0L;

    private final ToggleSwitchController attenuationToggle = new ToggleSwitchController(
            "pb-atten-track", "pb-atten-knob",
            () -> attenuationOn,
            v -> {
                attenuationOn = v;
                PacketDistributor.sendToServer(new ToggleAttenuationPayload(pos()));
            });

    private final ToggleSwitchController rangeToggle = new ToggleSwitchController(
            "pb-range-track", "pb-range-knob",
            () -> rangeVisible,
            v -> {
                rangeVisible = v;
                PacketDistributor.sendToServer(new ToggleRangeDisplayPayload(pos()));
            });

    /** Schedule mode: arms the ♪ button and bars the media slot (server flips the real state). */
    private boolean scheduleModeOn;

    private final ToggleSwitchController scheduleToggle = new ToggleSwitchController(
            "pb-sched-toggle-track", "pb-sched-toggle-knob",
            () -> scheduleModeOn,
            v -> {
                scheduleModeOn = v;
                if (!v) closeSchedule();
                PacketDistributor.sendToServer(new PlaylistCommandPayload(
                        pos(), PlaylistCommandPayload.OP_TOGGLE_MODE, 0, 0));
            });

    public PlaybackDeviceScreenV2(PlaybackDeviceMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    @Override
    protected void init() {
        super.init();
        PlaybackDeviceBlockEntity be = this.menu.getBlockEntity();
        this.attenuationOn = be.isAttenuationMode();
        this.rangeVisible = be.isShowRange();
        this.scheduleModeOn = be.isScheduleMode();
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
        showSchedule = sched;
        scheduleModeOn = sched;
        if (sched && !be().isScheduleMode()) be().toggleScheduleMode();   // dummy client BE: no packets
        scheduleOpenedAtNanos = 0L;   // no open animation, and slot items draw immediately
    }

    @Override
    protected String layoutJson() { return SasLayouts.load("layouts/playback-device.json"); }

    @Override
    protected String overlayJson() {
        return showSchedule ? SasLayouts.load("layouts/playback-schedule.json") : null;
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

    private PlaybackDeviceBlockEntity be() {
        return this.menu.getBlockEntity();
    }

    @Override
    public String getDynamicText(String[] classes, String defaultText) {
        PlaybackDeviceBlockEntity be = be();
        for (String c : classes) {
            switch (c) {
                case "pb-title":
                    return this.title.getString();
                case "pb-status":
                    return be.isPlaying()
                            ? Component.translatable("gui.spatialaudiosystem.status_playing").getString()
                            : Component.translatable("gui.spatialaudiosystem.status_stopped").getString();
                case "pb-file": {
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
                case "pb-format": {
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
                case "pb-atten-range":
                    return Component.translatable("gui.spatialaudiosystem.attenuation_range",
                            be.getAttenuationRange()).getString();
                case "pb-entry-index": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    return idx >= 0 ? String.valueOf(idx + 1) : "";
                }
                case "pb-count-display": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx < 0) return "";
                    return TIMES + (be.isLoopEntry(idx)
                            ? ENDLESS_COUNT
                            : String.valueOf(be.getPlayCount(idx)));
                }
                case "pb-media-info": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx < 0) return "";
                    String name = be.getPlaylist().getStackInSlot(idx).get(ModDataComponents.AUDIO_FILE_NAME);
                    if (name == null) return Component.translatable("gui.spatialaudiosystem.entry_empty").getString();
                    return trimToFit(name, 148);
                }
                default:
            }
        }
        return null;
    }

    @Override
    public Integer getDynamicColor(String[] classes, String key, int defaultArgb) {
        switch (key) {
            case "pb-status-color":
                return be().isPlaying() ? COLOR_PLAYING : COLOR_STOPPED;
            case "pb-atten-track-bg": return attenuationToggle.trackBg();
            case "pb-atten-knob-bg":  return attenuationToggle.knobBg();
            case "pb-range-track-bg": return rangeToggle.trackBg();
            case "pb-range-knob-bg":  return rangeToggle.knobBg();
            case "pb-sched-toggle-track-bg": return scheduleToggle.trackBg();
            case "pb-sched-toggle-knob-bg":  return scheduleToggle.knobBg();
            case "pb-sched-btn-color":  return scheduleModeOn ? COLOR_SCHED_ON : COLOR_SCHED_OFF_TEXT;
            case "pb-sched-btn-border": return scheduleModeOn ? COLOR_SCHED_ON : COLOR_SCHED_OFF_BORDER;
            case "pb-sched-btn-bg":     return scheduleModeOn ? 0x1AFFC107 : 0x0DFFFFFF;
            case "owner-border":
                return belugalab.tsu.api.OwnerAccess.ringColor(be().isPrivateMode());
            case "pb-entry-row-bg": {
                int idx = JsonLayoutEngine.currentRepeatIndex();
                return idx == be().getPlayingEntry() ? PLAYING_HL_BG : null;
            }
            case "pb-entry-row-border": {
                int idx = JsonLayoutEngine.currentRepeatIndex();
                return idx == be().getPlayingEntry() ? PLAYING_HL_BORDER : null;
            }
            case "pb-playing-frame-bg":     return PLAYING_HL_BG;
            case "pb-playing-frame-border": return PLAYING_HL_BORDER;
            default:
                return null;
        }
    }

    @Override
    public Integer getDynamicNumber(String[] classes, String key, int defaultValue) {
        switch (key) {
            case "pb-atten-knob-x": return attenuationToggle.knobX(defaultValue);
            case "pb-range-knob-x": return rangeToggle.knobX(defaultValue);
            case "pb-sched-toggle-knob-x": return scheduleToggle.knobX(defaultValue);
            case "pb-entry-count":  return showSchedule ? be().getEntryCount() : 0;
            case "pb-playing-frame-y": {
                int idx = Math.max(0, be().getPlayingEntry());
                return FIRST_ROW_Y + idx * ROW_STRIDE;
            }
            default:
                return null;
        }
    }

    @Override
    public Boolean getDynamicBool(String[] classes, String key, boolean defaultValue) {
        if ("pb-playing-frame-visible".equals(key)) {
            return showSchedule && be().getPlayingEntry() >= 0;
        }
        // The media-slot ✕ is an engine element so it rides the open/close animation;
        // a Java overdraw sat still while the rest of the dialog scaled.
        if ("pb-media-locked".equals(key)) return scheduleModeOn;
        return null;
    }

    @Override
    public boolean onElementWheel(String[] classes, String key, int mouseX, int mouseY, double scrollY) {
        if ("pb-count-wheel".equals(key)) {
            int idx = JsonLayoutEngine.currentRepeatIndex();
            if (idx < 0) return false;
            int delta = scrollY > 0 ? 1 : -1;
            PacketDistributor.sendToServer(new PlaylistCommandPayload(
                    pos(), PlaylistCommandPayload.OP_ADJUST_PLAYCOUNT, idx, delta));
            be().setPlayCount(idx, be().getPlayCount(idx) + delta);   // optimistic; server confirms
            return true;
        }
        return false;
    }

    @Override
    public void onElementClick(String[] classes, int mouseX, int mouseY, int button) {
        if (belugalab.tsu.api.HintToggleHelper.handleClick(classes)) return;
        if (attenuationToggle.handleClick(classes)) return;
        if (rangeToggle.handleClick(classes)) return;
        if (scheduleToggle.handleClick(classes)) return;
        if (belugalab.tsu.api.OwnerAccess.isFaceClick(classes)) {   // toggle public/private
            sendButtonClick(belugalab.tsu.api.OwnerAccess.TOGGLE_BUTTON);
            return;
        }
        for (String c : classes) {
            switch (c) {
                case "mc-popup-close": onClose(); return;
                case "wiki-btn": {
                    String pid = wikiPageId();
                    if (pid != null && !pid.isEmpty()) belugalab.mcss3.wiki.Wiki.open(pid);
                    return;
                }
                case "pb-play-btn":
                    if (scheduleModeOn) {
                        // In schedule mode the main play button drives the schedule too.
                        PacketDistributor.sendToServer(new PlaylistCommandPayload(
                                pos(), PlaylistCommandPayload.OP_PLAY_ALL, 0, 0));
                    } else {
                        PacketDistributor.sendToServer(new PlaybackControlPayload(pos(), true));
                    }
                    return;
                case "pb-stop-btn":
                case "pb-sched-stop-btn":
                    // One stop for everything: halts the single medium or a running playlist sequence.
                    PacketDistributor.sendToServer(new PlaylistCommandPayload(
                            pos(), PlaylistCommandPayload.OP_STOP, 0, 0));
                    return;
                case "pb-sched-playall-btn":
                    PacketDistributor.sendToServer(new PlaylistCommandPayload(
                            pos(), PlaylistCommandPayload.OP_PLAY_ALL, 0, 0));
                    return;
                case "pb-sched-btn":
                    if (!scheduleModeOn) return;   // armed by the toggle beside it
                    showSchedule = true;
                    scheduleOpenedAtNanos = System.nanoTime();
                    return;
                case "pb-sched-close":
                    closeSchedule();
                    return;
                case "pb-add-entry-btn":
                    PacketDistributor.sendToServer(new PlaylistCommandPayload(
                            pos(), PlaylistCommandPayload.OP_ADD_ENTRY, 0, 0));
                    return;
                case "pb-entry-up-btn": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx > 0) PacketDistributor.sendToServer(new PlaylistCommandPayload(
                            pos(), PlaylistCommandPayload.OP_REORDER, idx, idx - 1));
                    return;
                }
                case "pb-entry-down-btn": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0 && idx + 1 < be().getEntryCount()) {
                        PacketDistributor.sendToServer(new PlaylistCommandPayload(
                                pos(), PlaylistCommandPayload.OP_REORDER, idx, idx + 1));
                    }
                    return;
                }
                case "pb-entry-test-btn": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) PacketDistributor.sendToServer(new PlaylistCommandPayload(
                            pos(), PlaylistCommandPayload.OP_TEST, idx, 0));
                    return;
                }
                case "pb-entry-del-btn": {
                    int idx = JsonLayoutEngine.currentRepeatIndex();
                    if (idx >= 0) PacketDistributor.sendToServer(new PlaylistCommandPayload(
                            pos(), PlaylistCommandPayload.OP_REMOVE_ENTRY, idx, 0));
                    return;
                }
                default:
            }
        }
    }

    private void closeSchedule() {
        showSchedule = false;
        scheduleOpenedAtNanos = 0L;
        hideScheduleSlots();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (showSchedule) positionScheduleSlots(); else hideScheduleSlots();
        super.render(g, mouseX, mouseY, partialTick);
        if (showSchedule) {
            renderScheduleOverlayItems(g, mouseX, mouseY);
            renderCarriedAbovePopup(g, mouseX, mouseY);
            renderHoveredPlaylistTooltip(g, mouseX, mouseY);
        }
    }

    /**
     * Vanilla draws the carried stack under the popup panel, so an item dragged over the schedule
     * disappeared behind it. Draw it again above the panel — same spot and scale, so nothing shifts.
     */
    private void renderCarriedAbovePopup(GuiGraphics g, int mouseX, int mouseY) {
        ItemStack carried = this.menu.getCarried();
        if (carried.isEmpty()) return;
        float s = dialogScale();
        g.pose().pushPose();
        g.pose().translate(mouseX, mouseY, 800);   // slot items 700 < carried 800 < tooltip 900
        g.pose().scale(s, s, 1f);
        g.renderItem(carried, -8, -8);
        g.renderItemDecorations(this.font, carried, -8, -8);
        g.pose().popPose();
    }

    /** Base tooltips render under the popup too; redraw the hovered playlist slot's above it. */
    private void renderHoveredPlaylistTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!this.menu.getCarried().isEmpty()) return;
        Slot slot = hoveredPlaylistSlot(mouseX, mouseY);
        if (slot == null || !slot.hasItem()) return;
        ItemStack stack = slot.getItem();
        g.pose().pushPose();
        g.pose().translate(0, 0, 900);
        g.renderTooltip(this.font, this.getTooltipFromContainerItem(stack),
                stack.getTooltipImage(), mouseX, mouseY);
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
            if (i < n) {
                // Manta owns the overlay transform (origin-pivot scale); go through its API
                // instead of hand-rolling origin + scale, which is easy to get subtly wrong.
                int sx = Math.round(overlayLocalToScreenX(SLOT_ROW_X)) - this.leftPos;
                int sy = Math.round(overlayLocalToScreenY(SLOT_ROW_Y + i * ROW_STRIDE))
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
        if (showSchedule && button >= 0 && button <= 2) {
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
        if (showSchedule && button >= 0 && button <= 2
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
        float s = dialogScale();
        float x0 = this.leftPos + slot.x;
        float y0 = this.topPos + slot.y;
        float size = 16f * s;
        return mouseX >= x0 && mouseX < x0 + size
                && mouseY >= y0 && mouseY < y0 + size;
    }

    private void hideScheduleSlots() {
        for (int i = 0; i < PlaybackDeviceBlockEntity.MAX_ENTRIES; i++) {
            setMenuSlotPos(PlaybackDeviceMenu.PLAYLIST_MENU_BASE + i, -1000, -1000);
        }
    }

    private void setMenuSlotPos(int slotIndex, int x, int y) {
        if (slotIndex < 0 || slotIndex >= this.menu.slots.size()) return;
        Slot slot = this.menu.slots.get(slotIndex);
        try {
            if (SLOT_X_FIELD != null) SLOT_X_FIELD.setInt(slot, x);
            if (SLOT_Y_FIELD != null) SLOT_Y_FIELD.setInt(slot, y);
        } catch (Exception ignored) { }
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
                g.renderItemDecorations(this.font, stack, 0, 0);
            }
            g.pose().popPose();
        }
    }

    @Override
    public void drawCanvas(GuiGraphics g, String[] classes, String key,
                           int x, int y, int w, int h, int mouseX, int mouseY) {
        switch (key) {
            case "pb-jacket" -> drawJacket(g, x, y, w, h);
            case "owner-face" -> belugalab.tsu.api.OwnerFacePainter.draw(
                    g, x, y, w, h, be().getOwnerUUID());
            default -> { }
        }
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
        float scale = Math.min(w, h) * 0.62f / 16f;
        g.pose().pushPose();
        g.pose().translate(x + (w - 16 * scale) / 2f, y + (h - 16 * scale) / 2f, 0);
        g.pose().scale(scale, scale, 1f);
        g.renderItem(new ItemStack(ModItems.RECORDING_MEDIUM.get()), 0, 0);
        g.pose().popPose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (showSchedule) { closeSchedule(); return true; }
            onClose();
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null && mc.options.keyInventory != null
                && mc.options.keyInventory.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private String trimToFit(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ew = this.font.width(ellipsis);
        for (int i = text.length() - 1; i > 0; i--) {
            if (this.font.width(text.substring(0, i)) + ew <= maxWidth) {
                return text.substring(0, i) + ellipsis;
            }
        }
        return ellipsis;
    }
}
