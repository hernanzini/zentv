package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The weather line under the header (zen_weather, see ZenConfig for the place): now, today's high,
 * low and chance of rain, and the next few hours, from Open-Meteo (api.open-meteo.com, no account or key).
 * The last answer is kept in weather.json with the place it is for, so Home shows it at once after a
 * restart and refreshes it in the background.
 */
final class ZenWeather {
    static final String FILE_NAME = "weather.json";
    /** How often Home asks again while it is on screen. */
    static final long REFRESH_MS = 30 * 60_000L;
    /** An answer older than this is not shown at all. */
    static final long MAX_AGE_MS = 6 * 60 * 60_000L;
    private static final int MAX_BYTES = 256 * 1024;

    /** What the sky looks like, from the WMO weather code: a label and an icon (ZenWeatherIcon). */
    enum Sky {
        CLEAR(R.string.zen_sky_clear),
        MOSTLY_CLEAR(R.string.zen_sky_mostly_clear),
        PARTLY_CLOUDY(R.string.zen_sky_partly_cloudy),
        CLOUDY(R.string.zen_sky_cloudy),
        FOG(R.string.zen_sky_fog),
        DRIZZLE(R.string.zen_sky_drizzle),
        LIGHT_RAIN(R.string.zen_sky_light_rain),
        RAIN(R.string.zen_sky_rain),
        HEAVY_RAIN(R.string.zen_sky_heavy_rain),
        SNOW(R.string.zen_sky_snow),
        STORM(R.string.zen_sky_storm),
        HAIL(R.string.zen_sky_hail);

        final int label;

        Sky(int label) {
            this.label = label;
        }
    }

    static final class Day {
        final LocalDate date;
        final int max;
        final int min;
        /** Highest chance of rain in the day, 0 to 100. */
        final int rainChance;
        final Sky sky;

        Day(LocalDate date, int max, int min, int rainChance, Sky sky) {
            this.date = date;
            this.max = max;
            this.min = min;
            this.rainChance = rainChance;
            this.sky = sky;
        }
    }

    static final class Hour {
        final LocalDateTime time;
        final int temperature;
        final Sky sky;
        final boolean daytime;

        Hour(LocalDateTime time, int temperature, Sky sky, boolean daytime) {
            this.time = time;
            this.temperature = temperature;
            this.sky = sky;
            this.daytime = daytime;
        }
    }

    /** When the answer was received (currentTimeMillis). */
    final long fetchedAt;
    /** Now, in whole degrees Celsius. */
    final int temperature;
    final Sky sky;
    final boolean daytime;
    final List<Day> days;
    final List<Hour> hours;

    private ZenWeather(long fetchedAt, int temperature, Sky sky, boolean daytime, List<Day> days, List<Hour> hours) {
        this.fetchedAt = fetchedAt;
        this.temperature = temperature;
        this.sky = sky;
        this.daytime = daytime;
        this.days = Collections.unmodifiableList(days);
        this.hours = Collections.unmodifiableList(hours);
    }

    /** The forecast for this date, or null when the answer does not cover it. */
    Day day(LocalDate date) {
        for (Day d : days) if (d.date.equals(date)) return d;
        return null;
    }

    /**
     * Up to {@code n} coming hours on the hour divisible by 3, the first at least an hour from {@code now}:
     * at 8:30 that is 12, 15, 18 and 21. Worked out at each redraw, so the slots move on with the clock.
     */
    List<Hour> upcoming(LocalDateTime now, int n) {
        List<Hour> out = new ArrayList<>();
        LocalDateTime from = now.plusHours(1);
        for (Hour h : hours) {
            if (out.size() == n) break;
            if (h.time.getHour() % 3 == 0 && !h.time.isBefore(from)) out.add(h);
        }
        return out;
    }

    static Sky sky(int wmoCode) {
        switch (wmoCode) {
            case 0:
                return Sky.CLEAR;
            case 1:
                return Sky.MOSTLY_CLEAR;
            case 2:
                return Sky.PARTLY_CLOUDY;
            case 45:
            case 48:
                return Sky.FOG;
            case 51:
            case 53:
            case 55:
            case 56:
            case 57:
                return Sky.DRIZZLE;
            case 61:
            case 66:
            case 80:
                return Sky.LIGHT_RAIN;
            case 63:
            case 81:
                return Sky.RAIN;
            case 65:
            case 67:
            case 82:
                return Sky.HEAVY_RAIN;
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86:
                return Sky.SNOW;
            case 95:
                return Sky.STORM;
            case 96:
            case 99:
                return Sky.HAIL;
            default:
                return Sky.CLOUDY;   // 3, and any code added after this was written
        }
    }

