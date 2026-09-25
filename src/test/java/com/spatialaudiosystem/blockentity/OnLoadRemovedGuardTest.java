package com.spatialaudiosystem.blockentity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NeoForge runs a block entity's {@code onLoad} a tick late, and for one removed in the meantime too: its
 * {@code setRemoved} has already run, so whatever {@code onLoad} registers then - a manta:data host, a listener, a
 * SavedData entry, a static set - is never undone. Every {@code onLoad} override returns at once for a removed entity:
 * nothing but {@code super.onLoad()} comes before {@code if (isRemoved()) return;}.
 *
 * <p>Measured (second reading 13, then the sweep after TSU's slice T2 of MANTA_7_CONCEPT C4): the hosts were guarded and
 * five other registrations in the same methods were not - the defect had been fixed one instance at a time, each
 * guard in the condition of the one call it was written for. A body that merely reads {@code isRemoved()} somewhere
 * would still pass that shape, so the rule is the early return, read from the sources.</p>
 */
class OnLoadRemovedGuardTest {

    /** This module's own package directory under src/main/java: the marker the source roots are found by. */
    private static final String OWN = "com/spatialaudiosystem";
    private static final List<String> SOURCE_SETS = List.of("src/main/java");

    /** The source roots, found upwards from the working directory (a test may run from a subdirectory). */
    private static List<Path> roots() {
        for (Path base = Paths.get("").toAbsolutePath(); base != null; base = base.getParent()) {
            if (Files.isDirectory(base.resolve(SOURCE_SETS.get(0)).resolve(OWN))) {
                List<Path> out = new ArrayList<>();
                for (String set : SOURCE_SETS) out.add(base.resolve(set));
                return out;
            }
        }
        throw new AssertionError("src/main/java/" + OWN + " not found from " + Paths.get("").toAbsolutePath());
    }

    @Test
    @DisplayName("every onLoad override returns first for a removed entity")
    void everyOnLoadReturnsFirstWhenRemoved() throws IOException {
        List<String> seen = new ArrayList<>();
        List<String> unguarded = new ArrayList<>();
        for (Path root : roots()) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String src = Files.readString(f, StandardCharsets.UTF_8);
                    for (String body : onLoadBodies(src)) {
                        seen.add(f.toString());
                        if (!guardsFirst(body)) unguarded.add(f.toString());
                    }
                }
            }
        }
        // The two this module has today; a scan that found none would pass by reading nothing.
        assertTrue(seen.size() >= 2, "onLoad overrides found: " + seen);
        assertEquals(List.of(), unguarded, "onLoad that does not return first for a removed entity");
    }

    @Test
    @DisplayName("the scan itself: only a body that returns first for a removed entity passes")
    void scanSeesTheGuard() {
        assertEquals(List.of(" super.onLoad(); REGISTRY.add(this); "),
                onLoadBodies("class A { @Override public void onLoad() { super.onLoad(); REGISTRY.add(this); } }"));
        assertEquals(List.of(" x(); "), onLoadBodies("interface I { void onLoad(); } class B { void onLoad() { x(); } }"),
                "a declaration without a body is not read as the next method's");
        assertTrue(guardsFirst(" super.onLoad(); // why\n if (isRemoved()) return; REGISTRY.add(this); "));
        assertTrue(guardsFirst(" /* why */ if (isRemoved()) return; REGISTRY.add(this); "));
        assertFalse(guardsFirst(" super.onLoad(); REGISTRY.add(this); "), "no guard");
        assertFalse(guardsFirst(" super.onLoad(); GRID.init(); if (isRemoved()) return; HOST.open(); "),
                "a guard after a registration");
        assertFalse(guardsFirst(" super.onLoad(); GRID.init(); if (!isRemoved()) HOST.open(); "),
                "a guard in the condition of one call only");
    }

    /** Whether the body returns at once for a removed entity: nothing before the guard but super.onLoad(). */
    static boolean guardsFirst(String body) {
        String code = body.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ")
                .replaceAll("\\s+", " ").trim();
        return code.matches("(super\\.onLoad\\(\\); )?if \\(isRemoved\\(\\)\\) return;.*");
    }

    /** The body of every {@code void onLoad()} declared in {@code src}, braces matched. */
    static List<String> onLoadBodies(String src) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = src.indexOf("void onLoad()", from);
            if (at < 0) return out;
            int open = src.indexOf('{', at);
            int semi = src.indexOf(';', at);
            if (open < 0) return out;
            if (semi >= 0 && semi < open) { // a declaration without a body
                from = semi;
                continue;
            }
            int depth = 0;
            int i = open;
            for (; i < src.length(); i++) {
                char c = src.charAt(i);
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) break;
            }
            out.add(src.substring(open + 1, i));
            from = i;
        }
    }
}
