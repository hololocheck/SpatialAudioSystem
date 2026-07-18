package com.spatialaudiosystem.menu;

import com.spatialaudiosystem.block.ModBlocks;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import com.spatialaudiosystem.item.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public class RecordingDeviceMenu extends AbstractContainerMenu {
    private final RecordingDeviceBlockEntity blockEntity;
    private final ContainerLevelAccess access;
    private final ContainerData data;

    // Client constructor
    public RecordingDeviceMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, getBlockEntity(playerInventory, buf), new SimpleContainerData(2));
    }

    // Server constructor
    public RecordingDeviceMenu(int containerId, Inventory playerInventory, RecordingDeviceBlockEntity blockEntity) {
        this(containerId, playerInventory, blockEntity, createContainerData(blockEntity));
    }

    private RecordingDeviceMenu(int containerId, Inventory playerInventory,
                                RecordingDeviceBlockEntity blockEntity, ContainerData data) {
        super(ModMenuTypes.RECORDING_DEVICE_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.access = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());
        this.data = data;

        addDataSlots(data);

        ItemStackHandler handler = blockEntity.getInventory();

        // Input slot - below file button
        this.addSlot(new SlotItemHandler(handler, RecordingDeviceBlockEntity.INPUT_SLOT, 97, 54) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.RECORDING_MEDIUM.get());
            }
        });

        // Output slot - below cancel button
        this.addSlot(new SlotItemHandler(handler, RecordingDeviceBlockEntity.OUTPUT_SLOT, 151, 54) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });

        // Player inventory
        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);
    }

    private static ContainerData createContainerData(RecordingDeviceBlockEntity blockEntity) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> blockEntity.getRecordingProgress();
                    case 1 -> blockEntity.getMaxRecordingProgress();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 2;
            }
        };
    }

    private static RecordingDeviceBlockEntity getBlockEntity(Inventory playerInventory, FriendlyByteBuf data) {
        BlockEntity entity = playerInventory.player.level().getBlockEntity(data.readBlockPos());
        if (entity instanceof RecordingDeviceBlockEntity be) return be;
        throw new IllegalStateException("Block entity is not correct type at " + entity);
    }

    public RecordingDeviceBlockEntity getBlockEntity() {
        return blockEntity;
    }

    public int getRecordingProgress() {
        return data.get(0);
    }

    public int getMaxRecordingProgress() {
        return data.get(1);
    }

    public boolean isRecording() {
        return data.get(0) > 0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack quickMoveStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            quickMoveStack = slotStack.copy();

            if (index < RecordingDeviceBlockEntity.SLOT_COUNT) {
                if (!this.moveItemStackTo(slotStack, RecordingDeviceBlockEntity.SLOT_COUNT, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(slotStack, 0, 1, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (slotStack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return quickMoveStack;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, ModBlocks.RECORDING_DEVICE.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == belugalab.tsu.api.OwnerAccess.TOGGLE_BUTTON) {
            java.util.UUID owner = blockEntity.getOwnerUUID();
            if (owner == null || owner.equals(player.getUUID())) {   // owner-only toggle
                blockEntity.togglePrivateMode();
            }
            return true;
        }
        return super.clickMenuButton(player, id);
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                this.addSlot(new Slot(playerInventory, j + i * 9 + 9, 8 + j * 18, 84 + i * 18));
            }
        }
    }

    private void addPlayerHotbar(Inventory playerInventory) {
        for (int i = 0; i < 9; i++) {
            this.addSlot(new Slot(playerInventory, i, 8 + i * 18, 142));
        }
    }
}
