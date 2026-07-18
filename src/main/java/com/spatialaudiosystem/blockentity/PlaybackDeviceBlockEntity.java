package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.RangeBoardItem;
import com.spatialaudiosystem.item.RecordingMediumItem;
import com.spatialaudiosystem.menu.PlaybackDeviceMenu;
import com.spatialaudiosystem.network.ClientAudioChunkPayload;
import com.spatialaudiosystem.network.ClientPlayAudioPayload;
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

public class PlaybackDeviceBlockEntity extends BlockEntity implements MenuProvider {
    public static final int MEDIA_SLOT = 0;
    public static final int RANGE_SLOT = 1;
    public static final int SLOT_COUNT = 2;

    /** Playlist ("schedule") entries: recording media played in sequence by the scheduler. */
    public static final int PLAYLIST_SIZE = 6;
    /** Alias used by the entry-based schedule UI; entries share the playlist capacity. */
    public static final int MAX_ENTRIES = PLAYLIST_SIZE;
    public static final int MAX_PLAY_COUNT = 10;

    private final ItemStackHandler inventory = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
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
        if (ownerUUID == null && player != null) {
            setOwner(player.getUUID(), player.getName().getString());
        }
        if (player != null && !canAccess(player)) return null;   // private device: owner only
        return new PlaybackDeviceMenu(containerId, playerInventory, this);
    }

    @org.jetbrains.annotations.Nullable
    public java.util.UUID getOwnerUUID() {
        return ownerUUID;
    }

    public void setOwner(java.util.UUID uuid, String name) {
        this.ownerUUID = uuid;
        this.ownerName = name;
        markUpdated();
    }

    public boolean isPrivateMode() {
        return privateMode;
    }

    /** Flip public/private. Only the owner should reach this (guarded in the menu). */
    public void togglePrivateMode() {
        privateMode = !privateMode;
        markUpdated();
    }

    /** Public devices are open to all; private ones are owner-only. */
    public boolean canAccess(Player player) {
        return !privateMode || ownerUUID == null || ownerUUID.equals(player.getUUID());
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

    public void setPlayCount(int idx, int n) {
        if (idx < 0 || idx >= PLAYLIST_SIZE) return;
        playCounts[idx] = Math.max(1, Math.min(MAX_PLAY_COUNT, n));
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
        ItemStack sa = playlist.getStackInSlot(a);
        playlist.setStackInSlot(a, playlist.getStackInSlot(b));
        playlist.setStackInSlot(b, sa);
        int pc = playCounts[a];
        playCounts[a] = playCounts[b];
        playCounts[b] = pc;
        markUpdated();
        return true;
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
        playMedia(inventory.getStackInSlot(MEDIA_SLOT));
    }

    /**
     * Plays the given recording medium at this device, through its range board and attenuation
     * mode. Shared by the single MEDIA_SLOT play and the playlist scheduler.
     */
    public boolean playMedia(ItemStack mediaStack) {
        if (!RecordingMediumItem.hasAudioData(mediaStack) || level == null) return false;

        byte[] audioData = AudioStorage.loadForItem(level.getServer(), mediaStack);
        if (audioData == null) return false;
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

        isPlaying = true;
        playbackStartTick = level.getGameTime();
        setChanged();
        level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);

        if (level instanceof ServerLevel sl) {
            long playbackId = PlaybackSessionRegistry.begin(sl, getBlockPos());
            ClientPlayAudioPayload metaPayload = new ClientPlayAudioPayload(
                    getBlockPos(), playbackId, audioData.length, format, rangePos1, rangePos2,
                    attenuationMode, attRanges);
            for (ServerPlayer sp : sl.players()) {
                PacketDistributor.sendToPlayer(sp, metaPayload);
                ClientAudioChunkPayload.sendChunked(sp, getBlockPos(), playbackId, audioData);
            }
        }
        return true;
    }

    public void stopPlayback() {
        isPlaying = false;
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
        if (!level.isClientSide() && entity.isPlaying) {
            // Safety-net timeout: if client never sent stop, auto-reset after PLAYBACK_TIMEOUT_TICKS
            if (level.getGameTime() - entity.playbackStartTick > PLAYBACK_TIMEOUT_TICKS) {
                entity.stopPlayback();
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.put("playlist", playlist.serializeNBT(registries));
        tag.putIntArray("playCounts", playCounts.clone());
        tag.putInt("entryCount", entryCount);
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
            playCounts[i] = (i < pc.length && pc[i] >= 1) ? Math.min(MAX_PLAY_COUNT, pc[i]) : 1;
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
        attenuationMode = tag.getBoolean("attenuationMode");
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
