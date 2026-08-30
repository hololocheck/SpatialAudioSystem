package com.spatialaudiosystem.audio;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * How loud a sound at one place is for a listener at another.
 *
 * <p>This exists so that there is exactly one answer to "can this player hear it". The client
 * uses it every frame to set the line's gain; the server uses it to decide whether a player who
 * just joined, or who just walked in from another dimension, should be sent the audio at all.
 *
 * <p>Those two had to agree and could not have been kept in agreement as two copies. A server
 * that thinks the region is larger than the client does ships megabytes to someone who will hear
 * silence; one that thinks it is smaller leaves a player standing inside an audible box with
 * nothing playing. Neither shows up as an error. So the predicate is the same code for both,
 * and "audible" is defined as nothing more than a positive gain.
 *
 * <p>No client-only types are referenced here: the class loads on a dedicated server.
 */
public final class SpatialGain {

    private SpatialGain() {}

    /** Fade distance used when a device has no range board and attenuation is switched off. */
    public static final double AMBIENT_FALLOFF_BLOCKS = 160.0;

    /** Range assumed when the attenuation array cannot supply one. Mirrors the device default. */
    public static final int DEFAULT_RANGE_BLOCKS = 8;

    /**
     * Linear volume in {@code [0, 1]} for a listener at {@code (px, py, pz)}.
     *
     * @param devicePos         the sounding device; used only when there is no range box
     * @param rangePos1         one corner of the range board's box, or null for none
     * @param rangePos2         the other corner, or null for none
     * @param attenuationMode   true: fade by the per-face distances, false: hard box / gentle falloff
     * @param ranges            per-face distances {@code [E, W, U, D, S, N]}; may be null
     */
    public static float linearGain(double px, double py, double pz,
                                   BlockPos devicePos,
                                   BlockPos rangePos1, BlockPos rangePos2,
                                   boolean attenuationMode, int[] ranges) {
        if (rangePos1 != null && rangePos2 != null) {
            AABB box = boxOf(rangePos1, rangePos2);
            if (box.contains(px, py, pz)) return 1.0f;
            // Attenuation off means the box is the whole of it: outside is silent, not quiet.
            if (!attenuationMode) return 0.0f;
            if (ranges == null || ranges.length < 6) return 0.0f;

            double fx = axisFactor(px, box.minX, box.maxX, ranges[1], ranges[0]);
            double fy = axisFactor(py, box.minY, box.maxY, ranges[3], ranges[2]);
            double fz = axisFactor(pz, box.minZ, box.maxZ, ranges[5], ranges[4]);
            return (float) Math.max(0.0, fx * fy * fz);
        }

        if (devicePos == null) return 0.0f;
        double dx = px - (devicePos.getX() + 0.5);
        double dy = py - (devicePos.getY() + 0.5);
        double dz = pz - (devicePos.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (attenuationMode) {
            // The server fills the array with the device's own range when no board is inserted,
            // so this is that range and not a constant.
            int range = (ranges != null && ranges.length > 0) ? ranges[0] : DEFAULT_RANGE_BLOCKS;
            return range <= 0 ? 0.0f : (float) Math.max(0.0, 1.0 - dist / range);
        }
        return (float) Math.max(0.0, 1.0 - Math.min(1.0, dist / AMBIENT_FALLOFF_BLOCKS));
    }

    /**
     * Whether the listener is anywhere the sound can be heard at all.
     *
     * <p>Defined as a positive gain rather than as its own region test, so it cannot describe a
     * different shape from the one the client actually plays.
     */
    public static boolean audible(double px, double py, double pz,
                                  BlockPos devicePos,
                                  BlockPos rangePos1, BlockPos rangePos2,
                                  boolean attenuationMode, int[] ranges) {
        return linearGain(px, py, pz, devicePos, rangePos1, rangePos2, attenuationMode, ranges) > 0.0f;
    }

    /** The range board's two corners as the block-inclusive box the audio uses. */
    public static AABB boxOf(BlockPos a, BlockPos b) {
        return new AABB(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
    }

    /** Linear fade along one axis: full inside the span, tapering to nothing at {@code range}. */
    private static double axisFactor(double p, double min, double max, int negRange, int posRange) {
        if (p < min) {
            double dist = min - p;
            return negRange <= 0 ? 0.0 : Math.max(0.0, 1.0 - dist / negRange);
        } else if (p > max) {
            double dist = p - max;
            return posRange <= 0 ? 0.0 : Math.max(0.0, 1.0 - dist / posRange);
        }
        return 1.0;
    }
}
