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
    private static final int FILE_MAX_W = 190;
    private static final int ROW_STRIDE = 35;      // matches playback-schedule.json
    private static final int FIRST_ROW_Y = 52;     // first entry row Y in the overlay
    private static final int SLOT_ROW_X = 193;     // media-slot-frame x(192) + 1
    private static final int SLOT_ROW_Y = 56;      // media-slot-frame y(55) + 1
    private static final long OPEN_ANIM_NS = 220_000_000L;
    private static final int PLAYING_HL_BG = 0x224FC3F7;
    private static final int PLAYING_HL_BORDER = 0xFF4FC3F7;

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

    public PlaybackDeviceScreenV2(PlaybackDeviceMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
    }

    @Override
    protected void init() {
        super.init();
        PlaybackDeviceBlockEntity be = this.menu.getBlockEntity();
        this.attenuationOn = be.isAttenuationMode();
        this.rangeVisible = be.isShowRange();
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

    /** Wiki capture: force the shot to the main view or to the open schedule popup. */
    public void wikiApplyState(String state) {
        showSchedule = "schedule".equals(state);
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
                    ItemStack media = be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT);
                    String name = media.get(ModDataComponents.AUDIO_FILE_NAME);
                    if (name == null) {
                        return Component.translatable("gui.spatialaudiosystem.no_media").getString();
                    }
                    return trimToFit(Component.translatable(
                            "gui.spatialaudiosystem.file_prefix", name).getString(), FILE_MAX_W);
                }
                case "pb-format": {
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
                    return idx >= 0 ? "×" + be.getPlayCount(idx) : "";
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
                    PacketDistributor.sendToServer(new PlaybackControlPayload(pos(), true));
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
        if (showSchedule) renderScheduleOverlayItems(g, mouseX, mouseY);
    }

    /** Move each active playlist slot over its overlay row frame; the rest go off-screen. */
    private void positionScheduleSlots() {
        int dx = overlayX() - this.leftPos;
        int dy = overlayY() - this.topPos;
        int n = be().getEntryCount();
        for (int i = 0; i < PlaybackDeviceBlockEntity.MAX_ENTRIES; i++) {
            int slotIdx = PlaybackDeviceMenu.PLAYLIST_MENU_BASE + i;
            if (i < n) {
                setMenuSlotPos(slotIdx, dx + SLOT_ROW_X, dy + SLOT_ROW_Y + i * ROW_STRIDE);
            } else {
                setMenuSlotPos(slotIdx, -1000, -1000);
            }
        }
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
        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        for (int i = 0; i < PlaybackDeviceBlockEntity.MAX_ENTRIES; i++) {
            int slotIdx = PlaybackDeviceMenu.PLAYLIST_MENU_BASE + i;
            if (slotIdx >= this.menu.slots.size()) break;
            Slot slot = this.menu.slots.get(slotIdx);
            if (!slot.isActive() || slot.x < -500) continue;
            int sx = this.leftPos + slot.x;
            int sy = this.topPos + slot.y;
            if (this.isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY)) {
                g.fillGradient(sx, sy, sx + 16, sy + 16, 0x80FFFFFF, 0x80FFFFFF);
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                g.renderItem(stack, sx, sy);
                g.renderItemDecorations(this.font, stack, sx, sy);
            }
        }
        g.pose().popPose();
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
