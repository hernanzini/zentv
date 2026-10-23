package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ZenTV's additions to Home, kept out of MainActivity so that a merge from upstream meets four calls there
 * (constructor, resume, pause, close): the weather line under the header (zen_weather), Bing's picture of the day
 * as the wallpaper (zen_daily_wallpaper) and an app opened at power-on (zen_autostart_app, see ZenAutostart).
 * The first two need the network and the third a foreground service, which upstream's launcher does not take on;
 * all are off unless config.xml or a theme turns them on (ZenConfig). Downloads run only while Home is on screen
 * and the network is validated: the weather every 30 minutes, the picture once a day.
 */
final class ZenTv {
    private static final String TAG = "ZenTv";
    private static final long MINUTE_MS = 60_000L;
    /** Coming hours shown in the weather line. */
    private static final int WEATHER_HOURS = 4;
    /** Wait before asking for the weather again after a failed try. */
    private static final long WEATHER_RETRY_MS = 5 * MINUTE_MS;

    private final Activity activity;
    private final FrameLayout root;
    private final ZenConfig cfg;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Downloads, one at a time and off the main thread; null when nothing here is on. */
    private final ExecutorService io;
    /** A new connection is worth a try at once, even inside the wait after a failed one. */
    private final ConnectivityManager.NetworkCallback networkCallback;
    private boolean resumed;
    private boolean closed;

    private ZenWeather weather;
    /** The weather's coordinates: the configured ones, or weather_city's once looked up (NaN until then). */
    private double latitude;
    private double longitude;
    /** What the weather line was last filled from, so the minute tick only rebuilds it when that changed. */
    private String weatherShown;
    private boolean weatherBusy;
    /** elapsedRealtime of the last weather request, 0 before the first. */
    private long weatherTriedAt;
    private View weatherBlock;
    private View weatherDivider;
    private ZenWeatherIcon weatherIcon;
    private TextView weatherTemp;
    private TextView weatherSky;
    private TextView weatherToday;
    private LinearLayout weatherHours;

    private ZenDailyPhoto photo;
    private boolean photoBusy;
    /** elapsedRealtime of the last check for a new daily picture, 0 before the first. */
    private long photoTriedAt;
    /** The daily picture on screen, the shade over it and its caption; null until there is one. */
    private ImageView wall;
    private View shade;
    private TextView caption;

