package com.spatialaudiosystem.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The playback device's schedule popup issues a click on its own slots at the press and swallows the matching release.
 * A press there never starts vanilla's quick-craft, so a running one began outside the popup - and swallowing its end
 * left the distribution open until the next release (TSU's identical swallow, measured on the real client 2026-10-02:
 * a drag ending on a popup slot distributed nothing and kept the whole stack in hand). The swallow now stands aside
 * while a quick-craft runs; Manta's base hands that release to vanilla, which ends the distribution.
 */
class PopupSlotReleaseTest {

    private static String source(String relative) throws IOException {
        for (Path base = Paths.get("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path p = base.resolve(relative);
            if (Files.exists(p)) return Files.readString(p, StandardCharsets.UTF_8);
        }
        throw new AssertionError("not found: " + relative);
    }

    @Test
    @DisplayName("the schedule popup swallows a release over its slots only while no quick-craft runs")
    void theSwallowStandsAsideForARunningQuickCraft() throws IOException {
        String src = source("src/main/java/com/spatialaudiosystem/screen/PlaybackDeviceScreenV2.java");
        int at = src.indexOf("public boolean mouseReleased(double mouseX, double mouseY, int button) {");
        assertTrue(at >= 0, "PlaybackDeviceScreenV2.mouseReleased exists");
        String body = src.substring(at, src.indexOf("\n    }", at));
        int swallow = body.indexOf("return true;");
        assertTrue(swallow > 0, "the swallow exists");
        String guard = body.substring(0, swallow);
        assertTrue(guard.contains("hoveredPlaylistSlot(mouseX, mouseY) != null"), "it is the popup slots' swallow");
        assertTrue(guard.contains("!this.isQuickCrafting"), "and it stands aside while a quick-craft runs");
        assertTrue(body.substring(swallow).contains("return super.mouseReleased(mouseX, mouseY, button);"),
                "the rest reaches the base");
    }
}
