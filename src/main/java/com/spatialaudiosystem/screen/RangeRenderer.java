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
    /** 視線追従。 実体は Manta の {@code SmoothFollow} (この実装の抽出元そのもの)。 */
    private static final com.manta.api.hud.SmoothFollow SMOOTH = new com.manta.api.hud.SmoothFollow();

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

        // The sound handy's range mode (Shift+R): the targeted device's board, drawn as the board
        // is in the hand (corner preview follows the look target) under the board's view mode.
        BlockPos handyTarget = null;
        ItemStack handy = com.manta.api.hud.HeldTools.find(mc.player, ModItems.SOUND_HANDY.get());
        if (!handy.isEmpty() && com.spatialaudiosystem.item.SoundHandyItem.rangeMode(handy)) {
            net.minecraft.core.GlobalPos target = handy.get(ModDataComponents.HANDY_SELECTED_DEVICE);
            if (target != null && target.dimension().equals(level.dimension())
                    && level.getBlockEntity(target.pos()) instanceof PlaybackDeviceBlockEntity targetBE) {
                handyTarget = target.pos();
                ItemStack board = targetBE.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.RANGE_SLOT);
                if (board.is(ModItems.RANGE_BOARD.get())) {
                    renderRangeBoardOutline(board, event.getPoseStack(), camera, bufferSource, hudMode, true);
                }
            }
        }
        // Shift+H: the targeted device's block, outlined through terrain, so "which one is the
        // target" survives walls and distance (user's real-device note 2026-09-05). The handy's
        // own accent, whatever the range mode; the box is drawn even for an unloaded chunk,
        // because the position is on the handy and that is what the player is looking for.
        if (!handy.isEmpty() && com.spatialaudiosystem.item.SoundHandyItem.highlightMode(handy)) {
            net.minecraft.core.GlobalPos target = handy.get(ModDataComponents.HANDY_SELECTED_DEVICE);
            if (target != null && target.dimension().equals(level.dimension())) {
                AABB box = new AABB(target.pos()).inflate(0.02).move(-camera.x, -camera.y, -camera.z);
                com.manta.api.render.WorldOutline.box(event.getPoseStack(), bufferSource, box,
                        0.31f, 0.76f, 0.97f, 0.9f, true);
            }
        }
        // Reset smooth state when nothing follows the look target
        if (!mainHasBoard && !offHasBoard && handyTarget == null) {
            SMOOTH.reset();
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
                        if (be instanceof PlaybackDeviceBlockEntity playbackBE && playbackBE.isShowRange()
                                && !playbackBE.getBlockPos().equals(handyTarget)) {
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
                SMOOTH.reset();
                BlockPos lookTarget = getLookTargetPos();
                if (lookTarget == null) return;
                renderBox(poseStack, camera, bufferSource, lookTarget, lookTarget, 0.0f, 1.0f, 1.0f, 0.4f);
                return;
            }

            // Pos1 only: Pos2 follows look target with smooth interpolation
            if (pos1 != null && pos2 == null) {
                BlockPos lookTarget = getLookTargetPos();
                if (lookTarget == null) return;
                BlockPos smoothPos = SMOOTH.update(lookTarget);
                renderBox(poseStack, camera, bufferSource, pos1, smoothPos, 0.0f, 1.0f, 1.0f, 0.3f);
                return;
            }
        }

        // Both set or device-slot board: static rendering
        if (!RangeBoardItem.hasRange(stack)) return;
        if (pos1 == null || pos2 == null) return;

        // Reset smooth state when both points are confirmed
        if (inHand) SMOOTH.reset();

        double minX = Math.min(pos1.getX(), pos2.getX());
        double minY = Math.min(pos1.getY(), pos2.getY());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxX = Math.max(pos1.getX(), pos2.getX()) + 1;
        double maxY = Math.max(pos1.getY(), pos2.getY()) + 1;
        double maxZ = Math.max(pos1.getZ(), pos2.getZ()) + 1;

        // Cyan range box (always shown)
        AABB rangeAabb = com.manta.api.render.WorldOutline.blockSpan(pos1, pos2, camera);
        com.manta.api.render.WorldOutline.box(poseStack, bufferSource, rangeAabb, 0.0f, 1.0f, 1.0f, 0.5f, false);

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
                com.manta.api.render.WorldOutline.box(poseStack, bufferSource, attAabb, 1.0f, 0.55f, 0.0f, 0.5f, false);
            }
        }
    }

    /** 視線の先のブロック。 実体は {@code LookTarget.blockPos} (4 実装を 1 本へ)。 */
    private static BlockPos getLookTargetPos() {
        Minecraft mc = Minecraft.getInstance();
        return com.manta.api.hud.LookTarget.blockPos(mc.player, mc.level, MAX_LOOK_DISTANCE);
    }

    /** 2 点が張る箱を camera 相対で描く。 実体は {@code WorldOutline.blockBox} (5 実装を 1 本へ)。 */
    private static void renderBox(PoseStack poseStack, Vec3 camera, MultiBufferSource bufferSource,
                                  BlockPos p1, BlockPos p2, float r, float g, float b, float a) {
        com.manta.api.render.WorldOutline.blockBox(poseStack, bufferSource, p1, p2, camera, r, g, b, a, false);
    }
}
