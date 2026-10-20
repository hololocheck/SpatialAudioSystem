package com.spatialaudiosystem.client.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manta.api.wiki.WikiLiveScreens;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The screen table BelugaAOS's UI sweep reads by reflection ({@code belugalab.aos.manta.Catalogue}): it finds this class by
 * name, calls its static {@code table()}, and reads each row through the accessors {@code id}, {@code factory},
 * {@code apply}, {@code states} and, when present, {@code applyBeforeInit}. None of that fails anything here at runtime -
 * the driver treats a provider it cannot read as one with no catalogue, and the sweep simply has fewer rows, which the C2
 * gate cannot notice because it counts against the same catalogue - so the names are held here, as the STRINGS the driver
 * uses: an IDE rename rewrites a call, not a string (second reading, 2026-09-27).
 */
class SasScreenCatalogueTest {

    @Test
    void theClassIsWhereTheDriverLooksForIt() {
        assertEquals("com.spatialaudiosystem.client.wiki.SasWikiLiveCapture", SasWikiLiveCapture.class.getName());
    }

    @Test
    void theTableIsTheStaticMethodTheDriverCalls() throws ReflectiveOperationException {
        Method table = SasWikiLiveCapture.class.getDeclaredMethod("table");
        assertTrue(Modifier.isStatic(table.getModifiers()), "the driver invokes it with no instance");
        assertEquals(List.class, table.getReturnType());
    }

    @Test
    void everyRowHasTheAccessorsTheDriverReads() throws ReflectiveOperationException {
        for (String name : List.of("id", "factory", "apply", "states", "applyBeforeInit")) {
            Method m = SasWikiLiveCapture.Entry.class.getDeclaredMethod(name);
            assertTrue(m.getParameterCount() == 0, name + " is an accessor");
        }
        assertEquals(String[].class, SasWikiLiveCapture.Entry.class.getDeclaredMethod("states").getReturnType());
        assertEquals(boolean.class,
                SasWikiLiveCapture.Entry.class.getDeclaredMethod("applyBeforeInit").getReturnType(),
                "the driver refuses a row whose order is not a boolean");
    }

    @Test
    void idsAndStatesAreUniqueAndNoRowIsEmpty() {
        List<SasWikiLiveCapture.Entry> table = SasWikiLiveCapture.table();
        // Second reading 2026-09-27: over an empty table the loop below asserts nothing.
        assertFalse(table.isEmpty(), "an empty table would pass every check below");
        Set<String> ids = new HashSet<>();
        for (SasWikiLiveCapture.Entry e : table) {
            assertTrue(ids.add(e.id()), "a second row with id " + e.id() + " would be unreachable by id");
            assertTrue(e.states().length > 0, e.id() + " has no state to open it in");
            assertEquals(e.states().length, Set.of(e.states()).size(), e.id() + " repeats a state");
        }
    }

    /**
     * The sweep-only states (user, 2026-10-21) are the sweep's and never the wiki's: each is a state the driver walks, none
     * is in {@link SasWikiLiveCapture#wikiRows()} - the rows the capture loop and the live registration both read - and,
     * registered as the client registers them, Manta's own resolver draws none of their pictures while it draws every
     * other state of a photographed screen (the control that the url asked is one the resolver answers).
     */
    @Test
    void theSweepOnlyStatesAreWalkedByTheSweepAndNeverPhotographedOrDrawn() {
        SasWikiLiveCapture.registerLive();
        int sweepOnly = 0;
        int drawn = 0;
        for (SasWikiLiveCapture.Entry e : SasWikiLiveCapture.table()) {
            List<String> states = List.of(e.states());
            for (String s : e.sweepOnly()) {
                sweepOnly++;
                assertTrue(states.contains(s), e.id() + "/" + s + " is not a state, so the sweep never opens it");
                assertFalse(WikiLiveScreens.resolves(picture(e.id(), s)), e.id() + "/" + s + " is drawn by the wiki");
            }
            for (String s : e.wikiStates()) {
                assertFalse(e.sweepOnly().contains(s), e.id() + "/" + s + " is the sweep's, not the wiki's");
                if (e.photographed()) {
                    drawn++;
                    assertTrue(WikiLiveScreens.resolves(picture(e.id(), s)), e.id() + "/" + s + " is not drawn live");
                }
            }
            assertEquals(states.size(), e.wikiStates().length + e.sweepOnly().size(),
                    e.id() + ": every state is the wiki's or the sweep's, exactly once");
        }
        assertTrue(sweepOnly > 0 && drawn > 0, "nothing was judged (" + sweepOnly + " sweep-only, " + drawn + " drawn)");
        for (WikiLiveScreens.Row row : SasWikiLiveCapture.wikiRows()) {
            SasWikiLiveCapture.Entry e = SasWikiLiveCapture.table().stream()
                    .filter(t -> t.id().equals(row.id())).findFirst().orElseThrow();
            for (String s : row.states()) {
                assertFalse(e.sweepOnly().contains(s), row.id() + "/" + s + " would be photographed");
            }
        }
    }

    /** A page's picture of {@code id} in {@code state}: {@code <id>__<state>__<lang>.png}, the form Manta resolves. */
    private static String picture(String id, String state) {
        return "bws:spatialaudiosystem:wiki/screens/" + id + "__" + state + "__ja_jp.png";
    }
}
