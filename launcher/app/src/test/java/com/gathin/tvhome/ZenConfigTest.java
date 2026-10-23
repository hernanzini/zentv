package com.gathin.tvhome;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for ZenTV's settings: config.xml's values overlaid by a theme's keys. */
public class ZenConfigTest {

    private static final double NONE = Double.NaN;

    /** A theme's config.json over config.xml's defaults: everything off, no place. */
    private static ZenConfig theme(String json) throws Exception {
        return ZenConfig.from(json == null ? null : new JSONObject(json), false, NONE, NONE, "", NONE, NONE, false, "", Collections.emptyList());
    }

    @Test
    public void offByDefault() throws Exception {
        ZenConfig c = theme(null);
        assertFalse(c.weather);
        assertFalse(c.dailyWallpaper);
        assertEquals("", c.autostartApp);
        assertFalse(c.online());
    }

    @Test
    public void aThemeTurnsTheWeatherAndThePictureOn() throws Exception {
        ZenConfig c = theme("{\"weather\":true,\"weather_latitude\":-33.87,\"weather_longitude\":151.21,\"daily_wallpaper\":true}");
        assertTrue(c.weather);
        assertEquals(-33.87, c.latitude, 0);
        assertEquals(151.21, c.longitude, 0);
        assertEquals("", c.city);
        assertTrue(c.dailyWallpaper);
        assertTrue(c.online());
    }

    @Test
    public void aCityIsEnoughAndCoordinatesWinOverIt() throws Exception {
        ZenConfig c = theme("{\"weather\":true,\"weather_city\":\" Resistencia, Chaco, Argentina \"}");
        assertTrue(c.weather);
        assertEquals("Resistencia, Chaco, Argentina", c.city);
        assertTrue(Double.isNaN(c.latitude));
        c = theme("{\"weather\":true,\"weather_city\":\"Resistencia\",\"weather_latitude\":1.5,\"weather_longitude\":2.5}");
        assertEquals("", c.city);
        assertEquals(1.5, c.latitude, 0);
    }

    @Test
    public void theSkysPlaceIsTheLastResort() throws Exception {
        assertFalse(theme("{\"weather\":true}").weather);
        JSONObject on = new JSONObject("{\"weather\":true}");
        ZenConfig sky = ZenConfig.from(on, false, NONE, NONE, "", 54.69, 25.28, false, "", Collections.emptyList());
        assertTrue(sky.weather);
        assertEquals(54.69, sky.latitude, 0);
        // A city given for the weather comes before the sky's coordinates.
        ZenConfig city = ZenConfig.from(new JSONObject("{\"weather\":true,\"weather_city\":\"Vilnius\"}"),
                false, NONE, NONE, "", 54.69, 25.28, false, "", Collections.emptyList());
        assertEquals("Vilnius", city.city);
        assertTrue(Double.isNaN(city.latitude));
    }

    @Test
    public void wrongTypesAndRangesKeepTheDefaults() throws Exception {
        JSONObject json = new JSONObject("{\"weather\":\"yes\",\"weather_latitude\":95,\"weather_longitude\":\"-58\","
                + "\"weather_city\":7,\"daily_wallpaper\":1,\"autostart_app\":false}");
        ZenConfig c = ZenConfig.from(json, true, 10, 20, "", NONE, NONE, false, "", Collections.emptyList());
        assertTrue(c.weather);
        assertEquals(10, c.latitude, 0);
        assertEquals(20, c.longitude, 0);
        assertFalse(c.dailyWallpaper);
        assertEquals("", c.autostartApp);
    }

    @Test
    public void aThemeSetsTheAutostartApp() throws Exception {
        ZenConfig c = theme("{\"autostart_app\":\" ar.com.sensa.player \",\"autostart_uri\":\"minerva://content/livetv\"}");
        assertEquals("ar.com.sensa.player", c.autostartApp);
        assertEquals(Collections.singletonList("minerva://content/livetv"), c.autostartUris);
        assertFalse(c.online());   // waits for the network, but downloads nothing
        // A deep link without an app means nothing.
        assertTrue(theme("{\"autostart_uri\":\"minerva://content/livetv\"}").autostartUris.isEmpty());
    }

    @Test
    public void severalDeepLinksAsAListOrOneString() throws Exception {
        List<String> both = Arrays.asList("minerva://content/livetv", "minerva://keycode/dpad_center");
        assertEquals(both, theme("{\"autostart_app\":\"a\",\"autostart_uri\":"
                + "[\"minerva://content/livetv\",7,\" minerva://keycode/dpad_center \"]}").autostartUris);
        assertEquals(both, theme("{\"autostart_app\":\"a\",\"autostart_uri\":"
                + "\" minerva://content/livetv   minerva://keycode/dpad_center \"}").autostartUris);
        assertTrue(ZenConfig.links("  ").isEmpty());
    }

    @Test
    public void configXmlCoordinates() {
        assertEquals(-33.87, ZenConfig.coordinate(" -33.87 ", NONE, 90), 0);
        assertTrue(Double.isNaN(ZenConfig.coordinate("", NONE, 90)));
        assertTrue(Double.isNaN(ZenConfig.coordinate("200", NONE, 180)));
        assertEquals(1.5, ZenConfig.coordinate("north", 1.5, 90), 0);
    }
}
