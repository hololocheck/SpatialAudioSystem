package com.spatialaudiosystem.audio;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audible region (SAS-AUDIO-007).
 *
 * <p>The client sets its gain from this and the server decides from it whether a player who has
 * just arrived should be sent the audio at all. A disagreement between those two is silent in
 * both directions — megabytes shipped to someone who hears nothing, or a player standing inside
 * an audible box with no sound — so what is pinned here is the branching, not a happy path.
 *
 * <p>Sample points sit where the rule <em>decides</em>: on each side of the box, on each side of
 * a face's range, and exactly on the boundary. A test that only probed the middle of the box
 * would stay green through almost any change to the falloff.
 *
 * <p><b>Mutation controls</b> — each of these edits to {@link SpatialGain} must turn this class
 * red, and each was run:
 * <table>
 *   <tr><td>{@code !attenuationMode} branch returns 1.0f instead of 0.0f</td>
 *       <td>{@code attenuationOffMakesTheBoxAHardEdge}</td></tr>
 *   <tr><td>{@code axisFactor} drops the {@code negRange <= 0} guard</td>
 *       <td>{@code aFaceWithNoRangeIsSilentOnThatSide}</td></tr>
 *   <tr><td>{@code axisFactor} uses {@code >=} so the boundary stays audible</td>
 *       <td>{@code theFarEdgeOfAFaceRangeIsSilent}</td></tr>
 *   <tr><td>the no-board branch ignores {@code ranges[0]} and always uses 160</td>
 *       <td>{@code withoutABoardAttenuationUsesTheDeviceRange}</td></tr>
 *   <tr><td>{@code audible} returns {@code >= 0} instead of {@code > 0}</td>
 *       <td>{@code audibleAgreesWithTheGainItIsDefinedFrom}</td></tr>
 * </table>
 */
class SpatialGainTest {

    private static final BlockPos DEVICE = new BlockPos(0, 64, 0);
    /** A one-block range box at the origin: boxOf gives (0,0,0)..(1,1,1). */
    private static final BlockPos CORNER_A = new BlockPos(0, 64, 0);
    private static final BlockPos CORNER_B = new BlockPos(0, 64, 0);

    /** Per-face distances [E, W, U, D, S, N]. */
    private static int[] ranges(int all) {
        int[] r = new int[6];
        java.util.Arrays.fill(r, all);
        return r;
    }

