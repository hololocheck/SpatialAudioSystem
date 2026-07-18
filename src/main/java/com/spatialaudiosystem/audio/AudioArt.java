package com.spatialaudiosystem.audio;

import org.jetbrains.annotations.Nullable;

/**
 * Extracts embedded cover art ("jacket") from audio bytes.
 *
 * <p>Only MP3 ID3v2 (2.3 / 2.4) APIC frames are read — the common case for tagged music.
 * OGG/WAV and untagged files return {@code null}, and the UI shows a music-note placeholder.
 * The returned bytes are the raw image (usually JPEG or PNG), decoded on the client.
 */
public final class AudioArt {

    /** Cover art larger than this is ignored: a jacket is a thumbnail, and this keeps the
     *  server→client transfer to a single safe packet. */
    public static final int MAX_ART_BYTES = 256 * 1024;

    private AudioArt() {}

    @Nullable
    public static byte[] extract(byte[] audio, String format) {
        if (audio == null || format == null) return null;
        if (!"mp3".equalsIgnoreCase(format)) return null;
        return extractId3Apic(audio);
    }

    /** Reads the first APIC (attached picture) frame from an ID3v2 tag, or null. */
    @Nullable
    private static byte[] extractId3Apic(byte[] d) {
        // Header: "ID3" v v flags size(4, synchsafe)
        if (d.length < 10 || d[0] != 'I' || d[1] != 'D' || d[2] != '3') return null;
        int major = d[3] & 0xFF;
        boolean v24 = major >= 4;
        int tagSize = synchsafe(d, 6);
        int end = Math.min(d.length, 10 + tagSize);

        int p = 10;
        while (p + 10 <= end) {
            String id = new String(d, p, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int size = v24 ? synchsafe(d, p + 4) : beInt(d, p + 4);
            // frame flags at p+8..p+9
            int body = p + 10;
            if (size <= 0 || body + size > end) break;      // padding or corruption
            if ("APIC".equals(id)) {
                byte[] art = parseApic(d, body, size);
                if (art != null && art.length <= MAX_ART_BYTES) return art;
            }
            p = body + size;
        }
        return null;
    }

    /** APIC body: encoding(1) mime(0-terminated) picType(1) desc(term) imageData. */
    @Nullable
    private static byte[] parseApic(byte[] d, int off, int size) {
        int end = off + size;
        int i = off;
        int encoding = d[i++] & 0xFF;
        // MIME type is always ISO-8859-1, single-null terminated.
        while (i < end && d[i] != 0) i++;
        i++;                                // skip the MIME null
        if (i >= end) return null;
        i++;                                // skip picture type byte
        // Description terminator: 0x00 (enc 0/3) or 0x0000 on a 2-byte boundary (enc 1/2).
        if (encoding == 1 || encoding == 2) {
            while (i + 1 < end && !(d[i] == 0 && d[i + 1] == 0)) i += 2;
            i += 2;
        } else {
            while (i < end && d[i] != 0) i++;
            i++;
        }
        if (i >= end) return null;
        byte[] art = new byte[end - i];
        System.arraycopy(d, i, art, 0, art.length);
        return art;
    }

    /** 28-bit synchsafe integer (7 bits per byte) at offset. */
    private static int synchsafe(byte[] d, int o) {
        return ((d[o] & 0x7F) << 21) | ((d[o + 1] & 0x7F) << 14)
                | ((d[o + 2] & 0x7F) << 7) | (d[o + 3] & 0x7F);
    }

    /** 32-bit big-endian integer at offset. */
    private static int beInt(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16)
                | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }
}
