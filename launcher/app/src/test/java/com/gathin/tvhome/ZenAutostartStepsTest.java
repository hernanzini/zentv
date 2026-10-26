package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** Plain JVM tests for zen_autostart_steps: the "view:link" entries and which one the focused view picks. */
public class ZenAutostartStepsTest {

    private static final String APP = "ar.com.sensa.player";
    private static final List<ZenAutostartSteps.Step> SENSA = ZenAutostartSteps.parse(Arrays.asList(
            "browse_headers:minerva://content/livetv", "grid:minerva://keycode/media_play"));

    @Test
    public void anEntrySplitsAtItsFirstColon() {
        assertEquals(2, SENSA.size());
        assertEquals("grid", SENSA.get(1).view);
        assertEquals("minerva://keycode/media_play", SENSA.get(1).link);
    }

    @Test
    public void anEntryWithoutAViewOrALinkIsSkipped() {
        assertEquals(0, ZenAutostartSteps.parse(Arrays.asList("grid", ":minerva://keycode/media_play", "grid: ")).size());
    }

    @Test
    public void theFocusedViewPicksItsStep() {
        assertEquals(1, ZenAutostartSteps.match(SENSA, APP, Arrays.asList("ar.com.sensa.player:id/grid",
                "ar.com.sensa.player:id/fl_epg_item")));
    }

    @Test
    public void aViewInsideAContainerPicksTheContainersStep() {
        assertEquals(0, ZenAutostartSteps.match(SENSA, APP, Arrays.asList(null, "ar.com.sensa.player:id/row_header",
                "ar.com.sensa.player:id/browse_headers", "ar.com.sensa.player:id/browse_frame")));
    }

    @Test
    public void anIdThatOnlyEndsLikeAViewDoesNotMatch() {
        assertEquals(-1, ZenAutostartSteps.match(SENSA, APP, Arrays.asList("ar.com.sensa.player:id/epg_grid")));
    }

    @Test
    public void noFocusNoStep() {
        assertEquals(-1, ZenAutostartSteps.match(SENSA, APP, Collections.<String>emptyList()));
    }

    @Test
    public void anotherAppsViewWithTheSameIdDoesNotMatch() {
        assertEquals(-1, ZenAutostartSteps.match(SENSA, APP, Arrays.asList("com.gathin.tvhome:id/grid")));
    }
}
