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
    /** Menu index of the first playlist media slot (after media, range, 36 player-inventory slots). */
    public static final int PLAYLIST_MENU_BASE = PlaybackDeviceBlockEntity.SLOT_COUNT + 36; // 38

    private final PlaybackDeviceBlockEntity blockEntity;
    private final ContainerLevelAccess access;
    private final boolean remote;

    // Client constructor
    public PlaybackDeviceMenu(int containerId, Inventory playerInventory, FriendlyByteBuf data) {
        this(containerId, playerInventory, getBlockEntity(playerInventory, data));
    }

    // Server constructor
    public PlaybackDeviceMenu(int containerId, Inventory playerInventory, PlaybackDeviceBlockEntity blockEntity) {
        this(containerId, playerInventory, blockEntity, false);
    }

    /**
     * Server constructor; {@code remote} = opened through the sound handy (spec §2.5), so
     * {@link #stillValid} asks whether the handy is still in hand and the player still owns the
     * device, not how far away the block is. The client never reads the flag: only the
     * server closes a menu on validity.
     */
    public PlaybackDeviceMenu(int containerId, Inventory playerInventory, PlaybackDeviceBlockEntity blockEntity, boolean remote) {
        super(ModMenuTypes.PLAYBACK_DEVICE_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.remote = remote;
        this.access = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        ItemStackHandler handler = blockEntity.getInventory();

        // Media + range slots (menu 0,1) — positions come from the main JSON (syncSlotPositions).
        this.addSlot(new SlotItemHandler(handler, PlaybackDeviceBlockEntity.MEDIA_SLOT, 151, 49) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // Schedule mode bars single play: media goes to the playlist rows instead.
                return stack.is(ModItems.RECORDING_MEDIUM.get()) && !blockEntity.isScheduleMode();
            }
        });
        this.addSlot(new SlotItemHandler(handler, PlaybackDeviceBlockEntity.RANGE_SLOT, 151, 30) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.RANGE_BOARD.get());
            }
        });

        // Player inventory (menu 2..37) — must precede the popup slots so syncSlotPositions maps
        // the main layout's isSlot order (media, range, inventory) onto menu slots 0..37.
        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);

        // Playlist media slots (menu 38..53) — off-screen until the schedule popup positions them.
        ItemStackHandler playlist = blockEntity.getPlaylist();
        for (int i = 0; i < PlaybackDeviceBlockEntity.PLAYLIST_SIZE; i++) {
            final int entry = i;
            this.addSlot(new SlotItemHandler(playlist, i, -1000, -1000) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    // Only rows the editor actually shows take media, so a shift-click can
                    // never park an item in an entry that does not exist yet.
                    return stack.is(ModItems.RECORDING_MEDIUM.get())
                            && entry < blockEntity.getEntryCount();
                }
                @Override
                public boolean isActive() {
                    return this.x >= 0;   // inactive (and unclickable) while the popup is closed
                }
            });
        }
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

            int playerStart = PlaybackDeviceBlockEntity.SLOT_COUNT;   // 2
            boolean deviceSlot = index < playerStart || index >= PLAYLIST_MENU_BASE;
            if (deviceSlot) {
                // Device (media / range / playlist) → player inventory
                if (!this.moveItemStackTo(slotStack, playerStart, PLAYLIST_MENU_BASE, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (slotStack.is(ModItems.RANGE_BOARD.get())) {
                if (!this.moveItemStackTo(slotStack, PlaybackDeviceBlockEntity.RANGE_SLOT,
                        PlaybackDeviceBlockEntity.RANGE_SLOT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (slotStack.is(ModItems.RECORDING_MEDIUM.get())) {
                if (blockEntity.isScheduleMode()) {
                    // Schedule mode: shift-click feeds the first free playlist row, not the barred slot.
                    if (!this.moveItemStackTo(slotStack, PLAYLIST_MENU_BASE,
                            PLAYLIST_MENU_BASE + PlaybackDeviceBlockEntity.PLAYLIST_SIZE, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (!this.moveItemStackTo(slotStack, PlaybackDeviceBlockEntity.MEDIA_SLOT,
                        PlaybackDeviceBlockEntity.MEDIA_SLOT + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else {
                return ItemStack.EMPTY;
            }

            if (slotStack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return quickMoveStack;
    }

    /**
     * The remote rule (spec §2.5) on its own, so the mutation ledger can break it: the device
     * is still there, the handy is still in hand, and the player is the owner.
     */
    static boolean remoteStillValid(boolean removed, boolean holdingHandy, java.util.UUID player, java.util.UUID owner) {
        return !removed && holdingHandy && player != null && player.equals(owner);
    }

    @Override
    public boolean stillValid(Player player) {
        if (remote) {
            return remoteStillValid(blockEntity.isRemoved(),
                    !com.spatialaudiosystem.item.SoundHandyItem.held(player).isEmpty(),
                    player.getUUID(), blockEntity.getOwnerUUID());
        }
        return stillValid(this.access, player, com.spatialaudiosystem.block.ModBlocks.PLAYBACK_DEVICE.get());
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == com.manta.api.hud.OwnerAccess.TOGGLE_BUTTON) {
            blockEntity.toggleOwnerAccess(player);   // owner-only; a no-op for anyone else
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
