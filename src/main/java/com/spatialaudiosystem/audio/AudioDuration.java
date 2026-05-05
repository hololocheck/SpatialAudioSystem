package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.SpatialAudioSystem;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Audio bytes から durationを秒単位で算出する utility。
 *
 * <p>WAV: RIFF header を直接パースして正確な値を返す。
 * <p>OGG/MP3: AudioSystem.getAudioFileFormat() のメタデータ → frameLength / sampleRate を試行。
 * 失敗時はバイト長 ÷ 平均ビットレートでの近似値。
 */
public final class AudioDuration {

    private AudioDuration() {}

    /** @return 秒数 (>= 0)。算出不能なら 0。 */
    public static int compute(byte[] audioData, String format) {
        if (audioData == null || audioData.length == 0) return 0;
        if (format == null) format = "ogg";

        try {
            switch (format.toLowerCase()) {
                case "wav":
                    return computeWav(audioData);
                case "mp3":
                    int mp3 = tryAudioSystem(audioData);
                    if (mp3 > 0) return mp3;
                    // 128kbps fallback (16,000 bytes/sec)
                    return Math.max(1, audioData.length / 16_000);
                case "ogg":
                    int ogg = tryAudioSystem(audioData);
                    if (ogg > 0) return ogg;
                    // 128kbps fallback
                    return Math.max(1, audioData.length / 16_000);
                default:
                    return 0;
            }
        } catch (Throwable t) {
            SpatialAudioSystem.LOGGER.debug("[AudioDuration] compute failed for format={}: {}",
                    format, t.toString());
            return 0;
        }
    }

    /**
     * WAV (RIFF) header を直接パース:
     *   "RIFF" (4) + size (4) + "WAVE" (4) + chunks...
     *   "fmt " chunk: sampleRate (offset 12), channels (offset 10), bitsPerSample (offset 22)
     *   "data" chunk: payload size
     *   duration = dataSize / (sampleRate * channels * bitsPerSample/8)
     */
    private static int computeWav(byte[] data) {
        if (data.length < 44) return 0;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // "RIFF" check
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') return 0;
        // "WAVE"
        if (data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') return 0;
        // walk chunks
        int pos = 12;
        int sampleRate = 0, channels = 0, bitsPerSample = 0;
        long dataSize = -1;
        while (pos + 8 <= data.length) {
            String id = new String(data, pos, 4);
            int chunkSize = buf.getInt(pos + 4);
            if ("fmt ".equals(id) && pos + 24 <= data.length) {
                channels = buf.getShort(pos + 10) & 0xFFFF;
                sampleRate = buf.getInt(pos + 12);
                bitsPerSample = buf.getShort(pos + 22) & 0xFFFF;
            } else if ("data".equals(id)) {
                dataSize = chunkSize & 0xFFFFFFFFL;
                break;
            }
            pos += 8 + chunkSize;
            if (chunkSize < 0) break; // overflow guard
        }
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0 || dataSize <= 0) return 0;
        long bytesPerSec = (long) sampleRate * channels * (bitsPerSample / 8);
        if (bytesPerSec <= 0) return 0;
        return (int) Math.max(1, dataSize / bytesPerSec);
    }

    /** Java Sound API 経由で frame length と sample rate を取得 → 秒数。 */
    private static int tryAudioSystem(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            AudioFileFormat fmt = AudioSystem.getAudioFileFormat(bais);
            AudioFormat audio = fmt.getFormat();
            long frames = fmt.getFrameLength();
            float sr = audio.getSampleRate();
            if (frames > 0 && sr > 0) {
                return Math.max(1, (int) (frames / sr));
            }
        } catch (Throwable ignored) {}
        // 2nd attempt: open as AudioInputStream and count
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             AudioInputStream ais = AudioSystem.getAudioInputStream(bais)) {
            AudioFormat audio = ais.getFormat();
            long frames = ais.getFrameLength();
            float sr = audio.getSampleRate();
            if (frames > 0 && sr > 0) {
                return Math.max(1, (int) (frames / sr));
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
