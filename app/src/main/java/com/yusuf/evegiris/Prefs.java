package com.yusuf.evegiris;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Prefs {
    private static final String NAME = "yusuf_eve_giris";

    public static SharedPreferences p(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String host(Context c) { return p(c).getString("mqtt_host", "192.168.7.129"); }
    public static int port(Context c) { return p(c).getInt("mqtt_port", 1883); }
    public static String user(Context c) { return p(c).getString("mqtt_user", ""); }
    public static String pass(Context c) { return p(c).getString("mqtt_pass", ""); }
    public static String topic(Context c) { return p(c).getString("mqtt_topic", "ev/telefon/yusuf/eve_geldi"); }
    public static String ssid(Context c) { return p(c).getString("home_ssid", "Yusuf CAKIR Asus"); }
    public static String carBtName(Context c) { return p(c).getString("car_bt_name", "Ysf Golf6"); }
    public static String carBtAddress(Context c) { return p(c).getString("car_bt_address", ""); }
    public static float radius(Context c) { return p(c).getFloat("home_radius", 150f); }
    public static boolean homeSet(Context c) { return p(c).getBoolean("home_set", false); }
    public static double homeLat(Context c) { return Double.longBitsToDouble(p(c).getLong("home_lat", Double.doubleToLongBits(0))); }
    public static double homeLon(Context c) { return Double.longBitsToDouble(p(c).getLong("home_lon", Double.doubleToLongBits(0))); }
    public static String homeLabel(Context c) {
        return p(c).getString("home_label", "Karşıyaka, QF2W+VJ, 49100 Ağaçlık/Muş Merkez/Muş, Türkiye");
    }
    public static long lastTrigger(Context c) { return p(c).getLong("last_trigger_ms", 0L); }
    public static boolean serviceEnabled(Context c) { return p(c).getBoolean("service_enabled", false); }
    public static String lastStatus(Context c) { return p(c).getString("last_status", "Henüz olay yok"); }

    // V6 zaman / güvenlik varsayılanları (dakika)
    public static final int DEF_WIFI_AWAY_MIN = 10;
    public static final int DEF_CHARGE_TIMEOUT_MIN = 15;
    public static final int DEF_CANDIDATE_TIMEOUT_MIN = 5;
    public static final int DEF_STARTUP_GRACE_MIN = 5;
    public static final int DEF_COOLDOWN_MIN = 60;

    public static int wifiAwayMinutes(Context c) {
        return clamp(p(c).getInt("cfg_wifi_away_min", DEF_WIFI_AWAY_MIN), 1, 120);
    }
    public static int chargeTimeoutMinutes(Context c) {
        return clamp(p(c).getInt("cfg_charge_timeout_min", DEF_CHARGE_TIMEOUT_MIN), 1, 120);
    }
    public static int candidateTimeoutMinutes(Context c) {
        return clamp(p(c).getInt("cfg_candidate_timeout_min", DEF_CANDIDATE_TIMEOUT_MIN), 1, 60);
    }
    public static int startupGraceMinutes(Context c) {
        return clamp(p(c).getInt("cfg_startup_grace_min", DEF_STARTUP_GRACE_MIN), 0, 30);
    }
    public static int cooldownMinutes(Context c) {
        return clamp(p(c).getInt("cfg_cooldown_min", DEF_COOLDOWN_MIN), 0, 240);
    }

    public static long wifiAwayMs(Context c) { return wifiAwayMinutes(c) * 60_000L; }
    public static long chargeTimeoutMs(Context c) { return chargeTimeoutMinutes(c) * 60_000L; }
    public static long candidateTimeoutMs(Context c) { return candidateTimeoutMinutes(c) * 60_000L; }
    public static long startupGraceMs(Context c) { return startupGraceMinutes(c) * 60_000L; }
    public static long cooldownMs(Context c) { return cooldownMinutes(c) * 60_000L; }

    public static void saveTimingSettings(Context c, int wifiAwayMin, int chargeTimeoutMin,
                                          int candidateTimeoutMin, int startupGraceMin,
                                          int cooldownMin) {
        p(c).edit()
                .putInt("cfg_wifi_away_min", clamp(wifiAwayMin, 1, 120))
                .putInt("cfg_charge_timeout_min", clamp(chargeTimeoutMin, 1, 120))
                .putInt("cfg_candidate_timeout_min", clamp(candidateTimeoutMin, 1, 60))
                .putInt("cfg_startup_grace_min", clamp(startupGraceMin, 0, 30))
                .putInt("cfg_cooldown_min", clamp(cooldownMin, 0, 240))
                .apply();
    }

    public static void resetTimingSettings(Context c) {
        saveTimingSettings(c,
                DEF_WIFI_AWAY_MIN,
                DEF_CHARGE_TIMEOUT_MIN,
                DEF_CANDIDATE_TIMEOUT_MIN,
                DEF_STARTUP_GRACE_MIN,
                DEF_COOLDOWN_MIN);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static boolean lastLocationSet(Context c) { return p(c).getBoolean("last_location_set", false); }
    public static double lastLat(Context c) { return Double.longBitsToDouble(p(c).getLong("last_lat", Double.doubleToLongBits(0))); }
    public static double lastLon(Context c) { return Double.longBitsToDouble(p(c).getLong("last_lon", Double.doubleToLongBits(0))); }

    public static void status(Context c, String s) {
        p(c).edit().putString("last_status", System.currentTimeMillis() + "|" + s).apply();
    }

    public static synchronized void addEvent(Context c, String stage, String detail) {
        SharedPreferences sp = p(c);
        String old = sp.getString("event_log", "");
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = stamp + " | " + (stage == null ? "" : stage)
                + (detail == null || detail.trim().isEmpty() ? "" : " | " + detail.trim());

        String merged = line + (old == null || old.isEmpty() ? "" : "\n" + old);
        String[] lines = merged.split("\\n");
        StringBuilder keep = new StringBuilder();
        int max = Math.min(lines.length, 40);
        for (int i = 0; i < max; i++) {
            if (i > 0) keep.append("\n");
            keep.append(lines[i]);
        }
        sp.edit().putString("event_log", keep.toString()).apply();
    }

    public static String eventLog(Context c) {
        return p(c).getString("event_log", "Henüz olay kaydı yok.");
    }

    public static void clearEventLog(Context c) {
        p(c).edit().remove("event_log").apply();
    }

    public static void saveHome(Context c, double lat, double lon) {
        saveHome(c, lat, lon, homeLabel(c));
    }

    public static void saveHome(Context c, double lat, double lon, String label) {
        p(c).edit()
                .putBoolean("home_set", true)
                .putLong("home_lat", Double.doubleToRawLongBits(lat))
                .putLong("home_lon", Double.doubleToRawLongBits(lon))
                .putString("home_label", label == null || label.trim().isEmpty() ? "Haritadan seçildi" : label.trim())
                .apply();
    }

    public static void saveLastLocation(Context c, Location loc) {
        if (loc == null) return;
        p(c).edit()
                .putBoolean("last_location_set", true)
                .putLong("last_lat", Double.doubleToRawLongBits(loc.getLatitude()))
                .putLong("last_lon", Double.doubleToRawLongBits(loc.getLongitude()))
                .putFloat("last_accuracy", loc.getAccuracy())
                .putLong("last_location_ms", System.currentTimeMillis())
                .apply();
    }

    private Prefs() {}
}
