package com.spatialaudiosystem.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.manta.api.data.Schema;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.handy.HandyActions;
import com.spatialaudiosystem.handy.HandyRangeEdit;
import com.spatialaudiosystem.handy.SoundDeviceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAS-HANDY-003 / -005 on manta:data (MANTA_7_CONCEPT C4): the handy's and the range board's input is the player
 * host's actions (manta/tools-state.json), and every bound the payloads enforced at decode (BELUGAEXPERIENCE R3.6.1)
 * is the schema's now - asked of Manta's own codec ({@link Schema#accepts}: encoded as a mirror sends, decoded as
 * the host decodes), and pinned here against the Java constants the handlers are written against.
 */
class HandyStateTest {

    private static final Schema TOOLS = HandyData.schema();
    private static final String DIM = "minecraft:overworld";

    private static boolean action(int action, int arg) {
        return TOOLS.accepts("handy-action", action, arg, false, DIM, 0, 0, 0);
    }

    private static boolean rangeEdit(int op, int face, int value) {
        return TOOLS.accepts("handy-range-edit", op, face, value, false, 0, 0, 0);
    }

    @Test
    @DisplayName("SAS-HANDY-003: an action or argument outside its bound is refused by the codec, not by the handler")
    void actionBounds() {
        assertThat(action(HandyActions.MAX_ACTION, -HandyActions.MAX_ARG)).as("the bounds themselves pass").isTrue();
        assertThat(action(0, HandyActions.MAX_ARG)).isTrue();
        assertThat(action(HandyActions.MAX_ACTION + 1, 0)).isFalse();
        assertThat(action(-1, 0)).isFalse();
        assertThat(action(0, HandyActions.MAX_ARG + 1)).isFalse();
        assertThat(action(0, -HandyActions.MAX_ARG - 1)).isFalse();
        assertThat(TOOLS.accepts("handy-action", HandyActions.PLAY, 0, true, DIM, 30_000_000, 319, -30_000_000))
                .as("a device anywhere in the world border").isTrue();
    }

    @Test
    @DisplayName("SAS-HANDY-005: op, face and step outside their bounds are refused by the codec")
    void rangeEditBounds() {
        assertThat(rangeEdit(HandyRangeEdit.STEP_FACE, HandyRangeEdit.FACES - 1, HandyRangeEdit.MAX_STEP)).isTrue();
        assertThat(rangeEdit(HandyRangeEdit.MAX_OP + 1, 0, 0)).isFalse();
        assertThat(rangeEdit(HandyRangeEdit.STEP_FACE, HandyRangeEdit.FACES, 1)).isFalse();
        assertThat(rangeEdit(HandyRangeEdit.STEP_FACE, 0, HandyRangeEdit.MAX_STEP + 1)).isFalse();
        assertThat(rangeEdit(HandyRangeEdit.STEP_FACE, 0, -HandyRangeEdit.MAX_STEP - 1)).isFalse();
    }

    @Test
    @DisplayName("SAS-HANDY-005: a face steps by one and stays within 0..15 whatever the step says")
    void faceStepsByOneWithinTheBoard() {
        assertThat(HandyRangeEdit.steppedFace(8, 1)).isEqualTo(9);
        assertThat(HandyRangeEdit.steppedFace(8, -1)).isEqualTo(7);
        assertThat(HandyRangeEdit.steppedFace(8, 1000)).as("a big step is still one").isEqualTo(9);
        assertThat(HandyRangeEdit.steppedFace(15, 1)).isEqualTo(15);
        assertThat(HandyRangeEdit.steppedFace(0, -1)).isEqualTo(0);
    }

    /**
     * The widest name the inputs can hold as the codec measures it: 32 code points (the inputs' cap) of U+1D160,
     * which NFC - applied before the byte count - expands to three four-byte code points, 12 bytes each. A plain
     * four-byte character (128 bytes for 32) would pass any maxLen from 128 up (second reading 13).
     */
    static final String WIDEST_NAME = "𝅘𝅥𝅮".repeat(SoundDeviceRegistry.MAX_NAME_CODE_POINTS);

    @Test
    @DisplayName("SAS-HANDY-003: any name the inputs can hold is accepted; the codec refuses past the declaration")
    void nameOnTheWire() {
        assertThat(java.text.Normalizer.normalize(WIDEST_NAME, java.text.Normalizer.Form.NFC)
                .getBytes(StandardCharsets.UTF_8)).as("the widest name, as the codec measures it").hasSize(384);
        assertThat(TOOLS.accepts("set-device-name", DIM, 1, 64, -3, WIDEST_NAME)).isTrue();
        assertThat(TOOLS.accepts("set-device-name", DIM, 1, 64, -3, WIDEST_NAME + "x")).isFalse();
        assertThat(PlaybackDeviceData.schema().accepts("rename", WIDEST_NAME)).as("the device screen's rename").isTrue();
        assertThat(PlaybackDeviceData.schema().accepts("rename", WIDEST_NAME + "x")).isFalse();
        assertThat(TOOLS.accepts("range-board-data", "MAIN_HAND", 0, 15, 8, 8, 8, 8)).isTrue();
        assertThat(TOOLS.accepts("range-board-data", "MAIN_HAND", 0, 16, 8, 8, 8, 8)).as("the client clamps first")
                .isFalse();
    }

    @Test
    @DisplayName("SAS-HANDY-003: the device list holds the registry's whole per-owner cap")
    void listCapIsTheRegistryCap() throws Exception {
        JsonObject doc;
        try (InputStream in = SpatialAudioSystem.class.getResourceAsStream(HandyData.DOCUMENT)) {
            doc = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        int maxLength = doc.getAsJsonObject("state").getAsJsonObject("fields").getAsJsonObject("handy-devices")
                .get("maxLength").getAsInt();
        assertThat(maxLength).isEqualTo(SoundDeviceRegistry.MAX_DEVICES_PER_OWNER);
    }

    @Test
    @DisplayName("SAS-HANDY-003: a string a player controls is cut to its declaration at a code point, never refused")
    void playerStringsFit() {
        assertThat(HandyData.fit("x".repeat(500), HandyData.FILE_BYTES)).hasSize(HandyData.FILE_BYTES);
        String cjk = "教会の見える駅".repeat(40);   // 3 bytes per char
        String cut = HandyData.fit(cjk, HandyData.NAME_BYTES);
        assertThat(cut.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(HandyData.NAME_BYTES);
        assertThat(cjk).startsWith(cut);
        assertThat(HandyData.fit("é", 8)).as("NFC, as the codec carries it").isEqualTo("é");
        String music = "🎵".repeat(40);
        assertThat(HandyData.fit(music, 6)).as("never half a surrogate pair").isEqualTo("🎵");
    }
}
