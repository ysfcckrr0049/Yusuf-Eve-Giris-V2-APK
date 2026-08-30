package com.yusuf.evegiris;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private EditText host, port, user, pass, topic, ssid, carBtName, radius;
    private TextView homeText, mapInfo, stageText, treeText, statusText, eventLogText;
    private LinearLayout visualFlow;
    private MapView map;
    private Marker homeMarker, currentMarker, selectedMarker;
    private Polygon homeCircle;
    private GeoPoint selectedPoint;
    private boolean firstMapCenter = true;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        Configuration.getInstance().load(getApplicationContext(), getSharedPreferences("osmdroid", MODE_PRIVATE));
        buildUi();
        load();
        requestBasePermissions();
        refreshMapFromPrefs(true);
        refreshStatus();
    }

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(18f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(18), 0, dp(8));
        return v;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("YUSUF EVE GİRİŞ V6");
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Tailscale L3 + MQTT\nHaritalı ev alanı + Ysf Golf6 + Wi‑Fi + Android Auto + şarj\nV6: sert zaman aşımı iptali + ayrı Zaman/Güvenlik Ayarları");
        root.addView(sub);

        root.addView(label("Ev Alanı Haritası"));
        mapInfo = new TextView(this);
        mapInfo.setText("Haritada uzun bas: yeni ev noktası seçilir. Mavi daire ev alanını gösterir.");
        mapInfo.setPadding(0, 0, 0, dp(8));
        root.addView(mapInfo);

        map = new MapView(this);
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false);
        map.getController().setZoom(18.0);
        LinearLayout.LayoutParams mapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360));
        root.addView(map, mapLp);

        MapEventsOverlay events = new MapEventsOverlay(new MapEventsReceiver() {
            @Override public boolean singleTapConfirmedHelper(GeoPoint p) { return false; }
            @Override public boolean longPressHelper(GeoPoint p) {
                selectedPoint = new GeoPoint(p.getLatitude(), p.getLongitude());
                updateMapOverlays(false);
                mapInfo.setText("Seçilen nokta: " + coord(selectedPoint) + "\nKaydetmek için HARİTADAKİ PİNİ EV YAP düğmesine bas.");
                return true;
            }
        });
        map.getOverlays().add(events);

        root.addView(button("HARİTADAKİ PİNİ EV YAP", v -> saveSelectedAsHome()));
        root.addView(button("ŞU ANKİ KONUMU EV YAP", v -> setCurrentAsHome()));
        root.addView(button("HARİTAYI EV KONUMUNA ORTALA", v -> centerHome()));

        root.addView(label("MQTT Sunucu"));
        host = edit("192.168.7.129"); root.addView(host);
        port = edit("1883"); port.setInputType(InputType.TYPE_CLASS_NUMBER); root.addView(port);
        user = edit("MQTT kullanıcı adı"); root.addView(user);
        pass = edit("MQTT parola"); pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(pass);
        topic = edit("ev/telefon/yusuf/eve_geldi"); root.addView(topic);

        root.addView(label("Ev Wi‑Fi"));
        ssid = edit("Yusuf CAKIR Asus"); root.addView(ssid);

        root.addView(label("Araç Bluetooth"));
        carBtName = edit("Ysf Golf6"); root.addView(carBtName);

        root.addView(label("Ev yarıçapı (metre)"));
        radius = edit("150");
        radius.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(radius);
        radius.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateMapOverlays(false); }
            @Override public void afterTextChanged(Editable s) {}
        });

        homeText = new TextView(this);
        homeText.setPadding(0, dp(16), 0, dp(8));
        root.addView(homeText);

        root.addView(button("AYARLARI KAYDET", v -> {
            save();
            updateMapOverlays(false);
            toast("Ayarlar kaydedildi");
            refreshStatus();
        }));
        root.addView(button("ZAMAN AŞIMI / GÜVENLİK AYARLARI", v -> startActivity(new Intent(this, TimingSettingsActivity.class))));
        root.addView(button("ARKA PLAN KONUM İZNİ AYARLARI", v -> openAppSettings()));
        root.addView(button("MQTT / TAILSCALE BAĞLANTI TESTİ", v -> testMqtt()));
        root.addView(button("İZLEMEYİ BAŞLAT", v -> startMonitor()));
        root.addView(button("KONUMU ŞİMDİ TAZELE (YÜKSEK DOĞRULUK)", v -> sendServiceAction(MonitorService.ACTION_FORCE_LOCATION)));
        root.addView(button("İZLEMEYİ DURDUR", v -> stopMonitor()));
        root.addView(button("ORTAK TETİK KİLİDİNİ SIFIRLA", v -> {
            Prefs.p(this).edit().putLong("last_trigger_ms", 0L).apply();
            Prefs.status(this, "Ortak tetik kilidi manuel sifirlandi");
            Prefs.addEvent(this, "Kilit sıfırlandı", "Ortak tetik kilidi manuel sıfırlandı.");
            Prefs.p(this).edit().putString("flow_stage", "Kilit sıfırlandı")
                    .putString("flow_detail", "Ortak tetik kilidi manuel sıfırlandı.")
                    .putLong("flow_stage_ms", System.currentTimeMillis()).apply();
            refreshStatus();
        }));

        root.addView(label("Sahte Test Butonları (kuru çalışma)"));
        TextView fakeInfo = new TextView(this);
        fakeInfo.setText("Bu butonlar GERÇEK MQTT göndermez. Sadece algoritmanın hangi aşamada ne yapacağını görselleştirir.");
        root.addView(fakeInfo);
        root.addView(button("TEST: Evden ayrıldı", v -> sendServiceAction(MonitorService.ACTION_TEST_HOME_LEAVE)));
        root.addView(button("TEST: Wi‑Fi ayrıldı / 10 dk sayaç başladı", v -> sendServiceAction(MonitorService.ACTION_TEST_WIFI_AWAY)));
        root.addView(button("TEST: Golf6 bağlandı", v -> sendServiceAction(MonitorService.ACTION_TEST_BT_CONNECTED)));
        root.addView(button("TEST: Golf6 bağlantısı kesildi", v -> sendServiceAction(MonitorService.ACTION_TEST_BT)));
        root.addView(button("TEST: Eve giriş oldu", v -> sendServiceAction(MonitorService.ACTION_TEST_HOME_ENTER)));
        root.addView(button("TEST: Wi‑Fi 10 dk tamam + geri bağlandı", v -> sendServiceAction(MonitorService.ACTION_TEST_WIFI_READY)));
        root.addView(button("TEST: Android Auto kapandı", v -> sendServiceAction(MonitorService.ACTION_TEST_AA)));
        root.addView(button("TEST: Eve şarjda geldi", v -> sendServiceAction(MonitorService.ACTION_TEST_CHARGE_ARRIVAL)));
        root.addView(button("TEST: Şarjdan çıktı", v -> sendServiceAction(MonitorService.ACTION_TEST_CHARGE)));
        root.addView(button("TEST: TAM AKIŞ / MQTT YOK", v -> sendServiceAction(MonitorService.ACTION_TEST_FULL)));
        root.addView(button("TEST: ŞARJ ZAMAN AŞIMI / MQTT YOK", v -> sendServiceAction(MonitorService.ACTION_TEST_CHARGE_TIMEOUT)));
        root.addView(button("TEST: GENEL ADAY ZAMAN AŞIMI / MQTT YOK", v -> sendServiceAction(MonitorService.ACTION_TEST_CANDIDATE_TIMEOUT)));

        root.addView(label("Şu an ne yapıyor?"));
        stageText = new TextView(this);
        stageText.setTypeface(Typeface.MONOSPACE);
        stageText.setTextSize(14f);
        root.addView(stageText);

        root.addView(label("Canlı Algoritma Görseli"));
        TextView visualHint = new TextView(this);
        visualHint.setText("YEŞİL=tamam • SARI=şu an beklenen aşama • KIRMIZI=engelleyici şart • GRİ=henüz gelinmedi");
        visualHint.setPadding(0, 0, 0, dp(8));
        root.addView(visualHint);
        visualFlow = new LinearLayout(this);
        visualFlow.setOrientation(LinearLayout.VERTICAL);
        root.addView(visualFlow);

        root.addView(label("Algoritma Ağacı"));
        treeText = new TextView(this);
        treeText.setTypeface(Typeface.MONOSPACE);
        treeText.setTextSize(13f);
        root.addView(treeText);

        root.addView(label("Olay Günlüğü (son 40 olay)"));
        eventLogText = new TextView(this);
        eventLogText.setTypeface(Typeface.MONOSPACE);
        eventLogText.setTextSize(12f);
        eventLogText.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(eventLogText);
        root.addView(button("OLAY GÜNLÜĞÜNÜ TEMİZLE", v -> {
            Prefs.clearEventLog(this);
            Prefs.addEvent(this, "Günlük temizlendi", "Kullanıcı olay günlüğünü temizledi.");
            refreshStatus();
        }));

        root.addView(label("Canlı Durum / Ham Veri"));
        statusText = new TextView(this);
        statusText.setTextSize(14f);
        statusText.setPadding(0, 0, 0, dp(40));
        root.addView(statusText);

        setContentView(scroll);
    }

    private void sendServiceAction(String action) {
        save();
        Intent s = new Intent(this, MonitorService.class);
        s.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        toast("Sahte test çalıştırıldı: " + action);
        refreshStatus();
    }

    private String coord(GeoPoint p) {
        return String.format(Locale.US, "%.6f, %.6f", p.getLatitude(), p.getLongitude());
    }

    private void load() {
        host.setText(Prefs.host(this));
        port.setText(String.valueOf(Prefs.port(this)));
        user.setText(Prefs.user(this));
        pass.setText(Prefs.pass(this));
        topic.setText(Prefs.topic(this));
        ssid.setText(Prefs.ssid(this));
        carBtName.setText(Prefs.carBtName(this));
        radius.setText(String.valueOf((int) Prefs.radius(this)));
        if (Prefs.homeSet(this)) selectedPoint = new GeoPoint(Prefs.homeLat(this), Prefs.homeLon(this));
        updateHomeText();
    }

    private float radiusFromUi() {
        try { return Math.max(30f, Float.parseFloat(radius.getText().toString().trim())); }
        catch (Exception e) { return Prefs.radius(this); }
    }

    private void save() {
        int p;
        float r;
        try { p = Integer.parseInt(port.getText().toString().trim()); } catch (Exception e) { p = 1883; }
        r = radiusFromUi();
        Prefs.p(this).edit()
                .putString("mqtt_host", host.getText().toString().trim())
                .putInt("mqtt_port", p)
                .putString("mqtt_user", user.getText().toString())
                .putString("mqtt_pass", pass.getText().toString())
                .putString("mqtt_topic", topic.getText().toString().trim())
                .putString("home_ssid", ssid.getText().toString().trim())
                .putString("car_bt_name", carBtName.getText().toString().trim())
                .putFloat("home_radius", r)
                .apply();
    }

    private void updateHomeText() {
        if (Prefs.homeSet(this)) {
            homeText.setText("Ev konumu: " + String.format(Locale.US, "%.6f, %.6f", Prefs.homeLat(this), Prefs.homeLon(this))
                    + "\nAdres etiketi: " + Prefs.homeLabel(this)
                    + "\nEv alanı yarıçapı: " + Math.round(Prefs.radius(this)) + " m");
        } else {
            homeText.setText("Ev konumu henüz kaydedilmedi. Haritada uzun bas veya ŞU ANKİ KONUMU EV YAP düğmesini kullan.");
        }
    }

    private void refreshMapFromPrefs(boolean center) {
        if (Prefs.homeSet(this) && selectedPoint == null) {
            selectedPoint = new GeoPoint(Prefs.homeLat(this), Prefs.homeLon(this));
        }
        updateMapOverlays(center);
    }

    private void updateMapOverlays(boolean center) {
        if (map == null) return;

        if (homeMarker != null) map.getOverlays().remove(homeMarker);
        if (currentMarker != null) map.getOverlays().remove(currentMarker);
        if (selectedMarker != null) map.getOverlays().remove(selectedMarker);
        if (homeCircle != null) map.getOverlays().remove(homeCircle);

        GeoPoint home = Prefs.homeSet(this) ? new GeoPoint(Prefs.homeLat(this), Prefs.homeLon(this)) : null;
        GeoPoint current = null;
        if (Prefs.lastLocationSet(this)) current = new GeoPoint(Prefs.lastLat(this), Prefs.lastLon(this));
        else current = bestLastKnownPoint();

        if (home != null) {
            homeCircle = new Polygon(map);
            homeCircle.setPoints(Polygon.pointsAsCircle(home, radiusFromUi()));
            homeCircle.getFillPaint().setColor(0x332196F3);
            homeCircle.getOutlinePaint().setColor(0xCC1565C0);
            homeCircle.getOutlinePaint().setStrokeWidth(4f);
            homeCircle.setTitle("Ev alanı - " + Math.round(radiusFromUi()) + " m");
            map.getOverlays().add(homeCircle);

            homeMarker = new Marker(map);
            homeMarker.setPosition(home);
            homeMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            homeMarker.setTitle("EV");
            homeMarker.setSnippet("Kayıtlı ev merkezi");
            map.getOverlays().add(homeMarker);
        }

        if (selectedPoint != null && (home == null || selectedPoint.distanceToAsDouble(home) > 1.0)) {
            selectedMarker = new Marker(map);
            selectedMarker.setPosition(selectedPoint);
            selectedMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            selectedMarker.setTitle("SEÇİLEN EV NOKTASI");
            selectedMarker.setSnippet("HARİTADAKİ PİNİ EV YAP ile kaydet");
            map.getOverlays().add(selectedMarker);
        }

        if (current != null) {
            currentMarker = new Marker(map);
            currentMarker.setPosition(current);
            currentMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            currentMarker.setTitle("TELEFON");
            currentMarker.setSnippet("Son bilinen konum");
            map.getOverlays().add(currentMarker);
        }

        GeoPoint target = home != null ? home : (selectedPoint != null ? selectedPoint : current);
        if (center && target != null) {
            map.getController().setCenter(target);
            map.getController().setZoom(18.0);
            firstMapCenter = false;
        } else if (firstMapCenter && target != null) {
            map.getController().setCenter(target);
            map.getController().setZoom(18.0);
            firstMapCenter = false;
        }
        map.invalidate();
    }

    private GeoPoint bestLastKnownPoint() {
        if (!hasLocation()) return null;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                Location x = lm.getLastKnownLocation(provider);
                if (x != null && (best == null || x.getAccuracy() < best.getAccuracy())) best = x;
            }
        } catch (SecurityException ignored) {}
        if (best == null) return null;
        Prefs.saveLastLocation(this, best);
        return new GeoPoint(best.getLatitude(), best.getLongitude());
    }

    private void centerHome() {
        if (!Prefs.homeSet(this)) { toast("Ev konumu kayıtlı değil"); return; }
        GeoPoint h = new GeoPoint(Prefs.homeLat(this), Prefs.homeLon(this));
        map.getController().animateTo(h);
        map.getController().setZoom(18.0);
    }

    private void saveSelectedAsHome() {
        save();
        if (selectedPoint == null) {
            toast("Önce haritada ev noktasına uzun bas");
            return;
        }
        Prefs.saveHome(this, selectedPoint.getLatitude(), selectedPoint.getLongitude(), "Haritadan seçildi");
        Prefs.status(this, "Ev konumu haritadan kaydedildi: " + coord(selectedPoint));
        updateHomeText();
        updateMapOverlays(true);
        toast("Haritadaki nokta ev olarak kaydedildi");
    }

    private void requestBasePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 100);
    }

    private boolean hasLocation() {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void setCurrentAsHome() {
        save();
        if (!hasLocation()) {
            requestBasePermissions();
            toast("Konum izni gerekli");
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                Location x = lm.getLastKnownLocation(provider);
                if (x != null && (best == null || x.getAccuracy() < best.getAccuracy())) best = x;
            }
        } catch (SecurityException ignored) {}
        if (best != null && System.currentTimeMillis() - best.getTime() < 5 * 60_000L) {
            saveHome(best);
            return;
        }
        try {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, new android.location.LocationListener() {
                @Override public void onLocationChanged(Location location) { saveHome(location); }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            }, Looper.getMainLooper());
            toast("GPS konumu bekleniyor...");
        } catch (Exception e) {
            toast("Konum alınamadı: " + e.getMessage());
        }
    }

    private void saveHome(Location loc) {
        Prefs.saveLastLocation(this, loc);
        selectedPoint = new GeoPoint(loc.getLatitude(), loc.getLongitude());
        Prefs.saveHome(this, loc.getLatitude(), loc.getLongitude(), "Telefonun GPS konumundan seçildi");
        updateHomeText();
        updateMapOverlays(true);
        Prefs.status(this, "Ev konumu kaydedildi, doğruluk=" + Math.round(loc.getAccuracy()) + "m");
        toast("Ev konumu kaydedildi");
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(i);
        toast("İzinler > Konum > Her zaman izin ver seçeneğini aç");
    }

    private void testMqtt() {
        save();
        statusText.setText("MQTT bağlantısı test ediliyor...\nTailscale açık olmalı.");
        new Thread(() -> {
            try {
                MqttPublisher.test(Prefs.host(this), Prefs.port(this), Prefs.user(this), Prefs.pass(this));
                runOnUiThread(() -> {
                    Prefs.status(this, "MQTT baglanti testi BASARILI");
                    Prefs.p(this).edit().putString("flow_stage", "MQTT test başarılı")
                            .putString("flow_detail", "Broker erişilebilir. Tailscale / MQTT bağlantısı çalışıyor.")
                            .putLong("flow_stage_ms", System.currentTimeMillis()).apply();
                    toast("MQTT bağlantısı başarılı");
                    refreshStatus();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Prefs.status(this, "MQTT test HATA: " + e.getMessage());
                    Prefs.p(this).edit().putString("flow_stage", "MQTT test hatası")
                            .putString("flow_detail", e.getMessage())
                            .putLong("flow_stage_ms", System.currentTimeMillis()).apply();
                    toast("MQTT hata: " + e.getMessage());
                    refreshStatus();
                });
            }
        }, "mqtt-test").start();
    }

    private void startMonitor() {
        save();
        if (!Prefs.homeSet(this)) {
            toast("Önce ev konumunu kaydet");
            return;
        }
        if (!hasLocation()) {
            requestBasePermissions();
            toast("Konum izni gerekli");
            return;
        }
        Prefs.p(this).edit().putBoolean("service_enabled", true).apply();
        Intent s = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        Prefs.status(this, "Izleme servisi baslatildi");
        Prefs.addEvent(this, "İzleme başlatıldı", "Foreground servis başlatıldı.");
        toast("İzleme başladı");
        refreshStatus();
    }

    private void stopMonitor() {
        Prefs.p(this).edit().putBoolean("service_enabled", false).apply();
        stopService(new Intent(this, MonitorService.class));
        Prefs.status(this, "Izleme servisi durduruldu");
        Prefs.addEvent(this, "İzleme durduruldu", "Kullanıcı izleme servisini durdurdu.");
        Prefs.p(this).edit().putString("flow_stage", "Servis durduruldu")
                .putString("flow_detail", "İzleme artık yapılmıyor.")
                .putLong("flow_stage_ms", System.currentTimeMillis()).apply();
        toast("İzleme durdu");
        refreshStatus();
    }

    private String fmtRemain(long ms) {
        if (ms <= 0) return "0 sn";
        long sec = ms / 1000L;
        long h = sec / 3600L;
        long m = (sec % 3600L) / 60L;
        long s = sec % 60L;
        if (h > 0) return h + " sa " + m + " dk " + s + " sn";
        if (m > 0) return m + " dk " + s + " sn";
        return s + " sn";
    }

    private String fmtTs(long ts) {
        if (ts <= 0) return "Yok";
        return DateFormat.getDateTimeInstance().format(new Date(ts));
    }

    private String fmtAgo(long ts) {
        if (ts <= 0) return "Yok";
        return fmtRemain(System.currentTimeMillis() - ts) + " önce";
    }

    private String buildStageSummary(SharedPreferences p) {
        long now = System.currentTimeMillis();
        String stage = p.getString("flow_stage", "Hazır");
        String detail = p.getString("flow_detail", "Henüz olay yok");
        long stageAt = p.getLong("flow_stage_ms", 0L);

        long graceUntil = p.getLong("startup_grace_until_ms", 0L);
        long cooldownRemain = Prefs.lastTrigger(this) <= 0L ? 0L : Math.max(0L, Prefs.cooldownMs(this) - (now - Prefs.lastTrigger(this)));
        long wifiAwaySince = p.getLong("wifi_away_since", 0L);
        long pendingAt = p.getLong("event_pending_ms", 0L);
        String pendingMethod = p.getString("event_pending_method", "");
        boolean pendingDry = p.getBoolean("event_pending_dry_run", false);
        boolean chargePending = p.getBoolean("charge_arrival_pending", false);
        boolean sending = p.getBoolean("mqtt_sending", false);
        boolean insideHome = p.getBoolean("inside_home", false);
        String currentSsid = p.getString("current_ssid", "?");
        boolean carBt = p.getBoolean("car_bt_connected", false);
        String next;

        if (sending) next = "MQTT gönderimi bitmesi bekleniyor.";
        else if (now < graceUntil) next = "Servis başlangıç korumasının bitmesi bekleniyor.";
        else if (cooldownRemain > 0) next = Prefs.cooldownMinutes(this) + " dk ortak tetik kilidinin bitmesi bekleniyor.";
        else if (!insideHome) next = "Telefonun ev alanına girmesi bekleniyor.";
        else if (chargePending) next = "Şarjdan çıkma bekleniyor; süre dolarsa yöntem iptal edilecek.";
        else if (wifiAwaySince > 0 && !Prefs.ssid(this).equals(currentSsid)) next = "Ev Wi‑Fi’dan en az " + Prefs.wifiAwayMinutes(this) + " dk uzak kalıp tekrar bağlanma bekleniyor.";
        else if (!carBt) next = "Golf6 ayrılması olduysa konum teyit edilir; yoksa Wi‑Fi / Android Auto / şarj çıkışı izleniyor.";
        else next = "Kaynak olay bekleniyor: Golf6 ayrılması / Wi‑Fi geri gelişi / Android Auto çıkışı / şarjdan çıkma.";

        StringBuilder sb = new StringBuilder();
        sb.append("AKTİF AŞAMA : ").append(stage).append("\n");
        sb.append("AÇIKLAMA    : ").append(detail).append("\n");
        sb.append("AŞAMA ZAMANI: ").append(fmtTs(stageAt)).append(" (").append(fmtAgo(stageAt)).append(")\n\n");
        sb.append("ANLIK BEKLENTİ\n");
        sb.append("- ").append(next).append("\n\n");
        sb.append("GERİ SAYIMLAR\n");
        sb.append("- Servis başlangıç koruması : ").append(now < graceUntil ? fmtRemain(graceUntil - now) + " kaldı" : "yok").append("\n");
        if (wifiAwaySince > 0 && !Prefs.ssid(this).equals(currentSsid)) {
            long elapsed = now - wifiAwaySince;
            long left = Math.max(0L, Prefs.wifiAwayMs(this) - elapsed);
            sb.append("- Wi‑Fi minimum uzaklık      : ").append(Prefs.wifiAwayMinutes(this)).append(" dk | ").append(fmtRemain(elapsed)).append(" geçti, ").append(fmtRemain(left)).append(" kaldı\n");
        } else {
            sb.append("- Wi‑Fi minimum uzaklık      : ").append(Prefs.wifiAwayMinutes(this)).append(" dk | aktif değil\n");
        }
        if (pendingAt > 0 && pendingMethod != null && !pendingMethod.isEmpty()) {
            long left = Math.max(0L, Prefs.candidateTimeoutMs(this) - (now - pendingAt));
            sb.append("- Genel aday penceresi       : ").append(pendingMethod)
                    .append(pendingDry ? " [TEST]" : "")
                    .append(" için ").append(fmtRemain(left)).append(" kaldı\n");
        } else {
            sb.append("- Genel aday penceresi       : yok (limit ").append(Prefs.candidateTimeoutMinutes(this)).append(" dk)\n");
        }
        long chargeSince = p.getLong("charge_pending_since_ms", 0L);
        long chargeLeft = chargePending && chargeSince > 0L
                ? Math.max(0L, Prefs.chargeTimeoutMs(this) - (now - chargeSince)) : 0L;
        sb.append("- Şarjdan çıkarma penceresi  : ")
                .append(chargePending
                        ? Prefs.chargeTimeoutMinutes(this) + " dk limit | " + fmtRemain(chargeLeft) + " kaldı"
                        : "yok (limit " + Prefs.chargeTimeoutMinutes(this) + " dk)")
                .append("\n");
        sb.append("- Ortak tetik kilidi         : ")
                .append(Prefs.lastTrigger(this) == 0 ? "yok" : fmtRemain(cooldownRemain) + " kaldı")
                .append(" (ayar ").append(Prefs.cooldownMinutes(this)).append(" dk)\n");
        sb.append("- MQTT gönderim durumu       : ").append(sending ? "gönderim sürüyor" : "boşta").append("\n\n");
        sb.append("SON OLAY ZAMANLARI\n");
        sb.append("- Evden çıkış                : ").append(fmtTs(p.getLong("last_home_leave_ms", 0L))).append("\n");
        sb.append("- Eve giriş                  : ").append(fmtTs(p.getLong("last_home_enter_ms", 0L))).append("\n");
        sb.append("- Golf6 bağlandı             : ").append(fmtTs(p.getLong("last_bt_connected_ms", 0L))).append("\n");
        sb.append("- Golf6 ayrıldı              : ").append(fmtTs(p.getLong("last_bt_disconnected_ms", 0L))).append("\n");
        sb.append("- Wi‑Fi ayrıldı              : ").append(fmtTs(p.getLong("last_wifi_left_ms", 0L))).append("\n");
        sb.append("- Wi‑Fi geri bağlandı        : ").append(fmtTs(p.getLong("last_wifi_reconnect_ms", 0L))).append("\n");
        sb.append("- Şarjdan çıkış              : ").append(fmtTs(p.getLong("last_charge_disconnected_ms", 0L))).append("\n");
        return sb.toString();
    }

    private String mark(boolean ok) { return ok ? "✓" : "○"; }

    private String buildFlowTree(SharedPreferences p) {
        long now = System.currentTimeMillis();
        boolean service = Prefs.serviceEnabled(this);
        boolean homeSet = Prefs.homeSet(this);
        boolean inside = p.getBoolean("inside_home", false);
        boolean bt = p.getBoolean("car_bt_connected", false);
        boolean charging = p.getBoolean("charging", false);
        boolean chargePending = p.getBoolean("charge_arrival_pending", false);
        String curSsid = p.getString("current_ssid", "?");
        boolean onHomeWifi = Prefs.ssid(this).equals(curSsid);
        long wifiAwaySince = p.getLong("wifi_away_since", 0L);
        long wifiLeft = wifiAwaySince > 0 && !onHomeWifi ? Math.max(0L, Prefs.wifiAwayMs(this) - (now - wifiAwaySince)) : 0L;
        long graceLeft = Math.max(0L, p.getLong("startup_grace_until_ms", 0L) - now);
        long lockLeft = Prefs.lastTrigger(this) <= 0L ? 0L : Math.max(0L, Prefs.cooldownMs(this) - (now - Prefs.lastTrigger(this)));
        String pendingMethod = p.getString("event_pending_method", "");
        boolean sending = p.getBoolean("mqtt_sending", false);

        StringBuilder sb = new StringBuilder();
        sb.append("YUSUF EVE GİRİŞ ALGORİTMA AĞACI\n");
        sb.append("├─ 1) Servis çalışıyor mu?            ").append(service ? "✓ EVET" : "✗ HAYIR").append("\n");
        sb.append("├─ 2) Ev konumu kayıtlı mı?           ").append(homeSet ? "✓ EVET" : "✗ HAYIR").append("\n");
        sb.append("├─ 3) Telefon ev alanında mı?         ").append(inside ? "✓ EVET" : "○ HAYIR / bekleniyor").append("\n");
        sb.append("│   ├─ Ev Wi‑Fi (hedef SSID)          : ").append(onHomeWifi ? "✓ bağlı" : "○ bağlı değil").append(" [").append(curSsid).append("]\n");
        sb.append("│   ├─ Golf6 Bluetooth                : ").append(bt ? "✓ bağlı" : "○ bağlı değil").append(" [").append(Prefs.carBtName(this)).append("]\n");
        sb.append("│   ├─ Android Auto kapanışı          : olay gelirse değerlendirilir\n");
        sb.append("│   └─ Şarj durumu                    : ").append(charging ? "şarjda" : "şarjda değil").append(chargePending ? " / çıkış bekleniyor" : "").append("\n");
        sb.append("├─ 4) Koşul sayaçları\n");
        sb.append("│   ├─ Başlangıç koruması             : ").append(graceLeft > 0 ? fmtRemain(graceLeft) + " kaldı" : "tamam").append("\n");
        sb.append("│   ├─ Wi‑Fi uzak kalma sayacı        : ").append(wifiAwaySince > 0 && !onHomeWifi ? fmtRemain(Prefs.wifiAwayMs(this) - wifiLeft) + " geçti / " + fmtRemain(wifiLeft) + " kaldı" : "aktif değil").append(" | ayar=").append(Prefs.wifiAwayMinutes(this)).append(" dk\n");
        sb.append("│   ├─ Genel aday penceresi           : ").append(pendingMethod == null || pendingMethod.isEmpty() ? "bekleme yok" : pendingMethod).append(" | ayar=").append(Prefs.candidateTimeoutMinutes(this)).append(" dk\n");
        sb.append("│   └─ Ortak tetik kilidi             : ").append(Prefs.lastTrigger(this) == 0 ? "yok" : fmtRemain(lockLeft) + " kaldı").append(" | ayar=").append(Prefs.cooldownMinutes(this)).append(" dk\n");
        sb.append("├─ 5) Karar\n");
        if (sending) {
            sb.append("│   └─ MQTT gönderiliyor...\n");
        } else if (graceLeft > 0) {
            sb.append("│   └─ Şu an TETİK VERMEZ → başlangıç koruması aktif.\n");
        } else if (Prefs.lastTrigger(this) > 0 && lockLeft > 0) {
            sb.append("│   └─ Şu an TETİK VERMEZ → ortak tetik kilidi aktif.\n");
        } else if (!inside) {
            sb.append("│   └─ Şu an TETİK VERMEZ → önce ev alanına giriş beklenir.\n");
        } else if (chargePending) {
            sb.append("│   └─ Şu an şarjdan çıkma bekleniyor.\n");
        } else if (wifiAwaySince > 0 && !onHomeWifi && wifiLeft > 0) {
            sb.append("│   └─ Şu an Wi‑Fi için minimum uzak kalma süresi dolması bekleniyor.\n");
        } else {
            sb.append("│   └─ Uygun kaynak olayı gelirse MQTT tetiklenebilir.\n");
        }
        sb.append("└─ 6) Son aktif aşama\n");
        sb.append("    ├─ ").append(p.getString("flow_stage", "Hazır")).append("\n");
        sb.append("    └─ ").append(p.getString("flow_detail", "Henüz olay yok"));
        return sb.toString();
    }

    private GradientDrawable cardBg(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), 0x33000000);
        return g;
    }

    private void addFlowCard(String title, String detail, int state) {
        if (visualFlow == null) return;
        int bg;
        String icon;
        if (state == 1) { bg = 0xFFDFF5E3; icon = "✓"; }
        else if (state == 2) { bg = 0xFFFFF1C7; icon = "⏳"; }
        else if (state == 3) { bg = 0xFFFFDDDD; icon = "✗"; }
        else { bg = 0xFFE9ECEF; icon = "○"; }

        TextView box = new TextView(this);
        box.setText(icon + "  " + title + "\n" + detail);
        box.setTextSize(14f);
        box.setTextColor(Color.BLACK);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(cardBg(bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        visualFlow.addView(box, lp);

        TextView arrow = new TextView(this);
        arrow.setText("                 ↓");
        arrow.setTextSize(18f);
        arrow.setTextColor(0xFF666666);
        visualFlow.addView(arrow);
    }

    private void renderVisualFlow(SharedPreferences p) {
        if (visualFlow == null) return;
        visualFlow.removeAllViews();

        long now = System.currentTimeMillis();
        boolean service = Prefs.serviceEnabled(this);
        boolean inside = p.getBoolean("inside_home", false);
        boolean bt = p.getBoolean("car_bt_connected", false);
        boolean charging = p.getBoolean("charging", false);
        boolean chargePending = p.getBoolean("charge_arrival_pending", false);
        String currentSsid = p.getString("current_ssid", "(bilinmiyor)");
        boolean homeWifi = Prefs.ssid(this).equals(currentSsid);
        long wifiAway = p.getLong("wifi_away_since", 0L);
        long wifiLeft = wifiAway > 0 && !homeWifi ? Math.max(0L, Prefs.wifiAwayMs(this) - (now - wifiAway)) : 0L;
        long graceLeft = Math.max(0L, p.getLong("startup_grace_until_ms", 0L) - now);
        long lockLeft = Prefs.lastTrigger(this) <= 0 ? 0L
                : Math.max(0L, Prefs.cooldownMs(this) - (now - Prefs.lastTrigger(this)));
        String pending = p.getString("event_pending_method", "");
        boolean sending = p.getBoolean("mqtt_sending", false);
        boolean freshWaiting = p.getBoolean("fresh_location_waiting", false);
        long locFix = p.getLong("last_location_fix_time_ms", 0L);
        long locAge = locFix <= 0L ? Long.MAX_VALUE : Math.max(0L, now - locFix);
        float locAcc = p.getFloat("last_location_accuracy_v5", p.getFloat("last_accuracy", -1f));
        String stage = p.getString("flow_stage", "Hazır");
        String detail = p.getString("flow_detail", "Henüz olay yok");

        addFlowCard("1. İZLEME SERVİSİ",
                service ? "Servis açık; sensörler izleniyor." : "Servis kapalı. Önce İZLEMEYİ BAŞLAT.",
                service ? 1 : 3);

        int locState = freshWaiting ? 2
                : ((locFix > 0L && locAge <= 15_000L && (locAcc < 0f || locAcc <= 150f)) ? 1 : 3);
        String locDetail = freshWaiting
                ? "Yüksek doğruluklu TAZE konum hesaplanıyor. Eski konumla tetik kararı verilmeyecek."
                : "Motor=" + p.getString("location_engine", "?")
                    + " • yaş=" + (locAge == Long.MAX_VALUE ? "yok" : (locAge / 1000L) + " sn")
                    + " • doğruluk=" + Math.round(locAcc) + " m"
                    + " • sağlayıcı=" + p.getString("last_location_provider", "?");
        addFlowCard("2. TAZE KONUM MOTORU", locDetail, locState);

        int homeState = inside ? 1 : (service ? 2 : 0);
        addFlowCard("3. EV ALANI",
                inside ? "Telefon ev yarıçapında." :
                        "Telefon ev dışında. Eve giriş bekleniyor.",
                homeState);

        String sourceDetail;
        int sourceState = 0;
        if (pending != null && !pending.isEmpty()) {
            sourceDetail = "Kaynak olay geldi: " + pending + ". Konum teyidi bekleniyor.";
            sourceState = 2;
        } else if (chargePending) {
            long cs = p.getLong("charge_pending_since_ms", 0L);
            long cl = cs > 0L ? Math.max(0L, Prefs.chargeTimeoutMs(this) - (now - cs)) : 0L;
            sourceDetail = "Telefon eve şarjda geldi. Şarjdan çıkarma için " + fmtRemain(cl) + " kaldı; süre dolarsa yöntem İPTAL.";
            sourceState = 2;
        } else if (wifiAway > 0 && !homeWifi) {
            sourceDetail = "Ev Wi‑Fi uzakta. " + Prefs.wifiAwayMinutes(this) + " dk şartı için " + fmtRemain(wifiLeft) + " kaldı; sonra yeniden bağlanma beklenir.";
            sourceState = 2;
        } else if (bt) {
            sourceDetail = "Ysf Golf6 bağlı. Eve yaklaşınca Golf6 bağlantısının kesilmesi bekleniyor.";
            sourceState = 2;
        } else {
            sourceDetail = "Golf6 ayrılması / Wi‑Fi geri bağlanması / Android Auto kapanması / şarjdan çıkma olaylarından biri bekleniyor.";
            sourceState = service ? 2 : 0;
        }
        addFlowCard("4. GELİŞ KAYNAĞI", sourceDetail, sourceState);

        int guardState = 1;
        String guardDetail = "Başlangıç koruması tamam, ortak tetik kilidi yok.";
        if (graceLeft > 0) {
            guardState = 3;
            guardDetail = "HA/APK servis başlangıç koruması: " + fmtRemain(graceLeft) + " kaldı. Bu sürede tetik verilmez.";
        } else if (lockLeft > 0) {
            guardState = 3;
            guardDetail = "Ortak kilit (" + Prefs.cooldownMinutes(this) + " dk): " + fmtRemain(lockLeft) + " kaldı. Yeni tetik verilmez.";
        }
        addFlowCard("5. GÜVENLİK KİLİTLERİ", guardDetail, guardState);

        int decisionState = 2;
        String decisionDetail = "Henüz tetik kararı yok.";
        if (stage.startsWith("RED") || stage.startsWith("TEST RED")) {
            decisionState = 3;
            decisionDetail = detail;
        } else if (stage.startsWith("TEST ALLOW")) {
            decisionState = 1;
            decisionDetail = "TEST başarılı: gerçek olay olsaydı MQTT gönderilecekti.";
        } else if (stage.startsWith("MQTT gönderildi")) {
            decisionState = 1;
            decisionDetail = detail;
        } else if (sending) {
            decisionState = 2;
            decisionDetail = "MQTT gönderiliyor...";
        } else if (!inside) {
            decisionDetail = "Önce ev alanı + geliş kaynağı teyidi bekleniyor.";
        } else if (graceLeft > 0 || lockLeft > 0) {
            decisionState = 3;
            decisionDetail = "Kaynak olay gelse bile aktif güvenlik kilidi nedeniyle gönderim yapılmaz.";
        } else {
            decisionDetail = "Ev teyitli. Uygun kaynak olayı geldiğinde MQTT gönderilebilir.";
        }
        addFlowCard("6. KARAR", decisionDetail, decisionState);

        addFlowCard("7. MQTT → HOME ASSISTANT",
                sending ? "192.168.7.129:1883 üzerinden gönderim sürüyor." :
                        "Hazır. Tailscale L3 üzerinden " + Prefs.host(this) + ":" + Prefs.port(this) + " hedefleniyor.",
                sending ? 2 : 0);

        // Remove the last decorative arrow.
        int n = visualFlow.getChildCount();
        if (n > 0) visualFlow.removeViewAt(n - 1);
    }

    private void refreshStatus() {
        if (statusText == null) return;
        SharedPreferences p = Prefs.p(this);
        renderVisualFlow(p);
        if (eventLogText != null) eventLogText.setText(Prefs.eventLog(this));
        long last = Prefs.lastTrigger(this);
        long remain = last == 0 ? 0 : Math.max(0, Prefs.cooldownMs(this) - (System.currentTimeMillis() - last));
        String lastTxt = last == 0 ? "Yok" : DateFormat.getDateTimeInstance().format(new Date(last));
        String inside = p.getBoolean("inside_home", false) ? "EV ALANINDA" : "DIŞARIDA";
        String pendingMethod = p.getString("event_pending_method", "");
        long pendingAt = p.getLong("event_pending_ms", 0L);
        long pendingLeft = pendingAt == 0 ? 0 : Math.max(0, Prefs.candidateTimeoutMs(this) - (System.currentTimeMillis() - pendingAt));
        String s =
                "Servis: " + (Prefs.serviceEnabled(this) ? "AÇIK" : "KAPALI") +
                "\nMQTT: " + Prefs.host(this) + ":" + Prefs.port(this) +
                "\nTopic: " + Prefs.topic(this) +
                "\nKonum durumu: " + inside +
                "\nEv merkezine mesafe: " + Math.round(p.getFloat("distance_home", -1f)) + " m" +
                "\nKonum motoru: " + p.getString("location_engine", "?") +
                "\nSon konum doğruluğu: " + Math.round(p.getFloat("last_location_accuracy_v5", p.getFloat("last_accuracy", -1f))) + " m" +
                "\nSon konum yaşı: " + fmtRemain(Math.max(0L, System.currentTimeMillis() - p.getLong("last_location_fix_time_ms", System.currentTimeMillis()))) +
                "\nKonum sağlayıcı: " + p.getString("last_location_provider", "?") +
                "\nTaze konum bekleniyor: " + p.getBoolean("fresh_location_waiting", false) +
                "\nSon taze konum nedeni: " + p.getString("fresh_location_reason", "-") +
                "\nSon taze konum sonucu: " + p.getString("fresh_location_result", "-") +
                "\nTaze konum gecikmesi: " + p.getLong("fresh_location_latency_ms", -1L) + " ms" +
                "\nWi‑Fi: " + p.getString("current_ssid", "?") +
                "\nWi‑Fi okuma yolu: " + p.getString("wifi_source", "?") +
                "\nEv Wi‑Fi hedefi: " + Prefs.ssid(this) +
                "\nAraç Bluetooth adı: " + Prefs.carBtName(this) +
                "\nAraç Bluetooth MAC: " + Prefs.carBtAddress(this) +
                "\nAraç bağlantısı: " + (p.getBoolean("car_bt_connected", false) ? "BAĞLI" : "BAĞLI DEĞİL") +
                "\nŞarj: " + p.getBoolean("charging", false) +
                "\nŞarj-geliş beklemesi: " + p.getBoolean("charge_arrival_pending", false) +
                "\nBekleyen olay: " + (pendingMethod == null || pendingMethod.isEmpty() ? "yok" : pendingMethod + " / kalan=" + fmtRemain(pendingLeft)) +
                "\nMQTT gönderim: " + p.getBoolean("mqtt_sending", false) +
                "\nSon başarılı tetik: " + lastTxt +
                "\nSon tetik yöntemi: " + p.getString("last_trigger_method", "Yok") +
                "\nOrtak kilit kalan: " + fmtRemain(remain) +
                "\n--- ZAMAN / GÜVENLİK AYARLARI ---" +
                "\nWi‑Fi minimum uzaklık: " + Prefs.wifiAwayMinutes(this) + " dk" +
                "\nŞarjdan çıkarma timeout: " + Prefs.chargeTimeoutMinutes(this) + " dk" +
                "\nGenel aday timeout: " + Prefs.candidateTimeoutMinutes(this) + " dk" +
                "\nBaşlangıç koruması: " + Prefs.startupGraceMinutes(this) + " dk" +
                "\nOrtak tetik kilidi: " + Prefs.cooldownMinutes(this) + " dk" +
                "\nSon durum kaydı: " + Prefs.lastStatus(this) +
                "\n\nHarita notu: mavi daire kayıtlı ev alanıdır. Haritada uzun basarak ev merkezini değiştirebilirsin." +
                "\nİzin notu: Android 14/15/16 için Konum=Her zaman, Kesin konum=Açık, Yakındaki cihazlar=İzin ver, Pil=Kısıtlanmamış önerilir.";
        stageText.setText(buildStageSummary(p));
        treeText.setText(buildFlowTree(p));
        statusText.setText(s);
        updateHomeText();
        updateMapOverlays(false);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::refreshStatus, 1000);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
        refreshStatus();
    }

    @Override protected void onPause() {
        if (map != null) map.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
