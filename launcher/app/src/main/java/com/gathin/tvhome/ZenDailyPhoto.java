package com.gathin.tvhome;

import android.graphics.BitmapFactory;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Bing's picture of the day as the wallpaper (zen_daily_wallpaper): downloaded once a day from
 * www.bing.com into daily.jpg, with its date and caption in daily.json. Bing offers these pictures for use
 * as wallpapers only, which is all this does. Some days' pictures are not offered as wallpapers ("wp": false):
 * then the most recent one of the last eight days that is takes its place.
 */
final class ZenDailyPhoto {
    static final String IMAGE_NAME = "daily.jpg";
    static final String META_NAME = "daily.json";
    /** How long to wait before asking again after a failure, or while Bing still has yesterday's picture. */
    static final long RETRY_MS = 60 * 60_000L;
    private static final int MAX_LISTING_BYTES = 64 * 1024;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    final File image;
    /** Bing's day for the picture, yyyyMMdd. */
    final String date;
    /** What it shows and who took it, as Bing words it; may be empty. */
    final String caption;

    ZenDailyPhoto(File image, String date, String caption) {
        this.image = image;
        this.date = date;
        this.caption = caption;
    }

    /** True when this is the picture for {@code day}. */
    boolean isFor(LocalDate day) {
        return date.equals(day.format(DateTimeFormatter.BASIC_ISO_DATE));
    }

    /** One entry of Bing's picture archive: the day, the address without its size suffix, the caption. */
    static final class Listing {
        final String date;
        final String urlBase;
        final String caption;

        Listing(String date, String urlBase, String caption) {
            this.date = date;
            this.urlBase = urlBase;
            this.caption = caption;
        }

        /** The 1920x1080 picture, always on www.bing.com whatever the listing says. */
        String imageUrl() {
            return "https://www.bing.com" + urlBase + "_1920x1080.jpg";
        }
    }

    /**
     * Bing market for the TV's language, so the caption comes in that language where Bing writes one
     * (Spanish: es-ES; es-AR and other Latin American markets get the English worldwide caption).
     */
    static String market(Locale locale) {
        switch (locale.getLanguage()) {
            case "es":
                return "es-ES";
            case "pt":
                return "pt-BR";
            case "fr":
                return "fr-FR";
            case "de":
                return "de-DE";
            case "it":
                return "it-IT";
            case "ja":
                return "ja-JP";
            case "zh":
                return "zh-CN";
            default:
                return "en-US";
        }
    }

    /** The last eight days, newest first: Bing's archive gives no more in one answer. */
    static String listingUrl(String market) {
        return "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=" + market;
    }

    /**
     * The newest entry in Bing's answer that is offered as a wallpaper and looks right, or null when there is none
     * (2026-10-23 in es-ES was not, while the day before was).
     */
    static Listing parse(String json) {
        try {
            JSONArray images = new JSONObject(json).getJSONArray("images");
            for (int n = 0; n < images.length(); n++) {
                JSONObject i = images.optJSONObject(n);
                if (i == null || !i.optBoolean("wp", true)) continue;
                String date = i.optString("startdate");
                String urlBase = i.optString("urlbase");
                // A path on www.bing.com, nothing that could change the host or the scheme.
                if (!date.matches("\\d{8}") || !urlBase.startsWith("/th?id=") || urlBase.contains("//")) continue;
                return new Listing(date, urlBase, i.optString("copyright").trim());
            }
            return null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** The picture kept in {@code dir}, or null when there is none. */
    static ZenDailyPhoto cached(File dir) {
        File image = new File(dir, IMAGE_NAME);
        File meta = new File(dir, META_NAME);
        if (!image.isFile() || image.length() == 0 || !meta.isFile()) return null;
        try {
            JSONObject o = new JSONObject(new String(new AtomicFile(meta).readFully(), StandardCharsets.UTF_8));
            return new ZenDailyPhoto(image, o.getString("date"), o.optString("caption"));
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /**
     * Network, off the main thread: the newest picture offered as a wallpaper into {@code dir}. Null when the kept
     * one is already that one, so it stays.
     */
    static ZenDailyPhoto fetch(File dir, String market, ZenDailyPhoto current) throws IOException {
        String body = new String(ZenNet.get(listingUrl(market), MAX_LISTING_BYTES), StandardCharsets.UTF_8);
        Listing l = parse(body);
        if (l == null) throw new IOException("no picture offered as a wallpaper by www.bing.com");
        if (current != null && l.date.equals(current.date)) return null;
        byte[] jpeg = ZenNet.get(l.imageUrl(), MAX_IMAGE_BYTES);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("not a picture: " + l.imageUrl());
        File image = new File(dir, IMAGE_NAME);
        write(image, jpeg);
        try {
            write(new File(dir, META_NAME), new JSONObject().put("date", l.date).put("caption", l.caption)
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return new ZenDailyPhoto(image, l.date, l.caption);
    }

    private static void write(File file, byte[] bytes) throws IOException {
        AtomicFile af = new AtomicFile(file);
        FileOutputStream out = af.startWrite();
        try {
            out.write(bytes);
            af.finishWrite(out);
        } catch (IOException e) {
            af.failWrite(out);
            throw e;
        }
    }
}
