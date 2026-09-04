package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.audio.SpatialGain;
import com.spatialaudiosystem.network.SetAttenuationRangePayload;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SAS-RANGE-001: the playback range a device has without a range board.
 *
 * <p>Since 2026-09-02 it is a preset the player turns with the wheel, one to sixty-four blocks,
 * a new device starting at the jukebox's sixty-four. Every way the value gets in -- the wire,
 * the screen, the disk -- goes through one clamp, so this checks that clamp and the decode-time
 * refusal in front of it. The gain rule the value feeds is SpatialGainTest's.
 */
class RangePresetTest {

    private static final BlockPos POS = new BlockPos(3, 70, 12);

    /** A device with no world behind it; the range setter touches nothing but its own field. */
    private static PlaybackDeviceBlockEntity device() {
        return com.spatialaudiosystem.blockentity.TestDevices.newBare();
    }

    @Test
    @DisplayName("SAS-RANGE-001: the range is clamped to one..sixty-four blocks")
    void theRangeIsClampedToOneToSixtyFour() {
        PlaybackDeviceBlockEntity d = device();
        d.setAttenuationRange(0);
        // Zero was the old floor and meant silence; a range of nothing is not a range.
        assertThat(d.getAttenuationRange()).as("below the floor").isEqualTo(SpatialGain.MIN_RANGE_BLOCKS);
        d.setAttenuationRange(99);
        assertThat(d.getAttenuationRange()).as("above the ceiling").isEqualTo(SpatialGain.MAX_RANGE_BLOCKS);
        d.setAttenuationRange(24);
        assertThat(d.getAttenuationRange()).as("inside the bounds it is taken as is").isEqualTo(24);
        assertThat(SpatialGain.MIN_RANGE_BLOCKS).isEqualTo(1);
        assertThat(SpatialGain.MAX_RANGE_BLOCKS).isEqualTo(64);
    }

    @Test
    @DisplayName("SAS-RANGE-001: a new device starts at the jukebox's range")
    void aNewDeviceStartsAtTheJukeboxRange() throws Exception {
        // The jukebox is heard from sixty-four blocks; a device with no board should sound like
        // the block players already know. A placed device runs the field initialiser, which no
        // unit test can (Objenesis skips it), so the initialiser is read: the first version of
        // this test asserted only the constants and stayed green with the field back at 8.
        assertThat(SpatialGain.JUKEBOX_RANGE_BLOCKS).isEqualTo(64);
        assertThat(SpatialGain.DEFAULT_RANGE_BLOCKS)
                .as("the gain's fallback mirrors the device default")
                .isEqualTo(SpatialGain.JUKEBOX_RANGE_BLOCKS);
        assertThat(sourceText("src/main/java/com/spatialaudiosystem/blockentity/PlaybackDeviceBlockEntity.java"))
                .contains("private int attenuationRange = SpatialGain.JUKEBOX_RANGE_BLOCKS;");
    }

    /** The named source file, found by walking up from wherever the test runner started. */
    private static String sourceText(String relative) throws Exception {
        for (java.nio.file.Path base = java.nio.file.Paths.get("").toAbsolutePath();
             base != null; base = base.getParent()) {
            java.nio.file.Path c = base.resolve(relative);
            if (java.nio.file.Files.isRegularFile(c)) {
                return java.nio.file.Files.readString(c, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found from " + java.nio.file.Paths.get("").toAbsolutePath());
    }

    @Test
    @DisplayName("SAS-RANGE-001: the preset is in effect only with no box and attenuation on")
    void thePresetIsInEffectOnlyWithoutABoxAndWithAttenuationOn() {
        // The screen colours the range row from this. With a box the board's faces apply; with
        // attenuation off SpatialGain never reads the range. Claiming the value is live in
        // either case is the defect the second reading found for the attenuation-off case.
        assertThat(PlaybackDeviceBlockEntity.presetInEffect(false, true)).isTrue();
        assertThat(PlaybackDeviceBlockEntity.presetInEffect(true, true)).as("a box").isFalse();
        assertThat(PlaybackDeviceBlockEntity.presetInEffect(false, false)).as("attenuation off").isFalse();
        assertThat(PlaybackDeviceBlockEntity.presetInEffect(true, false)).isFalse();
    }

    @Test
    @DisplayName("SAS-RANGE-001: the screen colours the row from the predicate, arguments in order")
    void theScreenColoursTheRowFromThePredicate() throws Exception {
        // The screen cannot be constructed here, so its one line is read. A swapped argument
        // order or a negation at the call site would leave the predicate's own tests green.
        String screen = sourceText("src/main/java/com/spatialaudiosystem/screen/PlaybackDeviceScreenV2.java");
        assertThat(screen).contains(
                "PlaybackDeviceBlockEntity.presetInEffect(rangeBoardInserted(), be().isAttenuationMode())");
        assertThat(screen).contains("? COLOR_RANGE_VALUE : COLOR_RANGE_INACTIVE;");
        String layout = sourceText("src/main/resources/assets/spatialaudiosystem/layouts/playback-device.json");
        assertThat(layout).contains("\"colorKey\":\"pb-atten-range-color\"");
        assertThat(layout).contains("\"wheelKey\":\"pb-range-wheel\"");
    }

    private static SetAttenuationRangePayload decode(int range) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(POS);
        buf.writeInt(range);
        return SetAttenuationRangePayload.STREAM_CODEC.decode(buf);
    }

    @Test
    @DisplayName("SAS-RANGE-001: a range outside the bounds is refused at decode, not clamped")
    void aRangeOutsideTheBoundsIsRefusedAtDecode() {
        assertThat(decode(1).range()).isEqualTo(1);
        assertThat(decode(64).range()).isEqualTo(64);
        // The server would clamp these too, but a refused packet is a refused packet and a
        // clamped one is a silent correction of a peer that named a value it may not.
        assertThatThrownBy(() -> decode(0)).isInstanceOf(DecoderException.class);
        assertThatThrownBy(() -> decode(65)).isInstanceOf(DecoderException.class);
    }

    @Test
    @DisplayName("SAS-RANGE-001: the payload round-trips inside the bounds")
    void thePayloadRoundTrips() {
        SetAttenuationRangePayload sent = new SetAttenuationRangePayload(POS, 37);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SetAttenuationRangePayload.STREAM_CODEC.encode(buf, sent);
        assertThat(SetAttenuationRangePayload.STREAM_CODEC.decode(buf)).isEqualTo(sent);
    }
}
