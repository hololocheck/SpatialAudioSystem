package com.spatialaudiosystem.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AudioArt}, the ID3v2 APIC (cover art) extractor. The tags here are built to
 * the ID3v2.3 spec independently of the parser, so a matching bug in both would still be caught.
 */
class AudioArtTest {

    private static final byte[] IMAGE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};

    @Test
    @DisplayName("an ID3v2.3 APIC frame's image bytes are extracted from an mp3")
    void extractsApicFromId3v23() {
        assertThat(AudioArt.extract(id3(apic("image/jpeg", IMAGE)), "mp3")).isEqualTo(IMAGE);
    }

    @Test
    @DisplayName("non-mp3 formats are not parsed for art")
    void ignoresNonMp3() {
        byte[] tag = id3(apic("image/jpeg", IMAGE));
        assertThat(AudioArt.extract(tag, "ogg")).isNull();
        assertThat(AudioArt.extract(tag, "wav")).isNull();
    }

    @Test
    @DisplayName("an mp3 without an ID3 tag yields no art")
    void noId3NoArt() {
        assertThat(AudioArt.extract(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}, "mp3")).isNull();
    }

    @Test
    @DisplayName("an ID3 tag whose only frame is not a picture yields no art")
    void noApicNoArt() {
        assertThat(AudioArt.extract(id3(frame("TIT2", new byte[]{0x00, 'H', 'i'})), "mp3")).isNull();
    }

    // ---- ID3v2.3 builders ----

    /** APIC body: encoding(1) + mime(0-term) + picType(1) + empty desc(0-term) + image. */
    private static byte[] apic(String mime, byte[] image) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x00);                                        // ISO-8859-1
        body.writeBytes(mime.getBytes(StandardCharsets.ISO_8859_1));
        body.write(0x00);                                        // MIME terminator
        body.write(0x03);                                        // picture type: cover (front)
        body.write(0x00);                                        // empty description terminator
        body.writeBytes(image);
        return frame("APIC", body.toByteArray());
    }

    /** ID3v2.3 frame: id(4) + size(4 big-endian) + flags(2) + body. */
    private static byte[] frame(String id, byte[] body) {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        f.writeBytes(beInt(body.length));
        f.write(0x00); f.write(0x00);
        f.writeBytes(body);
        return f.toByteArray();
    }

    /** ID3v2.3 tag: "ID3" + 3,0,0 + synchsafe size + frames. */
    private static byte[] id3(byte[] frames) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.writeBytes("ID3".getBytes(StandardCharsets.ISO_8859_1));
        t.write(0x03); t.write(0x00); t.write(0x00);
        t.writeBytes(synchsafe(frames.length));
        t.writeBytes(frames);
        return t.toByteArray();
    }

    private static byte[] beInt(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] synchsafe(int v) {
        return new byte[]{(byte) ((v >>> 21) & 0x7F), (byte) ((v >>> 14) & 0x7F),
                (byte) ((v >>> 7) & 0x7F), (byte) (v & 0x7F)};
    }
}
