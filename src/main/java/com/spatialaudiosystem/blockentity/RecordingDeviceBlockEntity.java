package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.RecordingMediumItem;
import com.spatialaudiosystem.menu.RecordingDeviceMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

public class RecordingDeviceBlockEntity extends BlockEntity implements MenuProvider {
    public static final int INPUT_SLOT = 0;
    public static final int OUTPUT_SLOT = 1;
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
            if (slot == INPUT_SLOT) return stack.is(ModItems.RECORDING_MEDIUM.get());
            if (slot == OUTPUT_SLOT) return false; // output only
            return false;
        }
    };

    private int recordingProgress = 0;
    private int maxRecordingProgress = 100;
    private boolean isRecording = false;
    private byte[] pendingAudioData = null;
    private String pendingFileName = null;
    private String pendingFormat = null;

    public RecordingDeviceBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RECORDING_DEVICE.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.spatialaudiosystem.recording_device");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new RecordingDeviceMenu(containerId, playerInventory, this);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    public int getRecordingProgress() {
        return recordingProgress;
    }

    public int getMaxRecordingProgress() {
        return maxRecordingProgress;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public void setPendingAudio(byte[] audioData, String fileName, String format) {
        this.pendingAudioData = audioData;
        this.pendingFileName = fileName;
        this.pendingFormat = format;
    }

    public String getPendingFileName() {
        return pendingFileName;
    }

    public void clearPendingAudio() {
        this.pendingAudioData = null;
        this.pendingFileName = null;
        this.pendingFormat = null;
        this.recordingProgress = 0;
        this.isRecording = false;
        setChanged();
    }

    public void clearMediaAudioData() {
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && (stack.has(ModDataComponents.AUDIO_DATA)
                    || stack.has(ModDataComponents.AUDIO_FILE_NAME)
                    || stack.has(ModDataComponents.AUDIO_FORMAT)
                    || stack.has(ModDataComponents.AUDIO_ID))) {
                // Delete the audio file if it exists
                java.util.UUID audioId = stack.get(ModDataComponents.AUDIO_ID);
                if (audioId != null && level != null && level.getServer() != null) {
                    AudioStorage.delete(level.getServer(), audioId);
                }
                ItemStack cleaned = stack.copy();
                cleaned.remove(ModDataComponents.AUDIO_ID);
                cleaned.remove(ModDataComponents.AUDIO_DATA);
                cleaned.remove(ModDataComponents.AUDIO_FILE_NAME);
                cleaned.remove(ModDataComponents.AUDIO_FORMAT);
                inventory.setStackInSlot(i, cleaned);
            }
        }
    }

    public boolean startRecording() {
        ItemStack inputStack = inventory.getStackInSlot(INPUT_SLOT);
        if (inputStack.isEmpty() || pendingAudioData == null) return false;
        if (!inventory.getStackInSlot(OUTPUT_SLOT).isEmpty()) return false;

        isRecording = true;
        recordingProgress = 0;
        setChanged();
        return true;
    }

    public void tickRecording() {
        if (!isRecording || pendingAudioData == null) return;

        recordingProgress++;
        if (recordingProgress >= maxRecordingProgress) {
            finishRecording();
        }
        setChanged();
    }

    private void finishRecording() {
        ItemStack inputStack = inventory.getStackInSlot(INPUT_SLOT);
        if (!inputStack.isEmpty() && pendingAudioData != null && level != null && level.getServer() != null) {
            ItemStack outputStack = inputStack.copy();
            // Save audio to server-side file instead of ItemStack component
            java.util.UUID audioId = AudioStorage.save(level.getServer(), pendingAudioData);
            outputStack.set(ModDataComponents.AUDIO_ID, audioId);
            outputStack.remove(ModDataComponents.AUDIO_DATA); // ensure no legacy data
            outputStack.set(ModDataComponents.AUDIO_FILE_NAME, pendingFileName);
            outputStack.set(ModDataComponents.AUDIO_FORMAT, pendingFormat);

            inventory.setStackInSlot(INPUT_SLOT, ItemStack.EMPTY);
            inventory.setStackInSlot(OUTPUT_SLOT, outputStack);
        }
        isRecording = false;
        recordingProgress = 0;
        pendingAudioData = null;
        pendingFileName = null;
        pendingFormat = null;
        setChanged();
    }

    public void drops() {
        if (level != null) {
            for (int i = 0; i < inventory.getSlots(); i++) {
                Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                        inventory.getStackInSlot(i));
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.putInt("recordingProgress", recordingProgress);
        tag.putBoolean("isRecording", isRecording);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        recordingProgress = tag.getInt("recordingProgress");
        isRecording = tag.getBoolean("isRecording");
        // Eager migration on load
        if (level != null && !level.isClientSide() && level.getServer() != null) {
            migrateInventory();
        }
    }

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
        tag.putInt("recordingProgress", recordingProgress);
        tag.putBoolean("isRecording", isRecording);
        // Serialize inventory without AUDIO_DATA (too large for network NBT).
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
