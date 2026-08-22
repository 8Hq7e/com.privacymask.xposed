package com.privacymask.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline coordinate picker: draws a latitude/longitude graticule plus a handful of reference
 * cities for orientation, and lets the user drag a pin to set the fake latitude/longitude
 * directly instead of typing numbers.
 *
 * There is no map-tile dependency and no API key here on purpose — this module ships with no
 * internet permission and no Google Play services / Maps SDK requirement. It's plain Canvas
 * drawing plus touch handling, so it works completely offline and keeps the picker on the same
 * screen as everything else (no separate Activity/Fragment).
 *
 * Coordinates are cropped to latMin/latMax so the view isn't mostly empty polar space; every
 * city listed below fits inside that range.
 */
public class WorldMapView extends View {

    public interface OnLocationChangeListener {
        /** Fired continuously while the finger is moving, for live numeric-field feedback. */
        void onLocationPreview(double lat, double lng);
        /** Fired once when the finger lifts — the point to actually persist the change. */
        void onLocationCommitted(double lat, double lng);
    }

    private static final double LAT_MAX = 75;
    private static final double LAT_MIN = -60;
    private static final double LON_MIN = -180;
    private static final double LON_MAX = 180;

    private static final class City {
        final String name;
        final double lat, lng;
        City(String name, double lat, double lng) { this.name = name; this.lat = lat; this.lng = lng; }
    }

    // Reference points only, for orientation — this view draws no coastlines.
    private final List<City> cities = new ArrayList<>();
    {
        cities.add(new City("Reykjavik", 64.15, -21.94));
        cities.add(new City("London", 51.51, -0.13));
        cities.add(new City("Berlin", 52.52, 13.40));
        cities.add(new City("Moscow", 55.75, 37.62));
        cities.add(new City("Cairo", 30.04, 31.24));
        cities.add(new City("Lagos", 6.52, 3.38));
        cities.add(new City("Nairobi", -1.29, 36.82));
        cities.add(new City("Cape Town", -33.92, 18.42));
        cities.add(new City("Dubai", 25.20, 55.27));
        cities.add(new City("New Delhi", 28.61, 77.21));
        cities.add(new City("Beijing", 39.90, 116.40));
        cities.add(new City("Tokyo", 35.68, 139.69));
        cities.add(new City("Jakarta", -6.21, 106.85));
        cities.add(new City("Sydney", -33.87, 151.21));
        cities.add(new City("New York", 40.71, -74.01));
        cities.add(new City("Mexico City", 19.43, -99.13));
        cities.add(new City("Sao Paulo", -23.55, -46.63));
        cities.add(new City("Buenos Aires", -34.60, -58.38));
        cities.add(new City("Los Angeles", 34.05, -118.24));
        cities.add(new City("Toronto", 43.65, -79.38));
    }

    private double lat = 52.5, lng = 13.4;
    private OnLocationChangeListener listener;

    private final Paint bgPaint = new Paint();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint equatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cityDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cityLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WorldMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bgPaint.setColor(Color.parseColor("#E3F2FD"));
        gridPaint.setColor(Color.parseColor("#90CAF9"));
        gridPaint.setStrokeWidth(1f);
        equatorPaint.setColor(Color.parseColor("#1565C0"));
        equatorPaint.setStrokeWidth(2f);
        borderPaint.setColor(Color.parseColor("#1565C0"));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);
        cityDotPaint.setColor(Color.parseColor("#546E7A"));
        cityLabelPaint.setColor(Color.parseColor("#37474F"));
        cityLabelPaint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
        pinFillPaint.setColor(Color.parseColor("#D32F2F"));
        pinRingPaint.setColor(Color.parseColor("#D32F2F"));
        pinRingPaint.setStyle(Paint.Style.STROKE);
        pinRingPaint.setStrokeWidth(3f);
        setClickable(true);
    }

    public void setOnLocationChangeListener(OnLocationChangeListener l) {
        listener = l;
    }

    /** Moves the pin programmatically (initial load, or after the numeric fields were hand-edited). Doesn't fire the listener. */
    public void setLocation(double newLat, double newLng) {
        lat = clampLat(newLat);
        lng = clampLon(newLng);
        invalidate();
    }

    private double clampLat(double v) { return Math.max(LAT_MIN, Math.min(LAT_MAX, v)); }
    private double clampLon(double v) { return Math.max(LON_MIN, Math.min(LON_MAX, v)); }

    private float xForLon(double lon) { return (float) ((lon - LON_MIN) / (LON_MAX - LON_MIN) * getWidth()); }
    private float yForLat(double la) { return (float) ((LAT_MAX - la) / (LAT_MAX - LAT_MIN) * getHeight()); }
    private double lonForX(float x) { return LON_MIN + (x / getWidth()) * (LON_MAX - LON_MIN); }
    private double latForY(float y) { return LAT_MAX - (y / getHeight()) * (LAT_MAX - LAT_MIN); }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        canvas.drawRect(0, 0, w, h, bgPaint);

        for (double lo = LON_MIN; lo <= LON_MAX; lo += 30) {
            float x = xForLon(lo);
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (double la = Math.ceil(LAT_MIN / 30.0) * 30; la <= LAT_MAX; la += 30) {
            if (la == 0) continue; // drawn heavier below
            float y = yForLat(la);
            canvas.drawLine(0, y, w, y, gridPaint);
        }
        canvas.drawLine(0, yForLat(0), w, yForLat(0), equatorPaint);

        for (City c : cities) {
            float cx = xForLon(c.lng), cy = yForLat(c.lat);
            canvas.drawCircle(cx, cy, 4f, cityDotPaint);
            canvas.drawText(c.name, cx + 6, cy - 6, cityLabelPaint);
        }

        canvas.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, borderPaint);

        float px = xForLon(lng), py = yForLat(lat);
        canvas.drawCircle(px, py, 14f, pinRingPaint);
        canvas.drawCircle(px, py, 6f, pinFillPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        float x = Math.min(Math.max(event.getX(), 0), getWidth());
        float y = Math.min(Math.max(event.getY(), 0), getHeight());
        double newLat = clampLat(latForY(y));
        double newLng = clampLon(lonForX(x));

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                lat = newLat;
                lng = newLng;
                invalidate();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (listener != null) listener.onLocationPreview(lat, lng);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lat = newLat;
                lng = newLng;
                invalidate();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                if (listener != null) listener.onLocationCommitted(lat, lng);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
