package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.audio.PlaybackDelivery;
import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.RangeBoardItem;
import com.spatialaudiosystem.item.RecordingMediumItem;
import com.spatialaudiosystem.menu.PlaybackDeviceMenu;
import com.spatialaudiosystem.network.ClientStopAudioPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

public class PlaybackDeviceBlockEntity extends BlockEntity implements MenuProvider, OwnedDevice {
    /** Shares the loop signal's logger with {@link com.spatialaudiosystem.audio.AudioManager}. */
    private static final org.slf4j.Logger LOOP_SIGNAL =
            org.slf4j.LoggerFactory.getLogger("SAS-Loop");

    public static final int MEDIA_SLOT = 0;
    public static final int RANGE_SLOT = 1;
    public static final int SLOT_COUNT = 2;

    /** Playlist ("schedule") entries: recording media played in sequence by the scheduler. */
    public static final int PLAYLIST_SIZE = 6;
    /** Alias used by the entry-based schedule UI; entries share the playlist capacity. */
    public static final int MAX_ENTRIES = PLAYLIST_SIZE;
    public static final int MAX_PLAY_COUNT = 10;
    /**
     * The play count meaning "never stop".
     *
     * <p>Zero was free: a count has always been clamped to at least one, so no saved device can
     * be holding it by accident, and a legacy world with no counts at all reads as one rather
     * than as this. Scrolling runs {@code 1..MAX_PLAY_COUNT} and then here, so the endless
     * setting sits one step past the largest finite one instead of behind a separate control.
     */
    public static final int LOOP_FOREVER = 0;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            if (slot == MEDIA_SLOT) onMediaSlotChanged();
            setChanged();
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return switch (slot) {
                case MEDIA_SLOT -> stack.is(ModItems.RECORDING_MEDIUM.get());
                case RANGE_SLOT -> stack.is(ModItems.RANGE_BOARD.get());
                default -> false;
            };
        }
    };

    /** The playlist media (separate from the single MEDIA_SLOT so old devices are untouched). */
    private final ItemStackHandler playlist = new ItemStackHandler(PLAYLIST_SIZE) {
        @Override
        protected void onContentsChanged(int slot) {
            onPlaylistSlotChanged(slot);
            setChanged();
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.is(ModItems.RECORDING_MEDIUM.get());
        }
    };

    /** Times each playlist entry repeats before the scheduler moves on (1..MAX_PLAY_COUNT). */
    private final int[] playCounts = new int[PLAYLIST_SIZE];
    { java.util.Arrays.fill(playCounts, 1); }

    /** Number of active playlist entries (0..MAX_ENTRIES). Media/playCount for entry i live at index i. */
    private int entryCount = 0;
    /** Entry the scheduler is currently playing, for the playing-frame highlight (-1 = none). Transient. */
    private int playingEntry = -1;
    /**
     * Entry this device is looping endlessly (-1 = none). Persisted, unlike {@link #playingEntry}.
     *
     * <p>"For as long as the server is up" cannot be held in a running sequence: the scheduler's
     * state lives for one run and its device's chunk unloads whenever nobody is near. This is the
     * standing instruction that {@link #tick} restores the sound from after a restart or a chunk
     * reload, so the loop outlives both.
     */
    private int loopingEntry = -1;
    /**
     * Earliest server tick at which {@link #tick} may try to restore the loop, so a device whose
     * audio is missing is not retried every tick.
     *
     * <p>Held as the next allowed tick rather than the last attempted one. The obvious form —
     * a {@code Long.MIN_VALUE} "never tried" sentinel compared as {@code now - last >= interval}
     * — overflows: {@code now - Long.MIN_VALUE} wraps negative for every game time there is, so
     * the comparison is false forever and the restore never runs. It was written that way and the
     * loop silently never came back.
     */
    private long nextLoopArmTick = 0;
    /** How often {@link #tick} may try to restore a loop that is armed but not sounding. */
    private static final long LOOP_ARM_RETRY_TICKS = 20;
    /** Schedule mode: the playlist editor is armed and the single-play media slot is barred. */
    private boolean scheduleMode = false;
    /**
     * Play the single medium endlessly.
     *
     * <p>Separate from the schedule's own endless entry: until 2026-08-30 the only way to reach
     * an endless sound was to build a one-entry schedule, which is a lot of screen for "this
     * device hums until I stop it".
     *
     * <p>Not final and not initialised inline on purpose -- this class is also constructed by
     * Objenesis in tests, which skips field initialisers, so a non-false default here would be
     * a value that exists in production and not under test.
     */
    private boolean normalLoop = false;

    /**
     * The sound currently playing here was started from {@link #MEDIA_SLOT}.
     *
     * <p>{@code isPlaying} alone cannot say that: the scheduler's own tracks and the per-entry
     * preview set it too. Without this, a device with the endless button left on and a medium
     * still sitting in its slot -- which schedule mode bars but never empties -- would count a
     * playlist track as its endless sound, suppress the runaway timeout for it, and on the next
     * restart start the single medium on its own. Found by review on 2026-08-30.
     *
     * <p>Persisted, because after a restart there is nothing left to re-derive it from: the
     * session registry is process-local. Same reason {@code loopingEntry} is persisted.
     */
    private boolean playingSingle = false;
    private java.util.UUID ownerUUID = null;   // first player to open this device
    private String ownerName = null;
    private boolean privateMode = false;       // private = owner only (OwnerAccess ring red)

    private boolean isPlaying = false;
    private boolean showRange = false;
    private boolean attenuationMode = true;
    private int attenuationRange = 8;
    /** Server tick when playback started. Used for timeout safety net. */
    private long playbackStartTick = 0;
    /** Max playback duration in ticks before auto-stop (10 minutes). */
    private static final long PLAYBACK_TIMEOUT_TICKS = 12000;

    public PlaybackDeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLAYBACK_DEVICE.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.spatialaudiosystem.playback_device");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        if (!claimAndAllow(player)) return null;   // private device: owner only
        return new PlaybackDeviceMenu(containerId, playerInventory, this);
    }

    @Override
    @org.jetbrains.annotations.Nullable
    public java.util.UUID getOwnerUUID() {
        return ownerUUID;
    }

    @Override
    public void setOwner(java.util.UUID uuid, String name) {
        this.ownerUUID = uuid;
        this.ownerName = name;
        markUpdated();
    }

    @Override
    public boolean isPrivateMode() {
        return privateMode;
    }

    @Override
    public void togglePrivateMode() {
        privateMode = !privateMode;
        markUpdated();
    }

    /**
     * A playlist row changed: if it is the one looping, the endless sound loses its source.
     *
     * <p>Taking the medium out of that row has to stop the sound it is producing.
     * {@link #removeEntry} and {@link #swapEntries} stop it because the armed index would come
     * to name other media, but emptying or replacing a slot in place changes neither the index
     * nor the count, so nothing else here notices and the sound would play on with its source
     * gone. The endless sound is tied to the medium that was there when it started.
     *
     * <p>Named rather than inlined in the handler so it can be exercised directly.
     */
    void onPlaylistSlotChanged(int slot) {
        if (loopingEntry == slot && level != null && !level.isClientSide()) {
            stopPlayback();
        }
    }

    /** setChanged + a block-update sync, mirroring the inline pattern used across this class. */
    private void markUpdated() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    public ItemStackHandler getPlaylist() {
        return playlist;
    }

    public int getPlayCount(int idx) {
        return (idx >= 0 && idx < PLAYLIST_SIZE) ? playCounts[idx] : 1;
    }

    /** True when entry {@code idx} is set to play endlessly. */
    public boolean isLoopEntry(int idx) {
        return getPlayCount(idx) == LOOP_FOREVER;
    }

    /**
     * Sets entry {@code idx}'s play count, cycling through {@code 1..MAX_PLAY_COUNT} and then
     * {@link #LOOP_FOREVER}.
     *
     * <p>Cycling rather than clamping is what lets the wheel reach the endless setting: the
     * screen sends a delta of one either way and this is the only place that knows where the
     * range wraps.
     */
    public void setPlayCount(int idx, int n) {
        if (idx < 0 || idx >= PLAYLIST_SIZE) return;
        int span = MAX_PLAY_COUNT + 1;                 // the finite counts plus LOOP_FOREVER
        int next = ((n % span) + span) % span;
        boolean wasLooping = playCounts[idx] == LOOP_FOREVER;
        playCounts[idx] = next;
        // Turning the endless setting off has to stop the sound that setting is producing;
        // otherwise the display says a finite count while the device plays on forever.
        if (wasLooping && next != LOOP_FOREVER && loopingEntry == idx) stopPlayback();
        markUpdated();
    }

    public int getEntryCount() {
        return entryCount;
    }

    /** Append a new (empty) playlist entry; media is inserted into its slot afterward. */
    public boolean addEntry() {
        if (entryCount >= MAX_ENTRIES) return false;
        entryCount++;
        markUpdated();
        return true;
    }

    /** Remove entry {@code idx}, shifting later entries down, and return its media stack. */
    public ItemStack removeEntry(int idx) {
        if (idx < 0 || idx >= entryCount) return ItemStack.EMPTY;
        // Entries below the removed one shift up, so an armed loop's index would come to name a
        // different medium. Stopping is the only answer that cannot be silently wrong.
        if (loopingEntry >= 0) stopPlayback();
        ItemStack removed = playlist.getStackInSlot(idx);
        for (int i = idx; i < entryCount - 1; i++) {
            playlist.setStackInSlot(i, playlist.getStackInSlot(i + 1));
            playCounts[i] = playCounts[i + 1];
        }
        playlist.setStackInSlot(entryCount - 1, ItemStack.EMPTY);
        playCounts[entryCount - 1] = 1;
        entryCount--;
        markUpdated();
        return removed;
    }

    /** Swap entries {@code a} and {@code b} (media + play count together) for reordering. */
    public boolean swapEntries(int a, int b) {
        if (a < 0 || a >= entryCount || b < 0 || b >= entryCount || a == b) return false;
        // Same reason as removeEntry: after the swap the armed index names other media.
        if (loopingEntry >= 0) stopPlayback();
        ItemStack sa = playlist.getStackInSlot(a);
        playlist.setStackInSlot(a, playlist.getStackInSlot(b));
        playlist.setStackInSlot(b, sa);
        int pc = playCounts[a];
        playCounts[a] = playCounts[b];
        playCounts[b] = pc;
        markUpdated();
        return true;
    }

    public boolean isScheduleMode() {
        return scheduleMode;
    }

    /** Flips schedule mode. While on, the media slot refuses items and shift-clicks feed the playlist. */
    public void toggleScheduleMode() {
        scheduleMode = !scheduleMode;
        markUpdated();
    }

    public int getPlayingEntry() {
        return playingEntry;
    }

    /** Scheduler-set: the entry currently sounding (-1 = none). Drives the client playing-frame. */
    public void setPlayingEntry(int idx) {
        this.playingEntry = idx;
        markUpdated();
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public void setIsPlaying(boolean playing) {
        this.isPlaying = playing;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public boolean isShowRange() {
        return showRange;
    }

    public void setShowRange(boolean showRange) {
        this.showRange = showRange;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public boolean isAttenuationMode() {
        return attenuationMode;
    }

    public void setAttenuationMode(boolean attenuationMode) {
        this.attenuationMode = attenuationMode;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public int getAttenuationRange() {
        return attenuationRange;
    }

    public void setAttenuationRange(int range) {
        this.attenuationRange = Math.max(0, Math.min(15, range));
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    public void startPlayback() {
        ItemStack single = inventory.getStackInSlot(MEDIA_SLOT);
        // Nothing to start: leave whatever is playing exactly as it is. A redstone pulse into
        // an empty slot used to disarm a running playlist loop without stopping it, and the
        // safety-net timeout then ended that loop ten minutes later (review, 2026-09-02).
        if (!RecordingMediumItem.hasAudioData(single)) return;
        // Whatever the schedule had armed is no longer what this device is playing. Superseded
        // by stopping it, the way playAll does, rather than by dropping its arm: both arms set
        // at once made restoreLoop bring back a playlist entry instead of the medium in the
        // slot after a chunk reload, and both are persisted, so it survived a restart too.
        if (isPlaying || loopingEntry >= 0) stopPlayback();
        playingSingle = playMedia(single, normalLoop);
    }

    /** Whether the single medium plays endlessly. */
    public boolean isNormalLoop() {
        return normalLoop;
    }

    /**
     * Flips the endless flag for the single medium.
     *
     * <p>Reaches the sound that is playing, not only the next start. Turning the button off
     * used to leave an endless sound repeating until something else stopped it, which a live
     * test found on 2026-08-30: the button sits beside a stop and reads as a state of the
     * device, so it has to describe what the device is doing now.
     *
     * <p>Turning it off while the single medium plays <em>retires the sound on the server</em>
     * and lets each client finish its current pass on its own. Retiring it here, rather than
     * on the first client's finish report, is what makes the end per-client: the record a late
     * arrival would be delivered from is gone, so nobody is handed a pass that ends at decode
     * speed and reports the sound finished for everyone who is still hearing it -- the defect
     * a review found on 2026-09-02 in the version that let the clients decide.
     */
    public void toggleNormalLoop() {
        normalLoop = !normalLoop;
        setChanged();
        if (level instanceof ServerLevel sl) {
            if (playingSingle && isPlaying) {
                long id = PlaybackSessionRegistry.currentId(sl, getBlockPos());
                if (id != PlaybackSessionRegistry.NO_PLAYBACK) {
                    if (normalLoop) {
                        // A playing one-shot becomes endless by being started again as one.
                        // The one-shot went to every player in the dimension, because its
                        // end is reported by a client; flipping all of those to endless would
                        // pin a decode thread and an open line on players who cannot hear it,
                        // and the far ones would still report the old id finished and retire
                        // the sound under the near ones. Restarting goes through the filtered
                        // start instead. The cost is that listeners hear it from the top.
                        stopPlayback();
                        playingSingle = playMedia(inventory.getStackInSlot(MEDIA_SLOT), true);
                        return;
                    } else {
                        // Withdrawn. Every client that has the sound is told to stop looping
                        // and ends at its own pass boundary; the server has nothing left to
                        // deliver or to accept a report for, and the device is stopped now.
                        var msg = new com.spatialaudiosystem.network.ClientSetLoopPayload(
                                getBlockPos(), id, false);
                        for (ServerPlayer listener : sl.players()) {
                            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(listener, msg);
                        }
                        PlaybackSessionRegistry.end(sl, getBlockPos());
                        isPlaying = false;
                        playingSingle = false;
                        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(
                                new belugalab.sas.api.PlaybackEndedEvent(sl, getBlockPos()));
                    }
                }
            }
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Plays the given recording medium at this device, through its range board and attenuation
     * mode. Shared by the single MEDIA_SLOT play and the playlist scheduler.
     */
    public boolean playMedia(ItemStack mediaStack) {
        return playMedia(mediaStack, false);
    }

    /**
     * @param loop play endlessly. The client restarts the decoder on its own, so an endless
     *             sound costs one transfer rather than one per repetition, and it does not
     *             depend on a completion report that stops arriving when nobody is online.
     */
    public boolean playMedia(ItemStack mediaStack, boolean loop) {
        if (!RecordingMediumItem.hasAudioData(mediaStack) || level == null) return false;
        if (!(level instanceof ServerLevel sl)) return false;

        String format = mediaStack.getOrDefault(ModDataComponents.AUDIO_FORMAT, "ogg");

        BlockPos rangePos1 = null, rangePos2 = null;
        ItemStack rangeStack = inventory.getStackInSlot(RANGE_SLOT);
        if (RangeBoardItem.hasRange(rangeStack)) {
            rangePos1 = rangeStack.get(ModDataComponents.RANGE_POS1);
            rangePos2 = rangeStack.get(ModDataComponents.RANGE_POS2);
        }
        int[] attRanges = ModDataComponents.getAttenuationRangesArray(rangeStack);
        if (!rangeStack.has(ModDataComponents.ATTENUATION_RANGES)) {
            java.util.Arrays.fill(attRanges, attenuationRange);
        }

        // Delivery owns both the initial send and every later one, so a sound reaching a
        // player who arrives afterwards cannot describe itself differently from this one.
        long playbackId = PlaybackDelivery.start(sl, getBlockPos(), mediaStack, format,
                rangePos1, rangePos2, attenuationMode, attRanges, loop);
        if (playbackId == PlaybackSessionRegistry.NO_PLAYBACK) return false;

        isPlaying = true;
        // Cleared here rather than at each other call site: this is the one path every start
        // runs through, so a new caller cannot forget to say it is not the single medium.
        playingSingle = false;
        playbackStartTick = level.getGameTime();
        setChanged();
        level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        return true;
    }

    /**
     * Remembers that entry {@code idx} is the one playing endlessly, so the sound is restored
     * after a restart or a chunk reload. Pass -1 to disarm without stopping anything.
     */
    public void armLoop(int idx) {
        loopingEntry = idx;
        setChanged();
    }

    /** The entry this device is looping endlessly, or -1. */
    public int getLoopingEntry() {
        return loopingEntry;
    }

    /**
     * Whether this device is supposed to be producing an endless sound right now.
     *
     * <p>Re-derived from the schedule rather than trusting the index on its own. An index alone
     * says "entry 2" long after entry 2 stopped being endless or stopped existing, and -1 as the
     * off value is carried by a field initializer — which is exactly the assumption that does not
     * hold for an instance built without one. Every term here is checked, and the order matters:
     * the bound is tested before the play count is read, so an entry-less device never indexes
     * the counts array.
     */
    private boolean isLoopArmed() {
        return loopingEntry >= 0 && loopingEntry < entryCount && isLoopEntry(loopingEntry);
    }

    /**
     * Stops the single medium's sound when its medium is taken out while it plays.
     *
     * <p>The playlist already does this for its rows. The single slot did not, so an endless
     * single kept looping on every client after its medium was gone, while the server -- whose
     * "sounding endlessly" test reads the slot -- believed nothing endless was playing and let
     * a Play All start over it without a stop (review, 2026-09-02). Package-private so the rule
     * can be exercised without the handler, which cannot be constructed under Objenesis.
     */
    void onMediaSlotChanged() {
        // Server only, like the playlist hook: the client copy of this block entity sees the
        // same slot changes through container sync, and a stop from there would be a stop
        // nobody asked the server for.
        if (level != null && level.isClientSide()) return;
        if (playingSingle && isPlaying && inventory.getStackInSlot(MEDIA_SLOT).isEmpty()) {
            stopPlayback();
        }
    }

    /**
     * Whether what is sounding here will never end on its own.
     *
     * <p>Either endless source counts: a playlist entry set to endless, or the single medium
     * with the endless button on. A caller about to start something else has to stop an
     * endless sound explicitly -- a one-shot ends by itself and is left alone -- and a check
     * keyed on the playlist arm alone missed the second source (review, 2026-09-02).
     */
    public boolean isSoundingEndlessly() {
        return isLoopArmed() || isNormalLoopArmed();
    }

    /**
     * The single medium is set to play endlessly and has been started.
     *
     * <p>{@code isPlaying} is part of the test, and it is the part that matters: without it a
     * device holding a medium with the endless button left on would start sounding on its own
     * at the next tick, which nobody asked for. It is also what a stop clears, so stopping
     * disarms this without touching the button.
     *
     * <p>Found by review on 2026-08-30, before which the endless single medium was not armed at
     * all: the safety-net timeout below reached it at ten minutes and stopped it, so "endless"
     * lasted exactly as long as a runaway sound was allowed to.
     */
    private boolean isNormalLoopArmed() {
        return normalLoop && isPlaying && playingSingle
                && !inventory.getStackInSlot(MEDIA_SLOT).isEmpty();
    }

    public void stopPlayback() {
        isPlaying = false;
        playingSingle = false;
        // Every stop path runs through here, so this is the one place that has to disarm the
        // loop. Leaving it armed would have tick() start the sound again a second later.
        loopingEntry = -1;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            if (level instanceof ServerLevel sl) {
                ClientStopAudioPayload clientPayload =
                        new ClientStopAudioPayload(getBlockPos(), PlaybackSessionRegistry.currentId(sl, getBlockPos()));
                PlaybackSessionRegistry.end(sl, getBlockPos());
                for (ServerPlayer sp : sl.players()) {
                    PacketDistributor.sendToPlayer(sp, clientPayload);
                }
            }
        }
    }

    public void drops() {
        if (level != null) {
            for (int i = 0; i < inventory.getSlots(); i++) {
                Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                        inventory.getStackInSlot(i));
            }
            for (int i = 0; i < playlist.getSlots(); i++) {
                Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                        playlist.getStackInSlot(i));
            }
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, PlaybackDeviceBlockEntity entity) {
        if (level.isClientSide()) return;

        if (entity.isLoopArmed() || entity.isNormalLoopArmed()) {
            // Only the loop branch needs the server level; the timeout below does not, and
            // narrowing the whole method to it would change a path this is not about.
            if (!(level instanceof ServerLevel serverLevel)) return;
            // An endless sound has no end to wait for, so the timeout below would become the
            // thing that ends it. This is also where a loop comes back after a server restart
            // or a chunk reload. Whether it is actually sounding is asked of the registry and
            // not of isPlaying: isPlaying is restored from disk and can outlive the run that
            // set it, so it would report a sound that no client is playing.
            boolean sounding = PlaybackSessionRegistry.currentId(serverLevel, pos)
                    != PlaybackSessionRegistry.NO_PLAYBACK;
            if (!sounding && level.getGameTime() >= entity.nextLoopArmTick) {
                entity.nextLoopArmTick = level.getGameTime() + LOOP_ARM_RETRY_TICKS;
                entity.restoreLoop();
            }
            return;
        }

        // Safety-net timeout: if client never sent stop, auto-reset after PLAYBACK_TIMEOUT_TICKS
        if (entity.isPlaying && level.getGameTime() - entity.playbackStartTick > PLAYBACK_TIMEOUT_TICKS) {
            entity.stopPlayback();
        }
    }

    /** Starts the armed loop again, or disarms when its source can no longer produce a sound. */
    private void restoreLoop() {
        if (loopingEntry < 0) {
            // The single medium's endless play. Nothing to disarm if the slot has been emptied:
            // isNormalLoopArmed() already reads the slot, so it stops being armed by itself.
            ItemStack single = inventory.getStackInSlot(MEDIA_SLOT);
            // playMedia clears playingSingle for every caller, so this has to set it back --
            // exactly as startPlayback does. Without it the restore disarms what it restored
            // and the safety-net timeout ends the loop ten minutes later.
            if (!single.isEmpty() && (playingSingle = playMedia(single, true))) {
                LOOP_SIGNAL.info("restored pos={},{},{} entry=single",
                        getBlockPos().getX(), getBlockPos().getY(), getBlockPos().getZ());
            }
            return;
        }
        // isLoopArmed() has already established the index and the endless setting; what is left
        // is whether the entry still holds media.
        ItemStack media = playlist.getStackInSlot(loopingEntry);
        if (media.isEmpty()) {
            armLoop(-1);
            return;
        }
        // A failure here is left armed on purpose: the entry still names media, so the cause is
        // storage rather than the schedule, and the retry interval keeps that from being costly.
        if (playMedia(media, true)) {
            setPlayingEntry(loopingEntry);
            // The point of the whole arm-and-restore mechanism, said positively: "no error after
            // a restart" is indistinguishable from "the loop never came back".
            LOOP_SIGNAL.info("restored pos={},{},{} entry={}",
                    getBlockPos().getX(), getBlockPos().getY(), getBlockPos().getZ(), loopingEntry);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.put("playlist", playlist.serializeNBT(registries));
        tag.putIntArray("playCounts", playCounts.clone());
        tag.putInt("entryCount", entryCount);
        tag.putInt("loopingEntry", loopingEntry);   // survives restart: tick() restores the sound
        tag.putBoolean("scheduleMode", scheduleMode);
        tag.putBoolean("normalLoop", normalLoop);
        tag.putBoolean("playingSingle", playingSingle);
        tag.putBoolean("showRange", showRange);
        tag.putBoolean("isPlaying", isPlaying);
        tag.putBoolean("attenuationMode", attenuationMode);
        tag.putInt("attenuationRange", attenuationRange);
        if (ownerUUID != null) {
            tag.putUUID("OwnerUUID", ownerUUID);
            if (ownerName != null) tag.putString("OwnerName", ownerName);
        }
        tag.putBoolean("PrivateMode", privateMode);
    }

    private void loadPlayCounts(CompoundTag tag) {
        int[] pc = tag.getIntArray("playCounts");
        for (int i = 0; i < PLAYLIST_SIZE; i++) {
            if (i >= pc.length) {
                playCounts[i] = 1;
                continue;
            }
            int stored = pc[i];
            // LOOP_FOREVER became a legal stored value in 1.0.6. No earlier version could write
            // it — every write went through a clamp with a floor of one — so reading it back as
            // endless cannot mistake old data for a setting it never held.
            if (stored == LOOP_FOREVER) {
                playCounts[i] = LOOP_FOREVER;
            } else {
                playCounts[i] = stored >= 1 ? Math.min(MAX_PLAY_COUNT, stored) : 1;
            }
        }
    }

    private void loadEntryCount(CompoundTag tag) {
        if (tag.contains("entryCount")) {
            entryCount = Math.max(0, Math.min(MAX_ENTRIES, tag.getInt("entryCount")));
        } else {
            // Legacy device (fixed 6-slot playlist): treat filled slots as entries.
            int c = 0;
            for (int i = 0; i < MAX_ENTRIES; i++) {
                if (!playlist.getStackInSlot(i).isEmpty()) c = i + 1;
            }
            entryCount = c;
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        if (tag.contains("playlist")) playlist.deserializeNBT(registries, tag.getCompound("playlist"));
        loadPlayCounts(tag);
        loadEntryCount(tag);
        scheduleMode = tag.getBoolean("scheduleMode");
        normalLoop = tag.getBoolean("normalLoop");
        playingSingle = tag.getBoolean("playingSingle");
        loopingEntry = tag.contains("loopingEntry")
                ? Math.max(-1, Math.min(MAX_ENTRIES - 1, tag.getInt("loopingEntry")))
                : -1;
        // Transient on disk (defaults -1); carries the playing-frame index on client update tags.
        playingEntry = tag.contains("playingEntry") ? tag.getInt("playingEntry") : -1;
        if (tag.hasUUID("OwnerUUID")) {
            ownerUUID = tag.getUUID("OwnerUUID");
            ownerName = tag.contains("OwnerName") ? tag.getString("OwnerName") : null;
        } else {
            ownerUUID = null;
        }
        privateMode = tag.getBoolean("PrivateMode");
        showRange = tag.getBoolean("showRange");
        isPlaying = tag.getBoolean("isPlaying");
        // Missing key (legacy data) means the default ON, not false — same rule as TSU's config.
        attenuationMode = !tag.contains("attenuationMode") || tag.getBoolean("attenuationMode");
        attenuationRange = tag.contains("attenuationRange") ? tag.getInt("attenuationRange") : 8;
        // Eagerly migrate legacy AUDIO_DATA to file-based storage on load (Fix 4)
        if (level != null && !level.isClientSide() && level.getServer() != null) {
            migrateInventory();
        }
    }

    /** Called after level is set to migrate any legacy items in inventory. */
    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        if (!level.isClientSide() && level.getServer() != null) {
            migrateInventory();
        }
    }

    private void migrateInventory() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && AudioStorage.migrateIfNeeded(level.getServer(), stack)) {
                setChanged();
            }
        }
        for (int i = 0; i < PLAYLIST_SIZE; i++) {
            ItemStack stack = playlist.getStackInSlot(i);
            if (!stack.isEmpty() && AudioStorage.migrateIfNeeded(level.getServer(), stack)) {
                setChanged();
            }
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("showRange", showRange);
        tag.putBoolean("isPlaying", isPlaying);
        tag.putBoolean("attenuationMode", attenuationMode);
        tag.putInt("attenuationRange", attenuationRange);
        tag.putInt("entryCount", entryCount);
        tag.putBoolean("scheduleMode", scheduleMode);     // client: ✕ lock + button arming
        tag.putBoolean("normalLoop", normalLoop);        // client: the endless button
        tag.putInt("playingEntry", playingEntry);         // client: playing-frame highlight
        if (ownerUUID != null) tag.putUUID("OwnerUUID", ownerUUID);   // client: owner face
        tag.putBoolean("PrivateMode", privateMode);                   // client: ring colour
        // Serialize inventory without AUDIO_DATA (too large for network NBT).
        // createLiteStack copies all components except the legacy bulk data.
        ItemStackHandler liteInventory = new ItemStackHandler(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                liteInventory.setStackInSlot(i, ModDataComponents.createLiteStack(stack));
            }
        }
        tag.put("inventory", liteInventory.serializeNBT(registries));
        ItemStackHandler litePlaylist = new ItemStackHandler(PLAYLIST_SIZE);
        for (int i = 0; i < PLAYLIST_SIZE; i++) {
            ItemStack stack = playlist.getStackInSlot(i);
            if (!stack.isEmpty()) {
                litePlaylist.setStackInSlot(i, ModDataComponents.createLiteStack(stack));
            }
        }
        tag.put("playlist", litePlaylist.serializeNBT(registries));
        tag.putIntArray("playCounts", playCounts.clone());
        return tag;
    }
}
