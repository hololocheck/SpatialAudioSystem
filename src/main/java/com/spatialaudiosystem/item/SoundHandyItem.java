package com.spatialaudiosystem.item;

import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.handy.SoundDeviceLink;
import com.spatialaudiosystem.network.ClientNotifyPayload;
import com.spatialaudiosystem.network.HandyRangeEditPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * The sound handy (spec §2, v2.0): the owner's remote for their playback devices.
 *
 * <p>No tool modes (user decision 2026-09-04, an exception to R3.1.1): a right-click on one of
 * the player's devices targets it, a right-click anywhere else opens the handy screen.
 * Shift+right-click clears the target. While the range mode (Shift+R) is on, right-clicks set
 * the two corners of the targeted device's range board instead, as the board itself does in
 * the hand, and Shift+right-click clears the box.
 *
 * <p>Everything the item holds is a data component on the stack: the target
 * ({@link ModDataComponents#HANDY_SELECTED_DEVICE}), the range mode
 * ({@link ModDataComponents#HANDY_RANGE_MODE}) and the HUD setting. The server is the
 * authority for all three; the client echoes for immediate feedback.
 */
public class SoundHandyItem extends Item {
    public SoundHandyItem(Properties properties) {
        super(properties);
    }

    /** The handy the player holds, main hand first; empty when neither hand has one. */
    public static ItemStack held(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.is(ModItems.SOUND_HANDY.get())) return main;
        ItemStack off = player.getOffhandItem();
        return off.is(ModItems.SOUND_HANDY.get()) ? off : ItemStack.EMPTY;
    }

    public static boolean rangeMode(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.HANDY_RANGE_MODE, false);
    }

    /** Shift+H: the targeted device's block is outlined through terrain. */
    public static boolean highlightMode(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.HANDY_HIGHLIGHT, false);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // Two handies: the one held() resolves to (main hand first) is the one every server
        // path reads, so the other defers to it (review 2026-09-04: the hands would disagree).
        if (stack != held(player)) return InteractionResultHolder.pass(stack);
        if (rangeMode(stack)) {
            // Range mode: the board's own air-click - a corner at the look target, Shift clears.
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                if (player.isShiftKeyDown()) {
                    HandyRangeEditPayload.apply(sp, HandyRangeEditPayload.clear());
                } else {
                    BlockPos target = RangeBoardItem.getLookTargetBlock(player, level);
                    if (target != null) setCorner(sp, target);
                }
            }
            return InteractionResultHolder.success(stack);
        }
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                stack.remove(ModDataComponents.HANDY_SELECTED_DEVICE);
                notify(player, "message.spatialaudiosystem.sound_handy.cleared", 0xFFFF55);
            }
            return InteractionResultHolder.success(stack);
        }
        // R3.8.2 / R3.9.1: the screen opens from the client through a thin dispatcher.
        if (level.isClientSide() && FMLEnvironment.dist == Dist.CLIENT) {
            com.spatialaudiosystem.client.SoundHandyScreenOpener.open(stack);
        }
        return InteractionResultHolder.success(stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (stack != held(player)) return InteractionResult.PASS;   // see use(): one handy counts
        if (rangeMode(stack)) {
            if (!level.isClientSide() && player instanceof ServerPlayer sp) {
                if (player.isShiftKeyDown()) {
                    HandyRangeEditPayload.apply(sp, HandyRangeEditPayload.clear());
                } else {
                    setCorner(sp, pos);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                stack.remove(ModDataComponents.HANDY_SELECTED_DEVICE);
                notify(player, "message.spatialaudiosystem.sound_handy.cleared", 0xFFFF55);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof PlaybackDeviceBlockEntity be)) {
            // Not a device: the block keeps nothing from us, and use() opens the screen.
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (be.getOwnerUUID() == null || !be.getOwnerUUID().equals(player.getUUID())) {
            notify(player, "message.spatialaudiosystem.sound_handy.not_owner", 0xFF5555);
            return InteractionResult.SUCCESS;
        }
        // A pre-1.1.0 device whose owner was only ever the first opener: make sure it is listed.
        if (level instanceof ServerLevel) SoundDeviceLink.onOwnerKnown(be);
        stack.set(ModDataComponents.HANDY_SELECTED_DEVICE, GlobalPos.of(level.dimension(), pos));
        notify(player, "message.spatialaudiosystem.sound_handy.selected\t" + displayName(be, pos), 0x55FF55);
        return InteractionResult.SUCCESS;
    }

    /** First corner when the board has none, the second otherwise - the board's own rule. */
    private static void setCorner(ServerPlayer player, BlockPos pos) {
        ItemStack handy = held(player);
        GlobalPos target = handy.get(ModDataComponents.HANDY_SELECTED_DEVICE);
        PlaybackDeviceBlockEntity be = target == null ? null
                : SoundDeviceLink.ownedDevice(player.server, player.getUUID(), target);
        ItemStack board = be == null ? ItemStack.EMPTY
                : be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.RANGE_SLOT);
        int op = board.has(ModDataComponents.RANGE_POS1) ? HandyRangeEditPayload.SET_POS2 : HandyRangeEditPayload.SET_POS1;
        HandyRangeEditPayload.apply(player, HandyRangeEditPayload.corner(op, pos));
    }

    /** The device's name, or its position when unnamed - the same text the list shows. */
    public static String displayName(PlaybackDeviceBlockEntity be, BlockPos pos) {
        String name = be.getDeviceName();
        return name != null ? name : unnamed(pos);
    }

    public static String unnamed(BlockPos pos) {
        return Component.translatable("gui.spatialaudiosystem.sound_handy.unnamed",
                pos.getX(), pos.getY(), pos.getZ()).getString();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        GlobalPos selected = stack.get(ModDataComponents.HANDY_SELECTED_DEVICE);
        if (selected != null) {
            BlockPos p = selected.pos();
            lines.add(Component.translatable("tooltip.spatialaudiosystem.sound_handy.selected",
                    p.getX(), p.getY(), p.getZ()).withStyle(ChatFormatting.WHITE));
        } else {
            lines.add(Component.translatable("tooltip.spatialaudiosystem.sound_handy.no_selection")
                    .withStyle(ChatFormatting.GRAY));
        }
        if (rangeMode(stack)) {
            lines.add(Component.translatable("tooltip.spatialaudiosystem.sound_handy.range_mode").withStyle(ChatFormatting.GOLD));
        }
        // A3.11: the last line is the operating hint.
        lines.add(Component.translatable("tooltip.spatialaudiosystem.sound_handy.hint").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, context, lines, flag);
    }

    /** Sends a lang key (with tab-separated arguments) that the client translates in its own language. */
    static void notify(Player player, String keyAndArgs, int color) {
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new ClientNotifyPayload(keyAndArgs, color));
        }
    }
}
