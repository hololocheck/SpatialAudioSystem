package com.spatialaudiosystem.server;

import com.spatialaudiosystem.audio.AudioDuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tone the verification pass loops (SAS-AUDIO-012).
 *
 * <p>It is written byte by byte as a RIFF header plus PCM, which is the kind of thing that is
 * either exactly right or silently wrong. A malformed header does not throw anywhere the pass can
 * see: {@code streamWav} would fail on its own thread, no loop restart would be logged, and the
 * driver would report "it never looped" — sending someone to look for a bug in the loop that is
 * really a bug in the fixture.
 */
class SasVerifyToneTest {

    @Test
    @DisplayName("SAS-AUDIO-012: the generated tone is a WAV the audio system can open")
    void theToneIsADecodableWav() throws Exception {
        byte[] tone = SasVerifyPass.oneSecondTone();

        // The same call streamWav makes. If the header is wrong this throws
        // UnsupportedAudioFileException, which is the failure the pass could not otherwise see.
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(tone))) {
            AudioFormat format = stream.getFormat();
            assertThat(format.getSampleRate()).isEqualTo(44100f);
            assertThat(format.getSampleSizeInBits()).isEqualTo(16);
            assertThat(format.getChannels()).as("mono").isEqualTo(1);
            assertThat(format.getEncoding()).isEqualTo(AudioFormat.Encoding.PCM_SIGNED);
        }
    }

    @Test
    @DisplayName("SAS-AUDIO-012: the tone is one second long, so the loop comes round quickly")
    void theToneIsOneSecondLong() {
        // The pass waits five seconds per phase and expects three passes in that window. A tone
        // that came out ten times too long would leave the driver reporting "it never looped".
        assertThat(AudioDuration.compute(SasVerifyPass.oneSecondTone(), "wav")).isEqualTo(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-012: the tone carries actual sound, not silence")
    void theToneIsNotSilence() {
        byte[] tone = SasVerifyPass.oneSecondTone();
        // A header-only or all-zero buffer decodes fine and loops fine, and verifies nothing a
        // person could hear. Read the PCM past the 44-byte header and require some amplitude.
        int loudest = 0;
        for (int i = 44; i + 1 < tone.length; i += 2) {
            int sample = (short) ((tone[i] & 0xFF) | (tone[i + 1] << 8));
            loudest = Math.max(loudest, Math.abs(sample));
        }
        assertThat(loudest).as("peak amplitude").isGreaterThan(1000);
    }
}
