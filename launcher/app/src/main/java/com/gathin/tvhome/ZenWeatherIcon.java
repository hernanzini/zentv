package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * A weather icon drawn from shapes on a 24-unit grid, so it stays sharp at any size: sun or moon, a cloud,
 * and rain, snow, a bolt or fog lines under it. One view per icon; {@link #setSky} picks the picture.
 */
public class ZenWeatherIcon extends View {

    private static final int SUN = 0xFFFFC94D;
    private static final int MOON = 0xFFE6EBF5;
    private static final int CLOUD = 0xFFF2F4F8;
    private static final int CLOUD_DARK = 0xFFC3CAD4;
    private static final int CLOUD_BACK = 0xFF9AA3B0;
    private static final int RAIN = 0xFF7DB9FF;
    private static final int FOG = 0xFFD5DAE2;

    /** A cloud filling x 3..21, y 5.5..19: three circles over a flat base, filled as one shape. */
    private static final Path CLOUD_SHAPE = new Path();
    private static final Path BOLT = new Path();
    private static final float[] DROP_X = {8.5f, 12.5f, 16.5f};

    static {
        CLOUD_SHAPE.addCircle(7.5f, 14.5f, 4.5f, Path.Direction.CW);
        CLOUD_SHAPE.addCircle(12.5f, 11f, 5.5f, Path.Direction.CW);
        CLOUD_SHAPE.addCircle(17f, 15f, 4f, Path.Direction.CW);
        CLOUD_SHAPE.addRect(7.5f, 14.5f, 17f, 19f, Path.Direction.CW);
        BOLT.moveTo(13.6f, 14.2f);
        BOLT.lineTo(9.6f, 19.2f);
        BOLT.lineTo(12.1f, 19.2f);
        BOLT.lineTo(10.6f, 23.2f);
        BOLT.lineTo(15.4f, 17.4f);
        BOLT.lineTo(12.8f, 17.4f);
        BOLT.lineTo(14.4f, 14.2f);
        BOLT.close();
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path moon = new Path();
    private final Path moonCut = new Path();
    private ZenWeather.Sky sky = ZenWeather.Sky.CLEAR;
    private boolean daytime = true;

    public ZenWeatherIcon(Context context) {
        this(context, null);
    }

    public ZenWeatherIcon(Context context, AttributeSet attrs) {
        super(context, attrs);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    void setSky(ZenWeather.Sky sky, boolean daytime) {
        if (sky == this.sky && daytime == this.daytime) return;
        this.sky = sky;
        this.daytime = daytime;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom());
        if (size <= 0) return;
        canvas.save();
        canvas.translate(getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - size) / 2f,
                getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        switch (sky) {
            case CLEAR:
                light(canvas, 12f, 12f, 5f);
                break;
            case MOSTLY_CLEAR:
                light(canvas, 10.5f, 10.5f, 4.4f);
                cloud(canvas, 0.5f, 5f, 5f, CLOUD);
                break;
            case PARTLY_CLOUDY:
                light(canvas, 8.6f, 8.6f, 3.6f);
                cloud(canvas, 0.74f, 2.5f, 3f, CLOUD);
                break;
            case CLOUDY:
                cloud(canvas, 0.62f, 4.2f, -3.6f, CLOUD_BACK);
                cloud(canvas, 0.86f, -1f, 1.6f, CLOUD);
                break;
            case FOG:
                fog(canvas);
                break;
            case DRIZZLE:
                cloud(canvas, 0.8f, 0f, -3f, CLOUD_DARK);
                drops(canvas, 1.6f);
                break;
            case SNOW:
                cloud(canvas, 0.8f, 0f, -3f, CLOUD_DARK);
                flakes(canvas);
                break;
            case STORM:
            case HAIL:
                cloud(canvas, 0.8f, 0f, -3.4f, CLOUD_BACK);
                fill.setColor(SUN);
                canvas.drawPath(BOLT, fill);
                break;
            default:   // LIGHT_RAIN, RAIN, HEAVY_RAIN
                cloud(canvas, 0.8f, 0f, -3f, CLOUD_DARK);
                drops(canvas, 3.4f);
                break;
        }
        canvas.restore();
    }

    /** The sun with eight rays by day, a crescent moon by night, centred on (cx, cy). */
    private void light(Canvas canvas, float cx, float cy, float r) {
        if (daytime) {
            fill.setColor(SUN);
            canvas.drawCircle(cx, cy, r, fill);
            line.setColor(SUN);
            line.setStrokeWidth(1.6f);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                canvas.drawLine(cx + cos * (r + 1.8f), cy + sin * (r + 1.8f), cx + cos * (r + 3.4f), cy + sin * (r + 3.4f), line);
            }
        } else {
            moon.reset();
            moon.addCircle(cx, cy, r + 1.4f, Path.Direction.CW);
            moonCut.reset();
            moonCut.addCircle(cx + r * 0.7f, cy - r * 0.55f, r + 0.6f, Path.Direction.CW);
            moon.op(moonCut, Path.Op.DIFFERENCE);
            fill.setColor(MOON);
            canvas.drawPath(moon, fill);
        }
    }

    /** The cloud shape scaled about the centre of the grid and moved by (dx, dy). */
    private void cloud(Canvas canvas, float scale, float dx, float dy, int color) {
        canvas.save();
        canvas.translate(12f + dx, 12f + dy);
        canvas.scale(scale, scale);
        canvas.translate(-12f, -12f);
        fill.setColor(color);
        canvas.drawPath(CLOUD_SHAPE, fill);
        canvas.restore();
    }

    /** Three slanted drops under the cloud; drizzle draws them short. */
    private void drops(Canvas canvas, float length) {
        line.setColor(RAIN);
        line.setStrokeWidth(1.7f);
        for (float x : DROP_X) {
            float top = x == 12.5f ? 17.4f : 16.4f;
            canvas.drawLine(x, top, x - length * 0.35f, top + length, line);
        }
    }

    private void flakes(Canvas canvas) {
        fill.setColor(CLOUD);
        canvas.drawCircle(8.5f, 17.4f, 1.2f, fill);
        canvas.drawCircle(12.5f, 19.6f, 1.2f, fill);
        canvas.drawCircle(16.5f, 17.4f, 1.2f, fill);
        canvas.drawCircle(10.5f, 22f, 1.2f, fill);
        canvas.drawCircle(14.5f, 22f, 1.2f, fill);
    }

    private void fog(Canvas canvas) {
        line.setColor(FOG);
        line.setStrokeWidth(2f);
        canvas.drawLine(5f, 8f, 19f, 8f, line);
        canvas.drawLine(3.5f, 12f, 17.5f, 12f, line);
        canvas.drawLine(6.5f, 16f, 20.5f, 16f, line);
        canvas.drawLine(4.5f, 20f, 15f, 20f, line);
    }
}