    private static float gainWithBox(double x, boolean attenuationMode, int[] ranges) {
        return SpatialGain.linearGain(x, 64.5, 0.5, DEVICE, CORNER_A, CORNER_B, attenuationMode, ranges);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: inside the range box the sound is at full volume")
    void insideTheBoxIsFullVolume() {
        assertThat(gainWithBox(0.5, true, ranges(8))).isEqualTo(1.0f);
        // Attenuation only describes what happens outside, so it cannot change this.
        assertThat(gainWithBox(0.5, false, ranges(8))).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: with attenuation off the box edge is a hard edge")
    void attenuationOffMakesTheBoxAHardEdge() {
        assertThat(gainWithBox(0.5, false, ranges(8)))
                .as("just inside")
                .isEqualTo(1.0f);
        assertThat(gainWithBox(1.5, false, ranges(8)))
                .as("one block outside is silent, not merely quieter")
                .isEqualTo(0.0f);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: with attenuation on the volume falls off across the face range")
    void attenuationOnFadesAcrossTheFaceRange() {
        // Box spans x in [0,1). At x = 5.0 the listener is 4 blocks past the +X face.
        float halfway = gainWithBox(5.0, true, ranges(8));
        assertThat(halfway).isGreaterThan(0.0f).isLessThan(1.0f);

        // Further out is quieter. Pinned as an ordering so the shape of the curve is free but
        // its direction is not.
        assertThat(gainWithBox(7.0, true, ranges(8))).isLessThan(halfway);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: the far edge of a face range is silent")
    void theFarEdgeOfAFaceRangeIsSilent() {
        // Box max x is 1.0, range 8, so x = 9.0 is exactly one range away: the fade reaches zero.
        assertThat(gainWithBox(9.0, true, ranges(8)))
                .as("at exactly the face range the fade has reached zero")
                .isEqualTo(0.0f);
        assertThat(gainWithBox(8.99, true, ranges(8)))
                .as("just inside it is still audible")
                .isGreaterThan(0.0f);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: a face configured with no range is silent on that side")
    void aFaceWithNoRangeIsSilentOnThatSide() {
        int[] eastOnly = new int[]{8, 0, 8, 8, 8, 8};   // [E, W, U, D, S, N]

        assertThat(gainWithBox(5.0, true, eastOnly))
                .as("east has range, so this side still carries")
                .isGreaterThan(0.0f);
        assertThat(gainWithBox(-4.0, true, eastOnly))
                .as("west is zero, so nothing carries that way at any distance")
                .isEqualTo(0.0f);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: without a range board attenuation fades over the device's own range")
    void withoutABoardAttenuationUsesTheDeviceRange() {
        // No box: distance is measured from the device's centre, and the server fills the array
        // with the device's configured range.
        float near = SpatialGain.linearGain(4.5, 64.5, 0.5, DEVICE, null, null, true, ranges(8));
        assertThat(near).isGreaterThan(0.0f).isLessThan(1.0f);

        assertThat(SpatialGain.linearGain(8.5, 64.5, 0.5, DEVICE, null, null, true, ranges(8)))
                .as("at the configured range the fade has reached zero")
                .isEqualTo(0.0f);

        assertThat(SpatialGain.linearGain(20.5, 64.5, 0.5, DEVICE, null, null, true, ranges(8)))
                .as("well past it stays zero rather than falling through to the 160-block curve")
                .isEqualTo(0.0f);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: without a board and with attenuation off, the gentle 160-block falloff applies")
    void withoutABoardAttenuationOffUsesTheAmbientFalloff() {
        assertThat(SpatialGain.linearGain(20.5, 64.5, 0.5, DEVICE, null, null, false, ranges(8)))
                .as("audible far past the 8-block device range, which this mode ignores")
                .isGreaterThan(0.0f);

        assertThat(SpatialGain.linearGain(
                        SpatialGain.AMBIENT_FALLOFF_BLOCKS + 0.5, 64.5, 0.5,
                        DEVICE, null, null, false, ranges(8)))
                .as("at the falloff distance it has reached zero")
                .isEqualTo(0.0f);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: a face range of zero everywhere is silent outside the box")
    void zeroRangesEverywhereAreSilentOutsideTheBox() {
        assertThat(gainWithBox(0.5, true, ranges(0))).as("inside is unaffected").isEqualTo(1.0f);
        assertThat(gainWithBox(1.5, true, ranges(0))).isEqualTo(0.0f);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: a malformed ranges array is silent rather than throwing")
    void aMalformedRangesArrayIsSilent() {
        assertThat(gainWithBox(5.0, true, null)).isEqualTo(0.0f);
        assertThat(gainWithBox(5.0, true, new int[]{8, 8})).isEqualTo(0.0f);
        assertThat(SpatialGain.linearGain(1.0, 1.0, 1.0, null, null, null, true, ranges(8)))
                .as("no device and no box: nothing to be near")
                .isEqualTo(0.0f);
    }

    @Test
    @DisplayName("SAS-AUDIO-007: audible() is exactly a positive gain, at every branch")
    void audibleAgreesWithTheGainItIsDefinedFrom() {
        // Asked at each branch rather than at one point, because the two could only disagree on
        // a boundary. Comparing against linearGain rather than against a copied threshold is the
        // point: a transcribed predicate would stay green when linearGain changed.
        double[][] probes = {
                {0.5, 1}, {1.5, 1}, {5.0, 1}, {9.0, 1}, {8.99, 1},
        };
        for (double[] probe : probes) {
            double x = probe[0];
            for (boolean atten : new boolean[]{true, false}) {
                float gain = gainWithBox(x, atten, ranges(8));
                assertThat(SpatialGain.audible(x, 64.5, 0.5, DEVICE, CORNER_A, CORNER_B, atten, ranges(8)))
                        .as("x=%s attenuation=%s gain=%s", x, atten, gain)
                        .isEqualTo(gain > 0.0f);
            }
        }
    }

    @Test
    @DisplayName("SAS-AUDIO-007: the client's gain has no second copy of this arithmetic")
    void theClientDoesNotKeepItsOwnCopyOfTheFalloff() throws Exception {
        // The whole reason SpatialGain exists is that the client gain and the server's range
        // check must be one implementation. Re-inlining the maths into AudioManager would leave
        // every test above green while the two drifted apart, so this looks at the structure.
        //
        // What it can and cannot say: it reads one file for a fixed set of markers, so it catches
        // the copy coming back into AudioManager and not a copy grown in some third class. The
        // gain is only reachable through a private method on a live playback, so there is no seam
        // to compare the two numerically; this is a smoke check on the arrangement, not proof
        // that one implementation is in use.
        Path audioManager = locate("src/main/java")
                .resolve("com/spatialaudiosystem/audio/AudioManager.java");
        String src = Files.readString(audioManager, StandardCharsets.UTF_8);

        assertThat(src)
                .as("the client must ask SpatialGain rather than compute a gain of its own")
                .contains("SpatialGain.linearGain");
        assertThat(src)
                .as("an axis-factor helper back in AudioManager means the copy has returned")
                .doesNotContain("computeAxisFactor");
        assertThat(src)
                .as("the ambient falloff distance belongs to SpatialGain now")
                .doesNotContain("160.0f");
        // A re-inlined distance falloff needs the listener's distance, and that needs a square
        // root. Named separately from the identifier above because a copy brought back under
        // some other name would slip past a check that only knows the old one.
        assertThat(src)
                .as("distance from the listener is computed in SpatialGain, not here")
                .doesNotContain("Math.sqrt");
    }

    /** Resolve a repo-relative path by walking up; gradle's test dir is not the project dir. */
    private static Path locate(String rel) {
        for (Path base = Paths.get("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path candidate = base.resolve(rel);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new AssertionError("not found: " + rel + " (from " + Paths.get("").toAbsolutePath() + ")");
    }
}
