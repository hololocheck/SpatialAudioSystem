package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.block.ModBlocks;
import com.spatialaudiosystem.block.PlaybackDeviceBlock;
import com.spatialaudiosystem.block.RecordingDeviceBlock;
import com.spatialaudiosystem.handy.SoundDeviceRegistry;
import com.spatialaudiosystem.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a playback or recording device holds when its block goes: broken, it drops all of it; emptied by a command
 * first, as a vanilla container is, it drops none (the user's decisions of 2026-10-07).
 *
 * <p>Found 2026-10-07 on the real client (BelugaAOS run 20261007-213324-73b910): {@code /clone ... move} doubled both
 * devices' contents - four media and a range board on the ground and the same five in the target. Vanilla's clone
 * saves the source block entity (CloneCommands:262), clears it with Clearable.tryClear (:281), replaces it with a
 * barrier (:282), and loads what it saved into the target (:313). The devices were not Clearable, so their own onRemove,
 * run as the barrier replaced them, dropped what the target was about to receive.
 *
 * <p>The tests run that order on real block entities, through the blocks' own onRemove as LevelChunk.setBlockState
 * reaches it ({@code BlockState.onRemove}), on a mocked server level that keeps the item entities it is given. Every
 * stack carries its own name, so a slot dropped twice or a slot never dropped shows as a name too many or missing.
 *
 * <p>Not looked at here: that {@code /setblock}, {@code /fill} and structure placement call Clearable.tryClear before
 * they replace a block (vanilla - read, and measured on the real client for {@code /clone}), and that the dropped
 * stacks reach the ground of a real world (measured on the real client).
 */
class DeviceContentsTest {

    private static final BlockPos AT = new BlockPos(5, -60, 0);
    private static final BlockPos TARGET = new BlockPos(5, -60, 4);
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    private final List<Entity> added = new ArrayList<>();
    /** The devices' handlers, looked at each time an entity reaches the level. */
    private final List<ItemStackHandler> watched = new ArrayList<>();
    /** Slots that still held the stack being dropped when its item reached the level: dropped before emptied. */
    private final List<String> stillHeldWhenDropped = new ArrayList<>();
    private ServerLevel level;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        // The playback block's onRemove cuts the device out of its owner's handy list (SoundDeviceLink.onRemoved).
        MinecraftServer server = mock(MinecraftServer.class);
        DimensionDataStorage storage = mock(DimensionDataStorage.class);
        when(storage.computeIfAbsent(any(SavedData.Factory.class), anyString()))
                .thenReturn(SoundDeviceRegistry.load(new CompoundTag(), null));
        ServerLevel overworld = mock(ServerLevel.class);
        when(overworld.getDataStorage()).thenReturn(storage);
        when(server.overworld()).thenReturn(overworld);
        level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.addFreshEntity(any(Entity.class))).thenAnswer(call -> {
            Entity entity = call.getArgument(0);
            String name = entity instanceof ItemEntity item ? item.getItem().getHoverName().getString() : null;
            // Containers.dropItemStack splits the stack it is handed before the entity reaches here: a slot that still
            // holds that stack holds it drained (an empty stack that is not EMPTY), and a slot that still holds a stack
            // of the dropped item's name was dropped from - a copy - before it was emptied. Every name is unique.
            for (ItemStackHandler slots : watched) {
                for (int i = 0; i < slots.getSlots(); i++) {
                    ItemStack stack = slots.getStackInSlot(i);
                    boolean drained = stack.isEmpty() && stack != ItemStack.EMPTY;
                    boolean sameItem = !stack.isEmpty() && stack.getHoverName().getString().equals(name);
                    if (drained || sameItem) stillHeldWhenDropped.add(slots.getSlots() + "-slot handler, slot " + i);
                }
            }
            return added.add(entity);
        });
        // Containers.dropItemStack and ItemEntity read the level's own random, a field a mock never initialises.
        Field random = Level.class.getDeclaredField("random");
        random.setAccessible(true);
        random.set(level, RandomSource.create(0));
    }

    private static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    /** Playlist rows at both ends and in the middle, so a loop that stops short or skips leaves a name behind. */
    private static final String[] PLAYBACK_CONTENTS = {"single medium", "range board", "row 0", "row 7", "row 15"};
    private static final String[] RECORDING_CONTENTS = {"to write", "written"};

    private PlaybackDeviceBlockEntity playbackDeviceHolding(BlockPos pos) {
        PlaybackDeviceBlockEntity device =
                new PlaybackDeviceBlockEntity(pos, ModBlocks.PLAYBACK_DEVICE.get().defaultBlockState());
        device.setLevel(level);
        when(level.getBlockEntity(pos)).thenReturn(device);
        watched.add(device.getInventory());
        watched.add(device.getPlaylist());
        device.getInventory().setStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT,
                named(ModItems.RECORDING_MEDIUM.get(), "single medium"));
        device.getInventory().setStackInSlot(PlaybackDeviceBlockEntity.RANGE_SLOT,
                named(ModItems.RANGE_BOARD.get(), "range board"));
        for (int row : new int[] {0, 7, PlaybackDeviceBlockEntity.PLAYLIST_SIZE - 1}) {
            device.getPlaylist().setStackInSlot(row, named(ModItems.RECORDING_MEDIUM.get(), "row " + row));
        }
        return device;
    }

    private RecordingDeviceBlockEntity recordingDeviceHolding(BlockPos pos) {
        RecordingDeviceBlockEntity device =
                new RecordingDeviceBlockEntity(pos, ModBlocks.RECORDING_DEVICE.get().defaultBlockState());
        device.setLevel(level);
        when(level.getBlockEntity(pos)).thenReturn(device);
        watched.add(device.getInventory());
        device.getInventory().setStackInSlot(RecordingDeviceBlockEntity.INPUT_SLOT,
                named(ModItems.RECORDING_MEDIUM.get(), "to write"));
        device.getInventory().setStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT,
                named(ModItems.RECORDING_MEDIUM.get(), "written"));
        return device;
    }

    /** One name per item that reached the level, from every entity added to it - an entity that is not an item fails. */
    private List<String> droppedNames() {
        List<String> names = new ArrayList<>();
        for (Entity entity : added) {
            assertThat(entity).as("an entity added to the level").isInstanceOf(ItemEntity.class);
            ItemStack stack = ((ItemEntity) entity).getItem();
            for (int i = 0; i < stack.getCount(); i++) names.add(stack.getHoverName().getString());
        }
        return names;
    }

    private static List<String> namesIn(ItemStackHandler... handlers) {
        List<String> names = new ArrayList<>();
        for (ItemStackHandler slots : handlers) {
            for (int i = 0; i < slots.getSlots(); i++) {
                ItemStack stack = slots.getStackInSlot(i);
                if (!stack.isEmpty()) names.add(stack.getHoverName().getString());
            }
        }
        return names;
    }

    /**
     * Every slot holds EMPTY itself, and held it before its stack was dropped. A stack drained behind the handler's back
     * by Containers.dropItemStack is empty too, but it is still the stack that was there - the handler was never told,
     * or was told only after the stack it returned had been changed, or after a copy of it had already been dropped.
     */
    private void assertEmptiedThroughTheHandlerFirst(ItemStackHandler slots, String what) {
        for (int i = 0; i < slots.getSlots(); i++) {
            assertThat(slots.getStackInSlot(i)).as("%s slot %d", what, i).isSameAs(ItemStack.EMPTY);
        }
        assertThat(stillHeldWhenDropped).as("slots still holding a stack when its item was dropped").isEmpty();
    }

    // ===== broken: everything drops =====

    @Test
    @DisplayName("a playback device that is broken drops every slot it holds and keeps none of them")
    void aBrokenPlaybackDeviceDropsEverySlot() {
        PlaybackDeviceBlockEntity device = playbackDeviceHolding(AT);

        device.getBlockState().onRemove(level, AT, Blocks.AIR.defaultBlockState(), false);

        assertThat(droppedNames()).containsExactlyInAnyOrder(PLAYBACK_CONTENTS);
        assertEmptiedThroughTheHandlerFirst(device.getInventory(), "media slots");
        assertEmptiedThroughTheHandlerFirst(device.getPlaylist(), "playlist");
    }

    @Test
    @DisplayName("a recording device that is broken drops both slots and keeps neither")
    void aBrokenRecordingDeviceDropsBothSlots() {
        RecordingDeviceBlockEntity device = recordingDeviceHolding(AT);

        device.getBlockState().onRemove(level, AT, Blocks.AIR.defaultBlockState(), false);

        assertThat(droppedNames()).containsExactlyInAnyOrder(RECORDING_CONTENTS);
        assertEmptiedThroughTheHandlerFirst(device.getInventory(), "slots");
    }

    // ===== /clone ... move: the contents move, once =====

    @Test
    @DisplayName("/clone ... move carries a playback device's contents to the target and drops none of them")
    void cloneMoveCarriesThePlaybackDevicesContents() {
        PlaybackDeviceBlockEntity source = playbackDeviceHolding(AT);

        CompoundTag saved = source.saveCustomOnly(REGISTRIES);                                  // CloneCommands:262
        Clearable.tryClear(source);                                                             // :281
        source.getBlockState().onRemove(level, AT, Blocks.BARRIER.defaultBlockState(), false);  // :282
        PlaybackDeviceBlockEntity target =
                new PlaybackDeviceBlockEntity(TARGET, ModBlocks.PLAYBACK_DEVICE.get().defaultBlockState());
        target.loadCustomOnly(saved, REGISTRIES);                                               // :313

        assertThat(droppedNames()).as("on the ground").isEmpty();
        assertThat(namesIn(target.getInventory(), target.getPlaylist())).as("in the target")
                .containsExactlyInAnyOrder(PLAYBACK_CONTENTS);
    }

    @Test
    @DisplayName("/clone ... move carries a recording device's contents to the target and drops none of them")
    void cloneMoveCarriesTheRecordingDevicesContents() {
        RecordingDeviceBlockEntity source = recordingDeviceHolding(AT);

        CompoundTag saved = source.saveCustomOnly(REGISTRIES);
        Clearable.tryClear(source);
        source.getBlockState().onRemove(level, AT, Blocks.BARRIER.defaultBlockState(), false);
        RecordingDeviceBlockEntity target =
                new RecordingDeviceBlockEntity(TARGET, ModBlocks.RECORDING_DEVICE.get().defaultBlockState());
        target.loadCustomOnly(saved, REGISTRIES);

        assertThat(droppedNames()).as("on the ground").isEmpty();
        assertThat(namesIn(target.getInventory())).as("in the target").containsExactlyInAnyOrder(RECORDING_CONTENTS);
    }

    // ===== the same block in another state is not a removal =====

    @Test
    @DisplayName("a redstone signal changing the playback device's state drops nothing")
    void aPoweredPlaybackDeviceKeepsItsContents() {
        PlaybackDeviceBlockEntity device = playbackDeviceHolding(AT);
        BlockState state = device.getBlockState();

        state.onRemove(level, AT, state.setValue(PlaybackDeviceBlock.POWERED, true), false);

        assertThat(droppedNames()).isEmpty();
        assertThat(namesIn(device.getInventory(), device.getPlaylist())).containsExactlyInAnyOrder(PLAYBACK_CONTENTS);
    }

    @Test
    @DisplayName("turning the recording device to face another way drops nothing")
    void aTurnedRecordingDeviceKeepsItsContents() {
        RecordingDeviceBlockEntity device = recordingDeviceHolding(AT);
        BlockState state = device.getBlockState();

        state.onRemove(level, AT, state.setValue(RecordingDeviceBlock.FACING, Direction.EAST), false);

        assertThat(droppedNames()).isEmpty();
        assertThat(namesIn(device.getInventory())).containsExactlyInAnyOrder(RECORDING_CONTENTS);
    }
}