    /** Two days, so the coming hours carry on past midnight; times are local to the place (timezone=auto). */
    static String url(double latitude, double longitude) {
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=auto&forecast_days=2", latitude, longitude);
    }

    /** The answer, or null when it is not an Open-Meteo forecast with the fields asked for. */
    static ZenWeather parse(String json, long fetchedAt) {
        try {
            return parse(new JSONObject(json), fetchedAt);
        } catch (JSONException e) {
            return null;
        }
    }

    static ZenWeather parse(JSONObject o, long fetchedAt) {
        try {
            JSONObject now = o.getJSONObject("current");
            JSONObject daily = o.getJSONObject("daily");
            JSONArray dTime = daily.getJSONArray("time");
            JSONArray dCode = daily.getJSONArray("weather_code");
            JSONArray dMax = daily.getJSONArray("temperature_2m_max");
            JSONArray dMin = daily.getJSONArray("temperature_2m_min");
            JSONArray dRain = daily.getJSONArray("precipitation_probability_max");
            List<Day> days = new ArrayList<>();
            for (int i = 0; i < dTime.length(); i++) {
                if (dMax.isNull(i) || dMin.isNull(i)) continue;
                days.add(new Day(LocalDate.parse(dTime.getString(i)), round(dMax.getDouble(i)), round(dMin.getDouble(i)),
                        dRain.isNull(i) ? 0 : dRain.getInt(i), sky(dCode.optInt(i, 3))));
            }
            JSONObject hourly = o.getJSONObject("hourly");
            JSONArray hTime = hourly.getJSONArray("time");
            JSONArray hTemp = hourly.getJSONArray("temperature_2m");
            JSONArray hCode = hourly.getJSONArray("weather_code");
            JSONArray hDay = hourly.getJSONArray("is_day");
            List<Hour> hours = new ArrayList<>();
            for (int i = 0; i < hTime.length(); i++) {
                if (hTemp.isNull(i)) continue;
                hours.add(new Hour(LocalDateTime.parse(hTime.getString(i)), round(hTemp.getDouble(i)),
                        sky(hCode.optInt(i, 3)), hDay.optInt(i, 1) == 1));
            }
            return new ZenWeather(fetchedAt, round(now.getDouble("temperature_2m")), sky(now.getInt("weather_code")),
                    now.optInt("is_day", 1) == 1, days, hours);
        } catch (JSONException | DateTimeParseException e) {
            return null;
        }
    }

    static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** The kept answer for this place, or null when there is none, it is for another place or it is too old. */
    static ZenWeather cached(File file, double latitude, double longitude, long now) {
        if (!file.isFile()) return null;
        try {
            JSONObject o = new JSONObject(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            if (o.getDouble("latitude") != latitude || o.getDouble("longitude") != longitude) return null;
            long at = o.getLong("fetched_at");
            if (now - at > MAX_AGE_MS) return null;
            return parse(o.getJSONObject("answer"), at);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Network, off the main thread: asks Open-Meteo, keeps the answer in {@code file} and returns it. */
    static ZenWeather fetch(File file, double latitude, double longitude, long now) throws IOException {
        String body = new String(ZenNet.get(url(latitude, longitude), MAX_BYTES), StandardCharsets.UTF_8);
        JSONObject answer;
        try {
            answer = new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("not JSON from api.open-meteo.com");
        }
        ZenWeather w = parse(answer, now);
        if (w == null) throw new IOException("not a forecast from api.open-meteo.com: " + answer.optString("reason"));
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            byte[] bytes = new JSONObject()
                    .put("latitude", latitude)
                    .put("longitude", longitude)
                    .put("fetched_at", now)
                    .put("answer", answer)
                    .toString().getBytes(StandardCharsets.UTF_8);
            out = af.startWrite();
            out.write(bytes);
            af.finishWrite(out);
        } catch (IOException | JSONException e) {
            // Only the cache is lost: the answer is still shown, and asked for again after the next restart.
            if (out != null) af.failWrite(out);
        }
        return w;
    }

    private static int round(double d) {
        return (int) Math.round(d);
    }
}
