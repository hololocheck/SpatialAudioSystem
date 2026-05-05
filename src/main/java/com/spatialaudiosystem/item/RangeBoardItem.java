package com.spatialaudiosystem.item;

import com.spatialaudiosystem.network.ClientNotifyPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class RangeBoardItem extends Item {
    public RangeBoardItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        ItemStack stack = context.getItemInHand();
        BlockPos clickedPos = context.getClickedPos();

        if (player.isShiftKeyDown()) {
            // Shift+right-click: clear range
            stack.remove(ModDataComponents.RANGE_POS1);
            stack.remove(ModDataComponents.RANGE_POS2);
            sendNotify(player, Component.translatable("message.spatialaudiosystem.range_cleared").getString(), 0xFFFF55);
            return InteractionResult.SUCCESS;
        }

        if (!stack.has(ModDataComponents.RANGE_POS1)) {
            // First position
            stack.set(ModDataComponents.RANGE_POS1, clickedPos);
            sendNotify(player, Component.translatable("message.spatialaudiosystem.pos1_set",
                    clickedPos.getX(), clickedPos.getY(), clickedPos.getZ()).getString(), 0x55FF55);
        } else {
            // Second position
            stack.set(ModDataComponents.RANGE_POS2, clickedPos);
            sendNotify(player, Component.translatable("message.spatialaudiosystem.pos2_set",
                    clickedPos.getX(), clickedPos.getY(), clickedPos.getZ()).getString(), 0x55FF55);
        }

        return InteractionResult.SUCCESS;
    }

    private static final double MAX_RANGE = 64.0;

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) return InteractionResultHolder.success(stack);

        if (player.isShiftKeyDown()) {
            stack.remove(ModDataComponents.RANGE_POS1);
            stack.remove(ModDataComponents.RANGE_POS2);
            sendNotify(player, Component.translatable("message.spatialaudiosystem.range_cleared").getString(), 0xFFFF55);
            return InteractionResultHolder.success(stack);
        }

        // Long-range raycast: select blocks up to 64 blocks away
        BlockPos targetPos = getLookTargetBlock(player, level);
        if (targetPos != null) {
            if (!stack.has(ModDataComponents.RANGE_POS1)) {
                stack.set(ModDataComponents.RANGE_POS1, targetPos);
                sendNotify(player, Component.translatable("message.spatialaudiosystem.pos1_set",
                        targetPos.getX(), targetPos.getY(), targetPos.getZ()).getString(), 0x55FF55);
            } else {
                stack.set(ModDataComponents.RANGE_POS2, targetPos);
                sendNotify(player, Component.translatable("message.spatialaudiosystem.pos2_set",
                        targetPos.getX(), targetPos.getY(), targetPos.getZ()).getString(), 0x55FF55);
            }
        }
        return InteractionResultHolder.success(stack);
    }

    /** Send a HUD notification to the player via ClientNotifyPayload. */
    private static void sendNotify(Player player, String message, int color) {
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new ClientNotifyPayload(message, color));
        }
    }

    private static BlockPos getLookTargetBlock(Player player, Level level) {
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(MAX_RANGE));
        BlockHitResult hitResult = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            return hitResult.getBlockPos();
        }
        return null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        if (stack.has(ModDataComponents.RANGE_POS1) && stack.has(ModDataComponents.RANGE_POS2)) {
            BlockPos pos1 = stack.get(ModDataComponents.RANGE_POS1);
            BlockPos pos2 = stack.get(ModDataComponents.RANGE_POS2);
            tooltipComponents.add(Component.literal("Pos1: " + pos1.getX() + ", " + pos1.getY() + ", " + pos1.getZ())
                    .withStyle(ChatFormatting.AQUA));
            tooltipComponents.add(Component.literal("Pos2: " + pos2.getX() + ", " + pos2.getY() + ", " + pos2.getZ())
                    .withStyle(ChatFormatting.AQUA));
        } else if (stack.has(ModDataComponents.RANGE_POS1)) {
            BlockPos pos1 = stack.get(ModDataComponents.RANGE_POS1);
            tooltipComponents.add(Component.literal("Pos1: " + pos1.getX() + ", " + pos1.getY() + ", " + pos1.getZ())
                    .withStyle(ChatFormatting.AQUA));
            tooltipComponents.add(Component.translatable("tooltip.spatialaudiosystem.range_board.set_pos2")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.spatialaudiosystem.range_board.no_range")
                    .withStyle(ChatFormatting.GRAY));
        }
        // Attenuation ranges: [East=0, West=1, Up=2, Down=3, South=4, North=5]
        int[] ranges = ModDataComponents.getAttenuationRangesArray(stack);
        tooltipComponents.add(Component.literal("Attenuation:").withStyle(ChatFormatting.GRAY));
        tooltipComponents.add(Component.literal(
                "E:" + ranges[0] + "  W:" + ranges[1] + "  U:" + ranges[2] + " blocks")
                .withStyle(ChatFormatting.WHITE));
        tooltipComponents.add(Component.literal(
                "D:" + ranges[3] + "  S:" + ranges[4] + "  N:" + ranges[5] + " blocks")
                .withStyle(ChatFormatting.WHITE));

        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    public static boolean hasRange(ItemStack stack) {
        return stack.has(ModDataComponents.RANGE_POS1) && stack.has(ModDataComponents.RANGE_POS2);
    }
}
