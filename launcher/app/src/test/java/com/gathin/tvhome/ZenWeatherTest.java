package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for reading Open-Meteo's answer and choosing what the weather line shows. */
public class ZenWeatherTest {

    /** An answer shaped like Open-Meteo's for two days from midnight, hour by hour. */
    private static String answer() throws Exception {
        JSONArray time = new JSONArray(), temp = new JSONArray(), code = new JSONArray(), day = new JSONArray();
        for (int i = 0; i < 48; i++) {
            LocalDateTime t = LocalDateTime.of(2026, 10, 23, 0, 0).plusHours(i);
            time.put(t.toString());
            temp.put(18.4 + (i % 24) * 0.5);
            code.put(i < 12 ? 3 : 95);
            day.put(t.getHour() >= 7 && t.getHour() < 20 ? 1 : 0);
        }
        return new JSONObject()
                .put("current", new JSONObject().put("time", "2026-10-23T08:30").put("temperature_2m", 22.5)
                        .put("weather_code", 51).put("is_day", 1))
                .put("hourly", new JSONObject().put("time", time).put("temperature_2m", temp)
                        .put("weather_code", code).put("is_day", day))
                .put("daily", new JSONObject()
                        .put("time", new JSONArray().put("2026-10-23").put("2026-10-24"))
                        .put("weather_code", new JSONArray().put(51).put(95))
                        .put("temperature_2m_max", new JSONArray().put(32.8).put(25.7))
                        .put("temperature_2m_min", new JSONArray().put(19.2).put(19.1))
                        .put("precipitation_probability_max", new JSONArray().put(50).put(JSONObject.NULL)))
                .toString();
    }

    @Test
    public void readsNowTodayAndTheHours() throws Exception {
        ZenWeather w = ZenWeather.parse(answer(), 1234L);
        assertNotNull(w);
        assertEquals(1234L, w.fetchedAt);
        assertEquals(23, w.temperature);   // 22.5 rounds half up
        assertEquals(ZenWeather.Sky.DRIZZLE, w.sky);
        assertTrue(w.daytime);
        ZenWeather.Day today = w.day(LocalDate.of(2026, 10, 23));
        assertNotNull(today);
        assertEquals(33, today.max);
        assertEquals(19, today.min);
        assertEquals(50, today.rainChance);
        assertEquals(ZenWeather.Sky.DRIZZLE, today.sky);
        assertEquals(0, w.day(LocalDate.of(2026, 10, 24)).rainChance);   // null in the answer
        assertNull(w.day(LocalDate.of(2026, 10, 25)));
        assertEquals(48, w.hours.size());
    }

    @Test
    public void comingHoursAreEveryThirdHourAtLeastAnHourAway() throws Exception {
        ZenWeather w = ZenWeather.parse(answer(), 0L);
        assertEquals(hours(12, 15, 18, 21), hoursOf(w.upcoming(LocalDateTime.of(2026, 10, 23, 8, 30), 4)));
        assertEquals(hours(9, 12, 15, 18), hoursOf(w.upcoming(LocalDateTime.of(2026, 10, 23, 8, 0), 4)));
        // Past midnight into the second day, and fewer when the answer runs out.
        assertEquals(hours(0, 3, 6, 9), hoursOf(w.upcoming(LocalDateTime.of(2026, 10, 23, 22, 10), 4)));
        assertEquals(hours(21), hoursOf(w.upcoming(LocalDateTime.of(2026, 10, 24, 19, 0), 4)));
        ZenWeather.Hour first = w.upcoming(LocalDateTime.of(2026, 10, 23, 8, 30), 1).get(0);
        assertEquals(ZenWeather.Sky.STORM, first.sky);
        assertTrue(first.daytime);
    }

    @Test
    public void notAForecastIsNull() {
        assertNull(ZenWeather.parse("", 0L));
        assertNull(ZenWeather.parse("{\"error\":true,\"reason\":\"Latitude must be in range\"}", 0L));
        assertNull(ZenWeather.parse("{\"current\":{\"temperature_2m\":20,\"weather_code\":0}}", 0L));
    }

    @Test
    public void wmoCodesMapToSkies() {
        assertEquals(ZenWeather.Sky.CLEAR, ZenWeather.sky(0));
        assertEquals(ZenWeather.Sky.MOSTLY_CLEAR, ZenWeather.sky(1));
        assertEquals(ZenWeather.Sky.PARTLY_CLOUDY, ZenWeather.sky(2));
        assertEquals(ZenWeather.Sky.CLOUDY, ZenWeather.sky(3));
        assertEquals(ZenWeather.Sky.FOG, ZenWeather.sky(48));
        assertEquals(ZenWeather.Sky.LIGHT_RAIN, ZenWeather.sky(80));
        assertEquals(ZenWeather.Sky.HEAVY_RAIN, ZenWeather.sky(65));
        assertEquals(ZenWeather.Sky.SNOW, ZenWeather.sky(86));
        assertEquals(ZenWeather.Sky.HAIL, ZenWeather.sky(99));
        assertEquals(ZenWeather.Sky.CLOUDY, ZenWeather.sky(42));   // unknown
    }

    @Test
    public void urlUsesDotsWhateverTheLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("es", "AR"));   // writes -33,87
            String url = ZenWeather.url(-33.87, 151.21);
            assertTrue(url, url.startsWith("https://api.open-meteo.com/v1/forecast?latitude=-33.8700&longitude=151.2100&"));
        } finally {
            Locale.setDefault(saved);
        }
    }

    private static List<Integer> hours(int... h) {
        List<Integer> out = new ArrayList<>();
        for (int i : h) out.add(i);
        return out;
    }

    private static List<Integer> hoursOf(List<ZenWeather.Hour> slots) {
        List<Integer> out = new ArrayList<>();
        for (ZenWeather.Hour h : slots) out.add(h.time.getHour());
        return out;
    }
}
