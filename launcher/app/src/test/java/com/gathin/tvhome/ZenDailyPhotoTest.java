package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.time.LocalDate;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for reading Bing's picture of the day listing. */
public class ZenDailyPhotoTest {

    private static String listing(String urlBase, String wp) {
        return "{\"images\":[{\"startdate\":\"20261023\",\"urlbase\":\"" + urlBase + "\","
                + "\"copyright\":\" Dos lobos grises, Yellowstone (\\u00a9 John Morrison/Getty Images) \"" + wp + "}]}";
    }

    @Test
    public void readsTheDayThePictureAndTheCaption() {
        ZenDailyPhoto.Listing l = ZenDailyPhoto.parse(listing("/th?id=OHR.YellowstoneWolves_ES-ES1877782674", ""));
        assertNotNull(l);
        assertEquals("20261023", l.date);
        assertEquals("Dos lobos grises, Yellowstone (© John Morrison/Getty Images)", l.caption);
        assertEquals("https://www.bing.com/th?id=OHR.YellowstoneWolves_ES-ES1877782674_1920x1080.jpg", l.imageUrl());
    }

    @Test
    public void aDayNotOfferedAsWallpaperGivesWayToTheNewestThatIs() {
        String week = "{\"images\":["
                + "{\"startdate\":\"20261023\",\"urlbase\":\"/th?id=OHR.Library\",\"copyright\":\"a\",\"wp\":false},"
                + "{\"startdate\":\"20261022\",\"urlbase\":\"//example.com/x\",\"copyright\":\"b\",\"wp\":true},"
                + "{\"startdate\":\"20261021\",\"urlbase\":\"/th?id=OHR.Wolves\",\"copyright\":\"c\",\"wp\":true}]}";
        ZenDailyPhoto.Listing l = ZenDailyPhoto.parse(week);
        assertNotNull(l);
        assertEquals("20261021", l.date);   // the 22nd is skipped too: not a path on www.bing.com
        assertEquals("c", l.caption);
        assertNull(ZenDailyPhoto.parse("{\"images\":[{\"startdate\":\"20261023\",\"urlbase\":\"/th?id=OHR.X\",\"wp\":false}]}"));
    }

    @Test
    public void onlyPathsOnBingAreAccepted() {
        assertNull(ZenDailyPhoto.parse(listing("https://example.com/x", "")));
        assertNull(ZenDailyPhoto.parse(listing("//example.com/th?id=x", "")));
        assertNull(ZenDailyPhoto.parse(listing("/th?id=x//example.com", "")));
        assertNull(ZenDailyPhoto.parse("{\"images\":[]}"));
        assertNull(ZenDailyPhoto.parse("not json"));
    }

    @Test
    public void marketFollowsTheLanguage() {
        assertEquals("es-ES", ZenDailyPhoto.market(new Locale("es", "AR")));
        assertEquals("pt-BR", ZenDailyPhoto.market(new Locale("pt", "PT")));
        assertEquals("en-US", ZenDailyPhoto.market(new Locale("fa", "IR")));
    }

    @Test
    public void isForComparesBingsDay() {
        ZenDailyPhoto p = new ZenDailyPhoto(new File("daily.jpg"), "20261023", "");
        assertTrue(p.isFor(LocalDate.of(2026, 10, 23)));
        assertFalse(p.isFor(LocalDate.of(2026, 10, 24)));
    }
}
