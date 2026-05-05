package com.spatialaudiosystem.menu;

import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.item.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public class PlaybackDeviceMenu extends AbstractContainerMenu {
    private final PlaybackDeviceBlockEntity blockEntity;
    private final ContainerLevelAccess access;

    // Client constructor
    public PlaybackDeviceMenu(int containerId, Inventory playerInventory, FriendlyByteBuf data) {
        this(containerId, playerInventory, getBlockEntity(playerInventory, data));
    }

    // Server constructor
    public PlaybackDeviceMenu(int containerId, Inventory playerInventory, PlaybackDeviceBlockEntity blockEntity) {
        super(ModMenuTypes.PLAYBACK_DEVICE_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.access = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        ItemStackHandler handler = blockEntity.getInventory();

        // Media slot (slot 0) - right of play/stop buttons (lower row)
        this.addSlot(new SlotItemHandler(handler, PlaybackDeviceBlockEntity.MEDIA_SLOT, 151, 49) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.RECORDING_MEDIUM.get());
            }
        });

        // Range board slot (slot 1) - right of attenuation/visibility buttons (upper row)
        this.addSlot(new SlotItemHandler(handler, PlaybackDeviceBlockEntity.RANGE_SLOT, 151, 30) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.RANGE_BOARD.get());
            }
        });

        // Player inventory
        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);
    }

    private static PlaybackDeviceBlockEntity getBlockEntity(Inventory playerInventory, FriendlyByteBuf data) {
        BlockEntity entity = playerInventory.player.level().getBlockEntity(data.readBlockPos());
        if (entity instanceof PlaybackDeviceBlockEntity be) return be;
        throw new IllegalStateException("Block entity is not correct type at " + entity);
    }

    public PlaybackDeviceBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack quickMoveStack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack slotStack = slot.getItem();
            quickMoveStack = slotStack.copy();

            if (index < PlaybackDeviceBlockEntity.SLOT_COUNT) {
                if (!this.moveItemStackTo(slotStack, PlaybackDeviceBlockEntity.SLOT_COUNT, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(slotStack, 0, PlaybackDeviceBlockEntity.SLOT_COUNT, false)) {
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
        return stillValid(this.access, player, com.spatialaudiosystem.block.ModBlocks.PLAYBACK_DEVICE.get());
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
