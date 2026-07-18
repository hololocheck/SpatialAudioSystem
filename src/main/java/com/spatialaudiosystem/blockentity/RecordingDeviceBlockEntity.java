package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.SpatialAudioSystem;
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

public class RecordingDeviceBlockEntity extends BlockEntity implements MenuProvider, OwnedDevice {
    public static final int INPUT_SLOT = 0;
    public static final int OUTPUT_SLOT = 1;
    public static final int SLOT_COUNT = 2;

    /** {@link #startRecording()} outcome codes, sent to the client so it can explain a refusal. */
    public static final int START_OK = 0;
    public static final int START_NO_MEDIUM = 1;       // input slot empty
    public static final int START_NO_FILE = 2;         // no audio picked/uploaded yet
    public static final int START_OUTPUT_OCCUPIED = 3; // output slot still holds a result

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
    private java.util.UUID ownerUUID = null;   // first player to open this device
    private String ownerName = null;
    private boolean privateMode = false;       // private = owner only (OwnerAccess ring red)

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
        if (!claimAndAllow(player)) return null;   // private device: owner only
        return new RecordingDeviceMenu(containerId, playerInventory, this);
    }

    public ItemStackHandler getInventory() {
        return inventory;
    }

    @Override
    @Nullable
    public java.util.UUID getOwnerUUID() {
        return ownerUUID;
    }

    @Override
    public void setOwner(java.util.UUID uuid, String name) {
        this.ownerUUID = uuid;
        this.ownerName = name;
        setChanged();
        syncToClients();
    }

    @Override
    public boolean isPrivateMode() {
        return privateMode;
    }

    @Override
    public void togglePrivateMode() {
        privateMode = !privateMode;
        setChanged();
        syncToClients();
    }

    private void syncToClients() {
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
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

    public String getPendingFormat() {
        return pendingFormat;
    }

    /** Server-side only: the uploaded-but-not-yet-written audio, or null. */
    public byte[] getPendingAudioData() {
        return pendingAudioData;
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
                // Only the medium's own reference is dropped. The file stays: the same
                // audio id can sit on any number of copied stacks, and this device cannot
                // see the others.
                ItemStack cleaned = stack.copy();
                cleaned.remove(ModDataComponents.AUDIO_ID);
                cleaned.remove(ModDataComponents.AUDIO_DATA);
                cleaned.remove(ModDataComponents.AUDIO_FILE_NAME);
                cleaned.remove(ModDataComponents.AUDIO_FORMAT);
                inventory.setStackInSlot(i, cleaned);
            }
        }
    }

    /** Begins writing the pending audio onto the input medium, or returns why it cannot. */
    public int startRecording() {
        if (inventory.getStackInSlot(INPUT_SLOT).isEmpty()) return START_NO_MEDIUM;
        if (pendingAudioData == null) return START_NO_FILE;
        if (!inventory.getStackInSlot(OUTPUT_SLOT).isEmpty()) return START_OUTPUT_OCCUPIED;

        isRecording = true;
        recordingProgress = 0;
        setChanged();
        return START_OK;
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
        boolean written = false;
        if (!inputStack.isEmpty() && pendingAudioData != null && level != null && level.getServer() != null) {
            // Save audio to server-side file instead of ItemStack component
            java.util.UUID audioId = AudioStorage.save(level.getServer(), pendingAudioData);
            if (audioId == null) {
                // The write failed. Keep the input medium and the pending bytes so the
                // operator can retry; consuming them here would destroy the only copy
                // and hand back a medium that cannot play.
                SpatialAudioSystem.LOGGER.error(
                        "Recording write failed at {}; input and pending audio kept for retry", worldPosition);
                isRecording = false;
                recordingProgress = 0;
                setChanged();
                return;
            }
            // Extract embedded cover art (MP3 ID3v2) so the jacket is ready from the moment
            // of writing; missing/unsupported art just leaves the placeholder.
            byte[] art = com.spatialaudiosystem.audio.AudioArt.extract(pendingAudioData, pendingFormat);
            if (art != null) {
                AudioStorage.saveArt(level.getServer(), audioId, art);
            }
            ItemStack outputStack = inputStack.copy();
            outputStack.set(ModDataComponents.AUDIO_ID, audioId);
            outputStack.remove(ModDataComponents.AUDIO_DATA); // ensure no legacy data
            outputStack.set(ModDataComponents.AUDIO_FILE_NAME, pendingFileName);
            outputStack.set(ModDataComponents.AUDIO_FORMAT, pendingFormat);
            // Duration を計算して保存 (整数秒、不能なら 0)
            int durSec = com.spatialaudiosystem.audio.AudioDuration.compute(pendingAudioData, pendingFormat);
            if (durSec > 0) outputStack.set(ModDataComponents.AUDIO_DURATION_SEC, durSec);

            inventory.setStackInSlot(INPUT_SLOT, ItemStack.EMPTY);
            inventory.setStackInSlot(OUTPUT_SLOT, outputStack);
            written = true;
        }
        isRecording = false;
        recordingProgress = 0;
        // Only a medium that actually received the audio consumes the pick. Pulling the input
        // out mid-write used to discard the upload here, the same loss the failed-save branch
        // above goes out of its way to avoid; the operator can now insert another medium.
        if (written) {
            pendingAudioData = null;
            pendingFileName = null;
            pendingFormat = null;
        }
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
        if (ownerUUID != null) {
            tag.putUUID("OwnerUUID", ownerUUID);
            if (ownerName != null) tag.putString("OwnerName", ownerName);
        }
        tag.putBoolean("PrivateMode", privateMode);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
        recordingProgress = tag.getInt("recordingProgress");
        isRecording = tag.getBoolean("isRecording");
        if (tag.hasUUID("OwnerUUID")) {
            ownerUUID = tag.getUUID("OwnerUUID");
            ownerName = tag.contains("OwnerName") ? tag.getString("OwnerName") : null;
        } else {
            ownerUUID = null;
        }
        privateMode = tag.getBoolean("PrivateMode");
        // Pending pick is transient (never saved); on the client this comes from the update tag.
        pendingFileName = tag.contains("PendingFileName") ? tag.getString("PendingFileName") : null;
        pendingFormat = tag.contains("PendingFormat") ? tag.getString("PendingFormat") : null;
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
        if (ownerUUID != null) tag.putUUID("OwnerUUID", ownerUUID);   // client needs it for the owner face
        tag.putBoolean("PrivateMode", privateMode);                   // client needs it for the ring colour
        if (pendingAudioData != null && pendingFileName != null) {    // let the client show the pending pick
            tag.putString("PendingFileName", pendingFileName);
            if (pendingFormat != null) tag.putString("PendingFormat", pendingFormat);
        }
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