    /** Opens zen_autostart_app at power-on; null when none is set. */
    private final ZenAutostart autostart;

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateWeather();   // the coming hours move on with the clock
            refresh();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    ZenTv(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        cfg = ZenConfig.load(activity);
        density = activity.getResources().getDisplayMetrics().density;
        io = cfg.online() ? Executors.newSingleThreadExecutor() : null;
        networkCallback = cfg.online() ? new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                // A few seconds for the network to be validated first; refresh() skips one that is not.
                handler.postDelayed(() -> {
                    weatherTriedAt = 0;
                    photoTriedAt = 0;
                    refresh();
                }, 5000);
            }
        } : null;
        if (networkCallback != null) {
            try {
                activity.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "no network callback: " + e);   // the minute tick still retries
            }
        }
        if (cfg.weather) attachWeather();
        if (cfg.dailyWallpaper) attachDailyWallpaper();
        autostart = cfg.autostartApp.isEmpty() ? null
                : new ZenAutostart(activity, handler, cfg.autostartApp, cfg.autostartUris);
    }

    void resume() {
        if (closed) return;
        if (autostart != null) autostart.resume();
        if (io == null) return;
        resumed = true;
        updateWeather();
        refresh();
        handler.removeCallbacks(minuteTick);
        handler.postDelayed(minuteTick, MINUTE_MS);
    }

    void pause() {
        resumed = false;
        handler.removeCallbacks(minuteTick);
        if (autostart != null) autostart.pause();
    }

    void close() {
        pause();
        closed = true;
        if (autostart != null) autostart.close();
        handler.removeCallbacksAndMessages(null);
        if (io != null) io.shutdownNow();
        if (networkCallback != null) {
            try {
                activity.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException ignored) {
                // never registered
            }
        }
    }

    // ---- downloads -------------------------------------------------------------------------------

    /** Starts whichever download is due, only while Home is on screen and the network is validated. */
    private void refresh() {
        if (!resumed || closed || io == null || !networkValidated()) return;
        long now = SystemClock.elapsedRealtime();
        long age = weather != null ? System.currentTimeMillis() - weather.fetchedAt : Long.MAX_VALUE;
        if (cfg.weather && weatherBlock != null && !weatherBusy && (age < 0 || age >= ZenWeather.REFRESH_MS)
                && (weatherTriedAt == 0 || now - weatherTriedAt >= WEATHER_RETRY_MS)) {
            fetchWeather();
        }
        if (cfg.dailyWallpaper && !photoBusy && (photo == null || !photo.isFor(LocalDate.now()))
                && (photoTriedAt == 0 || now - photoTriedAt >= ZenDailyPhoto.RETRY_MS)) {
            fetchPhoto();
        }
    }

    private boolean networkValidated() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = n != null ? cm.getNetworkCapabilities(n) : null;
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void fetchWeather() {
        weatherBusy = true;
        weatherTriedAt = SystemClock.elapsedRealtime();
        final File file = ZenWeather.file(activity);
        final File placeFile = ZenPlace.file(activity.getFilesDir());
        final String language = Locale.getDefault().getLanguage();
        final double knownLat = latitude, knownLon = longitude;
        io.execute(() -> {
            double lat = knownLat, lon = knownLon;
            ZenWeather fresh = null;
            try {
                if (Double.isNaN(lat) || Double.isNaN(lon)) {
                    // weather_city, asked once: after this the place comes from place.json.
                    ZenPlace place = ZenPlace.resolve(placeFile, cfg.city, language);
                    lat = place.latitude;
                    lon = place.longitude;
                }
                fresh = ZenWeather.fetch(file, lat, lon, System.currentTimeMillis());
            } catch (IOException | RuntimeException e) {
                // An uncaught exception here would take Home down with it; the last answer simply stays.
                Log.w(TAG, "weather: " + e);
            }
            final ZenWeather result = fresh;
            final double foundLat = lat, foundLon = lon;
            handler.post(() -> {
                weatherBusy = false;
                if (closed) return;
                latitude = foundLat;
                longitude = foundLon;
                if (result == null) return;
                weather = result;
                weatherShown = null;
                updateWeather();
            });
        });
    }

    private void fetchPhoto() {
        photoBusy = true;
        photoTriedAt = SystemClock.elapsedRealtime();
        final File dir = activity.getFilesDir();
        final ZenDailyPhoto current = photo;
        final String market = ZenDailyPhoto.market(Locale.getDefault());
        io.execute(() -> {
            ZenDailyPhoto fresh = null;
            Bitmap bmp = null;
            try {
                fresh = ZenDailyPhoto.fetch(dir, market, current);
                if (fresh != null) bmp = decode(fresh.image);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "daily wallpaper: " + e);
            }
            final ZenDailyPhoto result = fresh;
            final Bitmap picture = bmp;
            handler.post(() -> {
                photoBusy = false;
                if (result == null || picture == null || closed) return;
                photo = result;
                showPicture(picture, true);
            });
        });
    }

    // ---- weather line ----------------------------------------------------------------------------

    /** Puts the weather line right under the header, above the tiles; it stays empty until there is an answer. */
    private void attachWeather() {
        View leftBlock = activity.findViewById(R.id.leftBlock);
        ViewParent header = leftBlock != null ? leftBlock.getParent() : null;
        ViewParent column = header != null ? header.getParent() : null;
        if (!(header instanceof View) || !(column instanceof LinearLayout)) {
            Log.w(TAG, "the header is not where ZenTV expects it; no weather line");
            return;
        }
        LinearLayout c = (LinearLayout) column;
        View row = LayoutInflater.from(activity).inflate(R.layout.zen_weather_row, c, false);
        c.addView(row, c.indexOfChild((View) header) + 1);
        weatherBlock = row;
        weatherIcon = row.findViewById(R.id.zenWeatherIcon);
        weatherTemp = row.findViewById(R.id.zenWeatherTemp);
        weatherSky = row.findViewById(R.id.zenWeatherSky);
        weatherToday = row.findViewById(R.id.zenWeatherToday);
        weatherDivider = row.findViewById(R.id.zenWeatherDivider);
        weatherHours = row.findViewById(R.id.zenWeatherHours);
        latitude = cfg.latitude;
        longitude = cfg.longitude;
        if (!cfg.city.isEmpty()) {
            ZenPlace place = ZenPlace.cached(ZenPlace.file(activity.getFilesDir()), cfg.city);
            if (place != null) {
                latitude = place.latitude;
                longitude = place.longitude;
            }
        }
        if (!Double.isNaN(latitude) && !Double.isNaN(longitude)) {
            weather = ZenWeather.cached(ZenWeather.file(activity), latitude, longitude, System.currentTimeMillis());
        }
        updateWeather();
    }

    /** Fills the weather line from the last answer; hides it when there is none or it is too old. */
    private void updateWeather() {
        if (weatherBlock == null) return;
        ZenWeather w = weather;
        if (w == null || System.currentTimeMillis() - w.fetchedAt > ZenWeather.MAX_AGE_MS) {
            weatherBlock.setVisibility(View.INVISIBLE);
            weatherShown = null;
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<ZenWeather.Hour> next = w.upcoming(now, WEATHER_HOURS);
        String shown = w.fetchedAt + "/" + now.toLocalDate() + "/" + (next.isEmpty() ? "" : next.get(0).time);
        if (shown.equals(weatherShown)) return;
        weatherShown = shown;

        weatherIcon.setSky(w.sky, w.daytime);
        weatherTemp.setText(degrees(w.temperature));
        weatherSky.setText(w.sky.label);
        ZenWeather.Day today = w.day(now.toLocalDate());
        if (today != null) {
            String text = activity.getString(R.string.zen_weather_today, degrees(today.max), degrees(today.min));
            // Under 10% is the model's noise rather than a forecast worth reading.
            if (today.rainChance >= 10) text += " · " + activity.getString(R.string.zen_weather_rain, today.rainChance);
            weatherToday.setText(text);
            weatherToday.setVisibility(View.VISIBLE);
        } else {
            weatherToday.setVisibility(View.GONE);
        }
        Locale locale = Locale.getDefault();
        DateTimeFormatter hourFormat = DateTimeFormatter.ofPattern(
                DateFormat.getBestDateTimePattern(locale, DateFormat.is24HourFormat(activity) ? "Hm" : "ha"), locale);
        weatherHours.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (ZenWeather.Hour h : next) {
            View item = inflater.inflate(R.layout.zen_weather_hour, weatherHours, false);
            ((TextView) item.findViewById(R.id.zenHourTime)).setText(h.time.format(hourFormat));
            ((ZenWeatherIcon) item.findViewById(R.id.zenHourIcon)).setSky(h.sky, h.daytime);
            ((TextView) item.findViewById(R.id.zenHourTemp)).setText(degrees(h.temperature));
            weatherHours.addView(item);
        }
        weatherDivider.setVisibility(next.isEmpty() ? View.GONE : View.VISIBLE);
        weatherBlock.setVisibility(View.VISIBLE);
    }

    private static String degrees(int celsius) {
        return String.format(Locale.getDefault(), "%d°", celsius);
    }

    // ---- daily wallpaper -------------------------------------------------------------------------

    /** The kept picture at once, decoded here like upstream's first wallpaper, so Home opens with it. */
    private void attachDailyWallpaper() {
        photo = ZenDailyPhoto.cached(activity.getFilesDir());
        Bitmap bmp = photo != null ? decode(photo.image) : null;
        if (bmp != null) showPicture(bmp, false);
        else photo = null;   // unreadable: fetch it again
    }

    /**
     * Puts the picture over the theme's wallpaper (when there is one) and under everything else, with the shade
     * that keeps the clock and the tiles readable and the caption in the top corner. {@code fade} crossfades.
     */
    private void showPicture(Bitmap bmp, boolean fade) {
        ImageView next = new ImageView(activity);
        next.setScaleType(ImageView.ScaleType.CENTER_CROP);
        next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        next.setImageBitmap(bmp);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // Upstream's WallpaperPlayer puts a theme's picture at index 0, under the layout's own views.
        int at = wall != null ? root.indexOfChild(wall) + 1 : root.getChildAt(0) instanceof ImageView ? 1 : 0;
        root.addView(next, at, full);
        if (shade == null) {
            shade = new View(activity);
            shade.setBackground(shadeDrawable());
            root.addView(shade, root.indexOfChild(next) + 1, new FrameLayout.LayoutParams(full));
        }
        final ImageView old = wall;
        wall = next;
        showCaption();
        if (old == null) return;
        if (!fade) {
            root.removeView(old);
            return;
        }
        next.setAlpha(0f);
        next.animate().alpha(1f).setDuration(900).withEndAction(() -> root.removeView(old)).start();
    }

    /** What the picture shows and who took it, small in the top corner above the pills. */
    private void showCaption() {
        String text = photo != null ? photo.caption : "";
        if (caption == null) {
            if (text.isEmpty()) return;
            caption = new TextView(activity);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            caption.setTextColor(0xB3FFFFFF);
            caption.setShadowLayer(6 * density, 0, density, 0xCC000000);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setMaxWidth(Math.round(560 * density));
            caption.setFocusable(false);
            try {
                caption.setTypeface(UiFont.get(activity, R.string.font_regular));
            } catch (Exception ignored) {
                // the system font is fine
            }
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            params.topMargin = Math.round(26 * density);
            params.setMarginEnd(Math.round(62 * density));
            root.addView(caption, params);
        }
        caption.setText(text);
        caption.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * Darker at the top for the clock and the weather, lighter across the middle where the picture shows,
     * darkest at the bottom behind the tiles. A small bitmap stretched over the screen: one texture.
     */
    private BitmapDrawable shadeDrawable() {
        int h = 128;
        Bitmap bmp = Bitmap.createBitmap(1, h, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xB3000000, 0x40000000, 0x33000000, 0x8C000000, 0xD9000000},
                new float[]{0f, 0.3f, 0.5f, 0.72f, 1f},
                Shader.TileMode.CLAMP));
        new Canvas(bmp).drawRect(0, 0, 1, h, paint);
        BitmapDrawable drawable = new BitmapDrawable(activity.getResources(), bmp);
        drawable.setGravity(Gravity.FILL);
        drawable.setFilterBitmap(true);
        return drawable;
    }

    /** Decoded to cover the screen in RGB_565, as upstream's WallpaperPlayer does; null when it cannot be. */
    private Bitmap decode(File file) {
        try {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, dm.widthPixels, dm.heightPixels);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, dm.widthPixels, dm.heightPixels);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;   // no alpha needed, half the memory
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
            if (decoded == null) return null;
            Bitmap gpu = decoded.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return decoded;
            decoded.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take Home down; the theme's look stays.
            return null;
        }
    }
}
