package com.spatialaudiosystem.screen;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.RangeBoardItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;


@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public class RangeRenderer {

    private static final double MAX_LOOK_DISTANCE = 64.0;
    private static final float LERP_SPEED = 12.0f;

    // Smooth interpolation state
    private static double smoothX = 0, smoothY = 0, smoothZ = 0;
    private static boolean smoothInitialized = false;
    private static long lastNano = System.nanoTime();

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Level level = mc.level;
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // Render range for held range boards
        ItemStack mainHand = mc.player.getMainHandItem();
        ItemStack offHand = mc.player.getOffhandItem();
        boolean mainHasBoard = mainHand.is(ModItems.RANGE_BOARD.get());
        boolean offHasBoard = offHand.is(ModItems.RANGE_BOARD.get());

        int hudMode = RangeBoardHudRenderer.currentMode;

        if (mainHasBoard) {
            renderRangeBoardOutline(mainHand, event.getPoseStack(), camera, bufferSource, hudMode, true);
        }
        if (offHasBoard) {
            renderRangeBoardOutline(offHand, event.getPoseStack(), camera, bufferSource, hudMode, true);
        }

        // Reset smooth state when not holding a range board
        if (!mainHasBoard && !offHasBoard) {
            smoothInitialized = false;
        }

        // Render range for playback devices with showRange enabled
        BlockPos playerPos = mc.player.blockPosition();
        int chunkRange = 16;
        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;
        for (int cx = -chunkRange; cx <= chunkRange; cx++) {
            for (int cz = -chunkRange; cz <= chunkRange; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(playerChunkX + cx, playerChunkZ + cz);
                if (chunk != null) {
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof PlaybackDeviceBlockEntity playbackBE && playbackBE.isShowRange()) {
                            ItemStack rangeStack = playbackBE.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.RANGE_SLOT);
                            // For device-slot boards: show attenuation box if device has attenuationMode on
                            int deviceMode = playbackBE.isAttenuationMode() ? 1 : 0;
                            renderRangeBoardOutline(rangeStack, event.getPoseStack(), camera, bufferSource, deviceMode, false);
                        }
                    }
                }
            }
        }

        bufferSource.endBatch();
    }

    private static void renderRangeBoardOutline(ItemStack stack, PoseStack poseStack, Vec3 camera,
                                                 MultiBufferSource bufferSource, int mode, boolean inHand) {
        if (stack.isEmpty()) return;

        BlockPos pos1 = stack.get(ModDataComponents.RANGE_POS1);
        BlockPos pos2 = stack.get(ModDataComponents.RANGE_POS2);

        // Follow logic only for hand-held boards in normal mode (mode 0)
        if (inHand) {
            // Both unset: show 1-block preview at look target
            if (pos1 == null && pos2 == null) {
                smoothInitialized = false;
                BlockPos lookTarget = getLookTargetPos();
                if (lookTarget == null) return;
                renderBox(poseStack, camera, bufferSource, lookTarget, lookTarget, 0.0f, 1.0f, 1.0f, 0.4f);
                return;
            }

            // Pos1 only: Pos2 follows look target with smooth interpolation
            if (pos1 != null && pos2 == null) {
                BlockPos lookTarget = getLookTargetPos();
                if (lookTarget == null) return;
                BlockPos smoothPos = updateSmooth(lookTarget);
                renderBox(poseStack, camera, bufferSource, pos1, smoothPos, 0.0f, 1.0f, 1.0f, 0.3f);
                return;
            }
        }

        // Both set or device-slot board: static rendering
        if (!RangeBoardItem.hasRange(stack)) return;
        if (pos1 == null || pos2 == null) return;

        // Reset smooth state when both points are confirmed
        if (inHand) smoothInitialized = false;

        double minX = Math.min(pos1.getX(), pos2.getX());
        double minY = Math.min(pos1.getY(), pos2.getY());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        double maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        double maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        // Cyan range box (always shown)
        AABB rangeAabb = new AABB(
                minX - camera.x, minY - camera.y, minZ - camera.z,
                maxX - camera.x, maxY - camera.y, maxZ - camera.z);
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, consumer, rangeAabb, 0.0f, 1.0f, 1.0f, 0.5f);

        // Orange attenuation box: shown in mode 1 or mode 2,
        // or for device-slot boards with attenuation on (mode != 0)
        if (mode != 0) {
            int[] ranges = ModDataComponents.getAttenuationRangesArray(stack);
            double aMinX = minX - ranges[1];
            double aMaxX = maxX + ranges[0];
            double aMinY = minY - ranges[3];
            double aMaxY = maxY + ranges[2];
            double aMinZ = minZ - ranges[5];
            double aMaxZ = maxZ + ranges[4];

            boolean hasDiff = ranges[0] > 0 || ranges[1] > 0 || ranges[2] > 0
                    || ranges[3] > 0 || ranges[4] > 0 || ranges[5] > 0;
            if (hasDiff) {
                AABB attAabb = new AABB(
                        aMinX - camera.x, aMinY - camera.y, aMinZ - camera.z,
                        aMaxX - camera.x, aMaxY - camera.y, aMaxZ - camera.z);
                LevelRenderer.renderLineBox(poseStack, consumer, attAabb, 1.0f, 0.55f, 0.0f, 0.5f);
            }
        }
    }

    private static BlockPos updateSmooth(BlockPos target) {
        long now = System.nanoTime();
        float dt = (now - lastNano) / 1_000_000_000f;
        lastNano = now;
        dt = Math.min(dt, 0.1f);

        double targetX = target.getX();
        double targetY = target.getY();
        double targetZ = target.getZ();

        if (!smoothInitialized) {
            smoothX = targetX;
            smoothY = targetY;
            smoothZ = targetZ;
            smoothInitialized = true;
        } else {
            float factor = 1.0f - (float) Math.exp(-LERP_SPEED * dt);
            smoothX += (targetX - smoothX) * factor;
            smoothY += (targetY - smoothY) * factor;
            smoothZ += (targetZ - smoothZ) * factor;
        }

        return new BlockPos(
                (int) Math.round(smoothX),
                (int) Math.round(smoothY),
                (int) Math.round(smoothZ));
    }

    private static BlockPos getLookTargetPos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;

        Vec3 eye = mc.player.getEyePosition(1.0f);
        Vec3 look = mc.player.getLookAngle();
        Vec3 end = eye.add(look.scale(MAX_LOOK_DISTANCE));
        BlockHitResult hitResult = mc.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            return hitResult.getBlockPos();
        }
        return null;
    }

    private static void renderBox(PoseStack poseStack, Vec3 camera, MultiBufferSource bufferSource,
                                   BlockPos p1, BlockPos p2, float r, float g, float b, float a) {
        double minX = Math.min(p1.getX(), p2.getX());
        double minY = Math.min(p1.getY(), p2.getY());
        double minZ = Math.min(p1.getZ(), p2.getZ());
        double maxX = Math.max(p1.getX(), p2.getX()) + 1;
        double maxY = Math.max(p1.getY(), p2.getY()) + 1;
        double maxZ = Math.max(p1.getZ(), p2.getZ()) + 1;

        AABB aabb = new AABB(
                minX - camera.x, minY - camera.y, minZ - camera.z,
                maxX - camera.x, maxY - camera.y, maxZ - camera.z);

        VertexConsumer consumer = bufferSource.getBuffer(RenderType.lines());
        LevelRenderer.renderLineBox(poseStack, consumer, aabb, r, g, b, a);
    }
}
