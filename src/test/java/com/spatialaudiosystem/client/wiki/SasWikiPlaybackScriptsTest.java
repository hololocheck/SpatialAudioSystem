package com.spatialaudiosystem.client.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manta.api.wiki.WikiLiveScreens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The wiki's playback scripts (Manta 7 Phase 7 slice 2e, MANTA_7_PHASE7_PLAYBACK v11 section 9) pass Manta's own check,
 * {@link WikiLiveScreens#checkPlayback} - never a copy of its rules here: the table is registered as the client
 * registers it ({@link SasWikiLiveCapture#registerLive()}), then every {@code wiki/playback/*.json} is asked.
 *
 * <p>{@link #NOT_PRESSED} is this mod's list of parts no script may click or wheel - what the playback containment cannot
 * see. Read 2026-09-30 (RecordingDeviceScreenV2): {@code rec-file-btn} opens a native file dialog (AudioFilePickerService →
 * TinyFileDialogs, on its own thread); it, {@code rec-start-btn} and {@code rec-clear-btn} clear the process-wide
 * RecordingErrorState before their (contained) send. Since 2026-10-07 (改善1: every screen operated in the wiki) the wiki's
 * stand-ins take those presses themselves - the memory device's ({@code RecordingDeviceScreenV2.wikiMode}) picks a demo
 * file and writes on its dummy entity without either, the playback device's applies its actions to its dummy entity, and
 * the sound handy's ({@code SoundHandyScreen.wikiCreate}) has a handy, devices and a layout flag of its own instead of the
 * held handy, the client's device list and SoundHandyLayoutState - so the list is empty. The memory device's buttons do
 * not rest on its wiki mode alone: the picker opens and the refusal clears only for the screen the player has open
 * (AudioFilePickerService.pickAndUpload, RecordingErrorState.clear - AudioFilePickerServiceTest, RecordingErrorStateTest),
 * which a stand-in drawn inside the wiki never is. The other parts send to the server through Mirror - contained.
 *
 * <p>Not seen here: whether each step finds its part on the real screen and state - that is the real client's run of each
 * script ({@code /manta debug live}: played, delivered n, no stop).
 */
class SasWikiPlaybackScriptsTest {

    private static final String ASSETS = "src/main/resources/assets/spatialaudiosystem";

    static final Set<String> NOT_PRESSED = Set.of();

    /** The assets, found upwards from the working directory (the unit tests run in build/minecraft-junit). */
    private static Path assets() {
        for (Path base = Paths.get("").toAbsolutePath(); base != null; base = base.getParent()) {
            if (Files.isDirectory(base.resolve(ASSETS))) return base.resolve(ASSETS);
        }
        return Paths.get(ASSETS);
    }

    @Test
    void everyPlaybackScriptPassesMantasCheck() {
        SasWikiLiveCapture.registerLive();
        Path assets = assets();
        WikiLiveScreens.PlaybackCheck check = WikiLiveScreens.checkPlayback(assets, "spatialaudiosystem", NOT_PRESSED);
        assertTrue(check.scripts() > 0, "no playback script read under " + assets.toAbsolutePath() + " - nothing was judged");
        assertEquals(List.of(), check.problems(), check.scripts() + " script(s) judged");
    }
}
