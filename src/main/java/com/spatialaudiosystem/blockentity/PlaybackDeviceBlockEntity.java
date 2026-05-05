package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.audio.AudioStorage;
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
        return new PlaybackDeviceMenu(containerId, playerInventory, this);
    }

    public ItemStackHandler getInventory() {
        return inventory;
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
        ItemStack mediaStack = inventory.getStackInSlot(MEDIA_SLOT);
        if (!RecordingMediumItem.hasAudioData(mediaStack) || level == null) return;

        byte[] audioData = AudioStorage.loadForItem(level.getServer(), mediaStack);
        if (audioData == null) return;
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

        ClientPlayAudioPayload metaPayload = new ClientPlayAudioPayload(
                getBlockPos(), audioData.length, format, rangePos1, rangePos2,
                attenuationMode, attRanges);
        if (level instanceof ServerLevel sl) {
            for (ServerPlayer sp : sl.getServer().getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(sp, metaPayload);
                ClientAudioChunkPayload.sendChunked(sp, getBlockPos(), audioData);
            }
        }
    }

    public void stopPlayback() {
        isPlaying = false;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            ClientStopAudioPayload clientPayload = new ClientStopAudioPayload(getBlockPos());
            if (level instanceof ServerLevel sl) {
                for (ServerPlayer sp : sl.getServer().getPlayerList().getPlayers()) {
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
        tag.putBoolean("showRange", showRange);
        tag.putBoolean("isPlaying", isPlaying);
        tag.putBoolean("attenuationMode", attenuationMode);
        tag.putInt("attenuationRange", attenuationRange);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
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
        return tag;
    }
}
