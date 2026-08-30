package com.yusuf.evegiris;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.util.concurrent.atomic.AtomicBoolean;

public class MonitorService extends Service implements LocationListener {
    public static final String ACTION_TEST_WIFI = "com.yusuf.evegiris.TEST_WIFI";
    public static final String ACTION_TEST_BT = "com.yusuf.evegiris.TEST_BT";
    public static final String ACTION_TEST_AA = "com.yusuf.evegiris.TEST_AA";
    public static final String ACTION_TEST_CHARGE = "com.yusuf.evegiris.TEST_CHARGE";
    public static final String ACTION_TEST_HOME_ENTER = "com.yusuf.evegiris.TEST_HOME_ENTER";
    public static final String ACTION_TEST_HOME_LEAVE = "com.yusuf.evegiris.TEST_HOME_LEAVE";
    public static final String ACTION_TEST_WIFI_AWAY = "com.yusuf.evegiris.TEST_WIFI_AWAY";
    public static final String ACTION_TEST_WIFI_READY = "com.yusuf.evegiris.TEST_WIFI_READY";
    public static final String ACTION_TEST_BT_CONNECTED = "com.yusuf.evegiris.TEST_BT_CONNECTED";
    public static final String ACTION_TEST_CHARGE_ARRIVAL = "com.yusuf.evegiris.TEST_CHARGE_ARRIVAL";
    public static final String ACTION_TEST_FULL = "com.yusuf.evegiris.TEST_FULL";
    public static final String ACTION_FORCE_LOCATION = "com.yusuf.evegiris.FORCE_LOCATION";
    public static final String ACTION_TEST_CHARGE_TIMEOUT = "com.yusuf.evegiris.TEST_CHARGE_TIMEOUT";
    public static final String ACTION_TEST_CANDIDATE_TIMEOUT = "com.yusuf.evegiris.TEST_CANDIDATE_TIMEOUT";

    private static final String CHANNEL = "yusuf_eve_giris_monitor";
    private static final int NOTIF_ID = 7041;
    private static final long FUSED_INTERVAL = 3000L;
    private static final long FUSED_MIN_INTERVAL = 1000L;
    private static final float FUSED_MIN_DISTANCE = 1.0f;
    private static final long FRESH_LOCATION_TIMEOUT = 10_000L;
    private static final float MAX_TRIGGER_ACCURACY_METERS = 150f;

    private LocationManager locationManager;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback fusedLocationCallback;
    private volatile boolean fusedRunning = false;
    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private volatile boolean carBtConnected = false;
    private boolean locationInitialized = false;
    private boolean wifiInitialized = false;
    private boolean insideHome = false;
    private long wifiAwaySince = 0L;
    private String pendingMethod = null;
    private boolean pendingDryRun = false;
    private long pendingMethodAt = 0L;
    private volatile String callbackSsid = "";
    private ConnectivityManager.NetworkCallback wifiCallback;
    private final AtomicBoolean sending = new AtomicBoolean(false);
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutTick = new Runnable() {
        @Override public void run() {
            try {
                expireTimedWindows();
            } finally {
                timeoutHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (a == null) return;

            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
                BluetoothDevice d = getBt(intent);
                if (isTargetCarBt(d)) {
                    setCarBtConnected(true, d);
                    Prefs.p(MonitorService.this).edit().putLong("last_bt_connected_ms", System.currentTimeMillis()).apply();
                    setFlow("Golf6 bağlı", "Araç Bluetooth bağlandı: " + safeBtLabel(d) + ". Şimdi bağlantının kesilmesi bekleniyor.");
                }
                return;
            }

            if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                BluetoothDevice d = getBt(intent);
                if (isTargetCarBt(d)) {
                    setCarBtConnected(false, d);
                    Prefs.p(MonitorService.this).edit().putLong("last_bt_disconnected_ms", System.currentTimeMillis()).apply();
                    setFlow("Golf6 ayrıldı", "Araç Bluetooth koptu: " + safeBtLabel(d) + ". Şimdi ev konumu ve güvenlik kilitleri kontrol ediliyor.");
                    requestCandidate("bluetooth_golf6", false);
                }
                return;
            }

            if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                Prefs.p(MonitorService.this).edit()
                        .putBoolean("charging", true)
                        .putLong("last_charge_connected_ms", System.currentTimeMillis())
                        .apply();
                setFlow("Şarj bağlandı", "Telefon şarja takıldı.");
                return;
            }

            if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                SharedPreferences sp = Prefs.p(MonitorService.this);
                boolean pending = sp.getBoolean("charge_arrival_pending", false);
                long now = System.currentTimeMillis();
                long since = sp.getLong("charge_pending_since_ms", 0L);
                long max = Prefs.chargeTimeoutMs(MonitorService.this);

                sp.edit()
                        .putBoolean("charging", false)
                        .putLong("last_charge_disconnected_ms", now)
                        .apply();

                if (pending && since > 0L && now - since <= max) {
                    sp.edit()
                            .putBoolean("charge_arrival_pending", false)
                            .remove("charge_pending_since_ms")
                            .apply();
                    setFlow("Şarjdan çıktı",
                            "Şarj penceresi içinde çıkarıldı (" + Prefs.chargeTimeoutMinutes(MonitorService.this)
                                    + " dk sınır). Taze konum kontrolü başlıyor.");
                    requestCandidate("charge", false);
                } else if (pending) {
                    sp.edit()
                            .putBoolean("charge_arrival_pending", false)
                            .remove("charge_pending_since_ms")
                            .putLong("last_charge_timeout_ms", now)
                            .apply();
                    setFlow("ŞARJ ZAMAN AŞIMI",
                            "Şarjdan çıkarma geç kaldı. " + Prefs.chargeTimeoutMinutes(MonitorService.this)
                                    + " dk pencere dolduğu için MQTT geliş yöntemi İPTAL.");
                } else {
                    setFlow("Şarjdan çıktı",
                            "Aktif eve-şarjda-geliş kaydı yok; bu çıkış MQTT tetiklemez.");
                }
                return;
            }

            if ("android.app.action.EXIT_CAR_MODE".equals(a)) {
                setFlow("Android Auto çıkışı", "Android Auto/Car Mode kapandı. " + Prefs.carBtName(MonitorService.this) + " bağlı mı? " + carBtConnected);
                if (!carBtConnected) requestCandidate("android_auto", false);
                return;
            }

            if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(a) || WifiManager.WIFI_STATE_CHANGED_ACTION.equals(a)) {
                updateWifi();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, notification("İzleme aktif"));
        long now = System.currentTimeMillis();
        Prefs.p(this).edit()
                .putBoolean("service_enabled", true)
                .putLong("service_started_ms", now)
                .putLong("startup_grace_until_ms", now + Prefs.startupGraceMs(this))
                .remove("event_pending_method").remove("event_pending_ms")
                .putBoolean("event_pending_dry_run", false)
                .putBoolean("mqtt_sending", false)
                .apply();

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

        registerReceivers();
        initCharging();
        initBluetooth();
        initWifi();
        initLocation();
        timeoutHandler.post(timeoutTick);
        setFlow("Servis başlatıldı", "Başlangıç koruması=" + Prefs.startupGraceMinutes(this) + " dk. Zaman aşımı denetleyicisi aktif.");
    }

    private void setFlow(String stage, String detail) {
        SharedPreferences sp = Prefs.p(this);
        String oldStage = sp.getString("flow_stage", "");
        String oldDetail = sp.getString("flow_detail", "");
        sp.edit()
                .putString("flow_stage", stage)
                .putString("flow_detail", detail)
                .putLong("flow_stage_ms", System.currentTimeMillis())
                .apply();
        Prefs.status(this, detail);
        if (!stage.equals(oldStage) || !detail.equals(oldDetail)) {
            Prefs.addEvent(this, stage, detail);
        }
        updateNotification(stage);
    }

    private Notification notification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("Yusuf Eve Giriş")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Yusuf Eve Giriş İzleme", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Konum, Wi-Fi, Bluetooth ve araç bağlantısını izler");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private void registerReceivers() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction("android.app.action.ENTER_CAR_MODE");
        f.addAction("android.app.action.EXIT_CAR_MODE");
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    private BluetoothDevice getBt(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private void initCharging() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean charging = false;
        if (b != null) {
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0;
        }
        Prefs.p(this).edit().putBoolean("charging", charging).apply();
    }

    private boolean hasBtPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private String safeBtName(BluetoothDevice d) {
        if (d == null || !hasBtPermission()) return "";
        try {
            String n = d.getName();
            return n == null ? "" : n.trim();
        } catch (Exception ignored) { return ""; }
    }

    private String safeBtAlias(BluetoothDevice d) {
        if (d == null || !hasBtPermission() || Build.VERSION.SDK_INT < 30) return "";
        try {
            String n = d.getAlias();
            return n == null ? "" : n.trim();
        } catch (Exception ignored) { return ""; }
    }

    private String safeBtAddress(BluetoothDevice d) {
        if (d == null || !hasBtPermission()) return "";
        try {
            String a = d.getAddress();
            return a == null ? "" : a.trim();
        } catch (Exception ignored) { return ""; }
    }

    private String safeBtLabel(BluetoothDevice d) {
        String alias = safeBtAlias(d);
        if (!alias.isEmpty()) return alias;
        String name = safeBtName(d);
        if (!name.isEmpty()) return name;
        String addr = safeBtAddress(d);
        return addr.isEmpty() ? Prefs.carBtName(this) : addr;
    }

    private boolean isTargetCarBt(BluetoothDevice d) {
        if (d == null || !hasBtPermission()) return false;
        String target = Prefs.carBtName(this).trim();
        if (target.isEmpty()) return false;

        String name = safeBtName(d);
        String alias = safeBtAlias(d);
        String addr = safeBtAddress(d);
        String remembered = Prefs.carBtAddress(this);

        boolean byName = target.equalsIgnoreCase(name) || target.equalsIgnoreCase(alias);
        boolean byRememberedAddress = !remembered.isEmpty() && remembered.equalsIgnoreCase(addr);

        if (byName && !addr.isEmpty()) {
            Prefs.p(this).edit().putString("car_bt_address", addr).apply();
        }
        return byName || byRememberedAddress;
    }

    private void setCarBtConnected(boolean connected, BluetoothDevice d) {
        carBtConnected = connected;
        SharedPreferences.Editor e = Prefs.p(this).edit().putBoolean("car_bt_connected", connected);
        String addr = safeBtAddress(d);
        if (connected && !addr.isEmpty()) e.putString("car_bt_address", addr);
        e.apply();
    }

    private void initBluetooth() {
        carBtConnected = false;
        Prefs.p(this).edit().putBoolean("car_bt_connected", false).apply();
        if (!hasBtPermission()) {
            setFlow("Bluetooth izni yok", Prefs.carBtName(this) + " izlenemiyor.");
            return;
        }
        try {
            BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            if (bm == null) return;

            for (BluetoothDevice d : bm.getConnectedDevices(BluetoothProfile.GATT)) {
                if (isTargetCarBt(d)) setCarBtConnected(true, d);
            }

            BluetoothAdapter adapter = bm.getAdapter();
            if (adapter != null) {
                BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
                    @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                        try {
                            for (BluetoothDevice d : proxy.getConnectedDevices()) {
                                if (isTargetCarBt(d)) setCarBtConnected(true, d);
                            }
                        } catch (SecurityException ignored) {}
                    }
                    @Override public void onServiceDisconnected(int profile) {}
                };
                adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP);
                adapter.getProfileProxy(this, listener, BluetoothProfile.HEADSET);
            }
        } catch (Exception ignored) {}
    }

    private void initWifi() {
        wifiAwaySince = Prefs.p(this).getLong("wifi_away_since", 0L);
        updateWifi();
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                wifiCallback = new ConnectivityManager.NetworkCallback(
                        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                    @Override public void onAvailable(Network network) { updateWifi(); }
                    @Override public void onLost(Network network) {
                        callbackSsid = "";
                        updateWifi();
                    }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                        String s = ssidFromCapabilities(caps);
                        if (!s.isEmpty()) callbackSsid = s;
                        updateWifi();
                    }
                };
            } else {
                wifiCallback = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { updateWifi(); }
                    @Override public void onLost(Network network) { updateWifi(); }
                    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { updateWifi(); }
                };
            }
            connectivityManager.registerDefaultNetworkCallback(wifiCallback);
        } catch (Exception e) {
            Prefs.p(this).edit().putString("wifi_source", "callback_error:" + e.getClass().getSimpleName()).apply();
        }
    }

    private String normalizeSsid(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty() || "<unknown ssid>".equalsIgnoreCase(s) || "unknown ssid".equalsIgnoreCase(s)) return "";
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
        return s;
    }

    private String ssidFromCapabilities(NetworkCapabilities caps) {
        try {
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "";
            Object ti = caps.getTransportInfo();
            if (ti instanceof WifiInfo) return normalizeSsid(((WifiInfo) ti).getSSID());
        } catch (Exception ignored) {}
        return "";
    }

    private String currentSsid() {
        String s = normalizeSsid(callbackSsid);
        if (!s.isEmpty()) {
            Prefs.p(this).edit().putString("wifi_source", "NetworkCallback").apply();
            return s;
        }

        try {
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : connectivityManager.getNetworkCapabilities(active);
            s = ssidFromCapabilities(caps);
            if (!s.isEmpty()) {
                Prefs.p(this).edit().putString("wifi_source", "NetworkCapabilities").apply();
                return s;
            }
        } catch (Exception ignored) {}

        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            s = info == null ? "" : normalizeSsid(info.getSSID());
            if (!s.isEmpty()) {
                Prefs.p(this).edit().putString("wifi_source", "WifiManager fallback").apply();
                return s;
            }
        } catch (Exception ignored) {}

        Prefs.p(this).edit().putString("wifi_source", "SSID okunamadi").apply();
        return "";
    }

    private synchronized void updateWifi() {
        String cur = currentSsid();
        String target = Prefs.ssid(this);
        boolean onTarget = !target.isEmpty() && target.equals(cur);
        long now = System.currentTimeMillis();
        Prefs.p(this).edit().putString("current_ssid", cur.isEmpty() ? "(yok/bilinmiyor)" : cur).apply();

        if (!wifiInitialized) {
            wifiInitialized = true;
            if (onTarget) {
                wifiAwaySince = 0L;
                Prefs.p(this).edit().remove("wifi_away_since").apply();
            } else if (wifiAwaySince == 0L) {
                wifiAwaySince = now;
                Prefs.p(this).edit().putLong("wifi_away_since", now).apply();
            }
            Prefs.p(this).edit().putBoolean("wifi_target", onTarget).apply();
            return;
        }

        boolean wasTarget = Prefs.p(this).getBoolean("wifi_target", false);
        if (onTarget) {
            if (!wasTarget && wifiAwaySince > 0L) {
                long away = now - wifiAwaySince;
                if (away >= Prefs.wifiAwayMs(this)) {
                    Prefs.p(this).edit().putLong("last_wifi_reconnect_ms", now).apply();
                setFlow("Wi‑Fi geri bağlandı", "Ev Wi‑Fi tekrar bağlandı. " + Prefs.wifiAwayMinutes(this) + " dk minimum uzak kalma şartı sağlandı; genel aday penceresi " + Prefs.candidateTimeoutMinutes(this) + " dk.");
                    requestCandidate("wifi", false);
                } else {
                    setFlow("Wi‑Fi erken geri geldi", "Ev Wi‑Fi geri geldi ama " + Prefs.wifiAwayMinutes(this) + " dk dolmadı. Kalan: " + ((Prefs.wifiAwayMs(this) - away) / 1000L) + " sn");
                }
            }
            wifiAwaySince = 0L;
            Prefs.p(this).edit().remove("wifi_away_since").putBoolean("wifi_target", true).apply();
        } else {
            if (wasTarget || wifiAwaySince == 0L) {
                wifiAwaySince = now;
                Prefs.p(this).edit().putLong("wifi_away_since", now).apply();
                Prefs.p(this).edit().putLong("last_wifi_left_ms", now).apply();
                setFlow("Wi‑Fi bekleniyor", "Ev Wi‑Fi ayrıldı. " + Prefs.wifiAwayMinutes(this) + " dk geri sayım başladı. Sayaç dolduktan sonra aynı Wi‑Fi'ye yeniden bağlanma bekleniyor.");
            }
            Prefs.p(this).edit().putBoolean("wifi_target", false).apply();
        }
    }

    private void initLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            setFlow("Konum izni yok", "Konum izni verilmediği için servis durduruldu.");
            stopSelf();
            return;
        }

        try {
            LocationRequest request = new LocationRequest.Builder(
                    Priority.PRIORITY_HIGH_ACCURACY, FUSED_INTERVAL)
                    .setMinUpdateIntervalMillis(FUSED_MIN_INTERVAL)
                    .setMinUpdateDistanceMeters(FUSED_MIN_DISTANCE)
                    .setMaxUpdateDelayMillis(0L)
                    .setWaitForAccurateLocation(false)
                    .build();

            fusedLocationCallback = new LocationCallback() {
                @Override public void onLocationResult(LocationResult result) {
                    if (result == null) return;
                    for (Location loc : result.getLocations()) {
                        if (loc == null) continue;
                        Prefs.p(MonitorService.this).edit()
                                .putString("location_engine", "FUSED_HIGH_ACCURACY")
                                .apply();
                        onLocationChanged(loc);
                    }
                }
            };

            fusedLocationClient.requestLocationUpdates(
                    request, fusedLocationCallback, Looper.getMainLooper());
            fusedRunning = true;
            Prefs.p(this).edit().putString("location_engine", "FUSED_HIGH_ACCURACY").apply();

            requestFreshLocation("servis_baslangici", false);
        } catch (Exception e) {
            fusedRunning = false;
            setFlow("Fused konum başlatılamadı",
                    "Google Fused Location hata: " + e.getClass().getSimpleName()
                            + ". Android LocationManager yedeği kullanılacak.");
        }

        if (!fusedRunning) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, 3000L, 1f,
                            this, Looper.getMainLooper());
                }
            } catch (Exception ignored) {}
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, 3000L, 1f,
                            this, Looper.getMainLooper());
                }
            } catch (Exception ignored) {}
            Prefs.p(this).edit().putString("location_engine", "ANDROID_FALLBACK").apply();
        }
    }

    private void requestFreshLocation(String reason, boolean candidateDriven) {
        if (fusedLocationClient == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        long requestedAt = System.currentTimeMillis();
        Prefs.p(this).edit()
                .putLong("fresh_location_requested_ms", requestedAt)
                .putString("fresh_location_reason", reason)
                .putBoolean("fresh_location_waiting", true)
                .apply();

        if (candidateDriven) {
            setFlow("TAZE KONUM İSTENDİ",
                    reason + " olayı geldi. Eski konum kullanılmıyor; yüksek doğrulukta yeni konum bekleniyor.");
        }

        try {
            CurrentLocationRequest req = new CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0L)
                    .setDurationMillis(FRESH_LOCATION_TIMEOUT)
                    .build();

            CancellationTokenSource cts = new CancellationTokenSource();
            fusedLocationClient.getCurrentLocation(req, cts.getToken())
                    .addOnSuccessListener(location -> {
                        long now = System.currentTimeMillis();
                        SharedPreferences.Editor e = Prefs.p(MonitorService.this).edit()
                                .putBoolean("fresh_location_waiting", false)
                                .putLong("fresh_location_received_ms", now)
                                .putLong("fresh_location_latency_ms", Math.max(0L, now - requestedAt));
                        if (location != null) {
                            e.putString("fresh_location_result", "OK")
                                    .putString("location_engine", "FUSED_FRESH_HIGH_ACCURACY")
                                    .apply();
                            onLocationChanged(location);
                        } else {
                            e.putString("fresh_location_result", "NULL").apply();
                            if (candidateDriven) {
                                setFlow("TAZE KONUM GELMEDİ",
                                        reason + ": 10 sn içinde taze fix üretilemedi. Sürekli Fused takip 2 dk pencere içinde devam ediyor.");
                            }
                        }
                    })
                    .addOnFailureListener(err -> {
                        long now = System.currentTimeMillis();
                        Prefs.p(MonitorService.this).edit()
                                .putBoolean("fresh_location_waiting", false)
                                .putLong("fresh_location_received_ms", now)
                                .putLong("fresh_location_latency_ms", Math.max(0L, now - requestedAt))
                                .putString("fresh_location_result", "ERROR:" + err.getClass().getSimpleName())
                                .apply();
                        if (candidateDriven) {
                            setFlow("TAZE KONUM HATASI",
                                    reason + ": " + err.getClass().getSimpleName()
                                            + ". Sürekli konum takibi devam ediyor.");
                        }
                    });
        } catch (Exception e) {
            Prefs.p(this).edit()
                    .putBoolean("fresh_location_waiting", false)
                    .putString("fresh_location_result", "START_ERROR:" + e.getClass().getSimpleName())
                    .apply();
        }
    }

    @Override public void onLocationChanged(Location location) {
        if (location == null || !Prefs.homeSet(this)) return;

        long nowMs = System.currentTimeMillis();
        long fixTime = location.getTime() > 0L ? location.getTime() : nowMs;
        long fixAge = Math.max(0L, nowMs - fixTime);
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : -1f;

        Prefs.p(this).edit()
                .putLong("last_location_fix_time_ms", fixTime)
                .putLong("last_location_age_ms", fixAge)
                .putString("last_location_provider", String.valueOf(location.getProvider()))
                .putFloat("last_location_accuracy_v5", accuracy)
                .apply();

        float[] d = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(), Prefs.homeLat(this), Prefs.homeLon(this), d);
        boolean nowInside = d[0] <= Prefs.radius(this);
        boolean oldInside = insideHome;
        insideHome = nowInside;
        Prefs.saveLastLocation(this, location);
        Prefs.p(this).edit().putBoolean("inside_home", nowInside).putFloat("distance_home", d[0]).apply();

        if (!locationInitialized) {
            locationInitialized = true;
            setFlow("İlk konum alındı", "Ev mesafesi=" + Math.round(d[0]) + "m, doğruluk=" + Math.round(accuracy) + "m, yaş=" + (fixAge / 1000L) + "sn, inside=" + nowInside);
        } else {
            if (!oldInside && nowInside) {
                boolean charging = isCharging();
                Prefs.p(this).edit().putLong("last_home_enter_ms", System.currentTimeMillis()).apply();
                setFlow("Ev alanına girdi", "Telefon ev alanına girdi. Şarj=" + charging + ", mesafe=" + Math.round(d[0]) + "m. Şimdi geliş kaynağı teyidi bekleniyor.");
                if (charging) {
                    Prefs.p(this).edit()
                            .putBoolean("charge_arrival_pending", true)
                            .putLong("charge_pending_since_ms", System.currentTimeMillis())
                            .apply();
                    setFlow("Şarjdan çıkma bekleniyor", "Ev alanına giriş şarjdayken oldu. " + Prefs.chargeTimeoutMinutes(this) + " dk içinde şarjdan çıkarılmazsa bu yöntem otomatik İPTAL.");
                }
            }
            if (oldInside && !nowInside) {
                Prefs.p(this).edit()
                        .putBoolean("charge_arrival_pending", false)
                        .remove("charge_pending_since_ms")
                        .putLong("last_home_leave_ms", System.currentTimeMillis())
                        .apply();
                setFlow("Evden ayrıldı", "Telefon ev alanından çıktı. Şimdi ev Wi‑Fi'dan ayrılma/10 dk sayacı ve Ysf Golf6 bağlantısı izleniyor.");
            }
        }

        if (nowInside && pendingMethod != null) {
            if (System.currentTimeMillis() - pendingMethodAt <= Prefs.candidateTimeoutMs(this)) {
                String m = pendingMethod;
                boolean dry = pendingDryRun;
                clearPending();
                evaluateTrigger(m + "_konum_teyit", dry);
            } else {
                clearPending();
                setFlow("GENEL ADAY ZAMAN AŞIMI", "Bekleyen " + Prefs.candidateTimeoutMinutes(this) + " dk geliş adayı penceresi doldu. MQTT yöntemi İPTAL.");
            }
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {}

    private boolean isCharging() {
        boolean c = Prefs.p(this).getBoolean("charging", false);
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            c = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL || plugged != 0;
            Prefs.p(this).edit().putBoolean("charging", c).apply();
        }
        return c;
    }

    private synchronized void clearPending() {
        pendingMethod = null;
        pendingDryRun = false;
        pendingMethodAt = 0L;
        Prefs.p(this).edit().remove("event_pending_method").remove("event_pending_ms").remove("event_pending_origin_ms").putBoolean("event_pending_dry_run", false).apply();
    }

    private synchronized void requestCandidate(String method, boolean dryRun) {
        long now = System.currentTimeMillis();
        Prefs.p(this).edit()
                .putString("last_candidate_method", method)
                .putLong("last_candidate_ms", now)
                .apply();

        if (dryRun) {
            if (insideHome && Prefs.p(this).getBoolean("inside_home", false)) {
                evaluateTrigger(method, true);
            } else {
                pendingMethod = method;
                pendingDryRun = true;
                pendingMethodAt = now;
                Prefs.p(this).edit()
                        .putString("event_pending_method", method)
                        .putLong("event_pending_ms", pendingMethodAt)
                        .putLong("event_pending_origin_ms", pendingMethodAt)
                        .putBoolean("event_pending_dry_run", true)
                        .apply();
                setFlow("TEST: Konum teyidi bekleniyor",
                        method + " sahte olayı geldi; mevcut canlı konum ev alanına girerse test devam eder. MQTT gönderilmez.");
            }
            return;
        }

        pendingMethod = method;
        pendingDryRun = false;
        pendingMethodAt = now;
        Prefs.p(this).edit()
                .putString("event_pending_method", method)
                .putLong("event_pending_ms", pendingMethodAt)
                .putLong("event_pending_origin_ms", pendingMethodAt)
                .putBoolean("event_pending_dry_run", false)
                .apply();

        setFlow("TAZE KONUM BEKLENİYOR",
                method + " olayı geldi. En fazla " + Prefs.candidateTimeoutMinutes(this) + " dk geçerli. Son kayıtlı konum kullanılmayacak; taze konum bekleniyor.");
        requestFreshLocation(method, true);
    }

    private void evaluateTrigger(String method, boolean dryRun) {
        long nowForLocation = System.currentTimeMillis();
        long fixTime = Prefs.p(this).getLong("last_location_fix_time_ms", 0L);
        long locationAge = fixTime <= 0L ? Long.MAX_VALUE : Math.max(0L, nowForLocation - fixTime);
        float locationAccuracy = Prefs.p(this).getFloat("last_location_accuracy_v5", -1f);

        if (!dryRun && (fixTime <= 0L || locationAge > 15_000L
                || (locationAccuracy >= 0f && locationAccuracy > MAX_TRIGGER_ACCURACY_METERS))) {
            SharedPreferences sp = Prefs.p(this);
            long origin = sp.getLong("event_pending_origin_ms", 0L);
            if (origin <= 0L) origin = nowForLocation;

            if (nowForLocation - origin > Prefs.candidateTimeoutMs(this)) {
                clearPending();
                setFlow("GENEL ADAY ZAMAN AŞIMI",
                        method + ": taze/uygun konum " + Prefs.candidateTimeoutMinutes(this)
                                + " dk içinde alınamadı. MQTT geliş adayı İPTAL.");
                return;
            }

            pendingMethod = method;
            pendingDryRun = false;
            pendingMethodAt = origin;
            long lastRetry = sp.getLong("last_quality_retry_ms", 0L);
            sp.edit()
                    .putString("event_pending_method", method)
                    .putLong("event_pending_ms", origin)
                    .putLong("event_pending_origin_ms", origin)
                    .apply();

            setFlow("KONUM KALİTESİ BEKLENİYOR",
                    method + ": konum yaşı="
                            + (locationAge == Long.MAX_VALUE ? "yok" : (locationAge / 1000L) + "sn")
                            + ", doğruluk=" + Math.round(locationAccuracy)
                            + "m. Taze yüksek doğruluklu fix bekleniyor.");

            if (nowForLocation - lastRetry >= 5000L
                    && !sp.getBoolean("fresh_location_waiting", false)) {
                sp.edit().putLong("last_quality_retry_ms", nowForLocation).apply();
                requestFreshLocation(method + "_kalite", true);
            }
            return;
        }

        if (!Prefs.homeSet(this) || !Prefs.p(this).getBoolean("inside_home", false)) {
            setFlow((dryRun ? "TEST RED" : "RED") + " ev teyidi", method + " reddedildi: ev adresi teyit değil.");
            return;
        }
        long now = System.currentTimeMillis();
        long graceUntil = Prefs.p(this).getLong("startup_grace_until_ms", 0L);
        if (now < graceUntil) {
            setFlow((dryRun ? "TEST RED" : "RED") + " başlangıç koruması",
                    method + " bekletildi: başlangıç koruması aktif, kalan=" + ((graceUntil - now) / 1000L) + " sn");
            return;
        }
        long last = Prefs.lastTrigger(this);
        if (last > 0 && now - last < Prefs.cooldownMs(this)) {
            setFlow((dryRun ? "TEST RED" : "RED") + " 1 saat kilit",
                    method + " reddedildi: ortak kilit " + Prefs.cooldownMinutes(this) + " dk, kalan=" + ((Prefs.cooldownMs(this) - (now - last)) / 1000L) + " sn");
            return;
        }
        if (dryRun) {
            setFlow("TEST ALLOW", method + " koşulları sağlandı. Bu durumda GERÇEK MQTT gönderilirdi.");
            return;
        }
        if (!sending.compareAndSet(false, true)) {
            setFlow("RED gönderim sürüyor", method + " reddedildi: MQTT gönderimi zaten sürüyor.");
            return;
        }

        Prefs.p(this).edit().putBoolean("mqtt_sending", true).apply();
        setFlow("MQTT gönderimi başladı", method + " için MQTT gönderimi başlatıldı.");

        new Thread(() -> {
            Exception lastErr = null;
            String payload = "{\"event\":\"YUSUF_EVE_GELDI\",\"method\":\"" + escape(method)
                    + "\",\"ts\":" + System.currentTimeMillis()
                    + ",\"source\":\"YusufEveGirisAPK\"}";
            try {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        MqttPublisher.publish(Prefs.host(this), Prefs.port(this), Prefs.user(this), Prefs.pass(this), Prefs.topic(this), payload);
                        long ok = System.currentTimeMillis();
                        Prefs.p(this).edit().putLong("last_trigger_ms", ok).putString("last_trigger_method", method).apply();
                        setFlow("MQTT gönderildi", method + " -> MQTT başarıyla gönderildi (deneme " + attempt + ")");
                        return;
                    } catch (Exception e) {
                        lastErr = e;
                        setFlow("MQTT denemesi hata", "Deneme " + attempt + " hata: " + e.getMessage());
                        try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
                    }
                }
                setFlow("MQTT gönderilemedi", lastErr == null ? "Bilinmeyen hata" : lastErr.getMessage());
            } finally {
                sending.set(false);
                Prefs.p(this).edit().putBoolean("mqtt_sending", false).apply();
            }
        }, "mqtt-trigger").start();
    }

    private synchronized void expireTimedWindows() {
        long now = System.currentTimeMillis();
        SharedPreferences sp = Prefs.p(this);

        boolean chargePending = sp.getBoolean("charge_arrival_pending", false);
        long chargeSince = sp.getLong("charge_pending_since_ms", 0L);
        if (chargePending && chargeSince > 0L
                && now - chargeSince > Prefs.chargeTimeoutMs(this)) {
            sp.edit()
                    .putBoolean("charge_arrival_pending", false)
                    .remove("charge_pending_since_ms")
                    .putLong("last_charge_timeout_ms", now)
                    .apply();
            setFlow("ŞARJ ZAMAN AŞIMI",
                    "Telefon " + Prefs.chargeTimeoutMinutes(this)
                            + " dk içinde şarjdan çıkarılmadı. Şarj ile eve giriş yöntemi İPTAL edildi.");
        }

        long origin = sp.getLong("event_pending_origin_ms",
                sp.getLong("event_pending_ms", 0L));
        String method = sp.getString("event_pending_method", "");
        if (origin > 0L && method != null && !method.isEmpty()
                && now - origin > Prefs.candidateTimeoutMs(this)) {
            String expired = method;
            clearPending();
            sp.edit().putLong("last_candidate_timeout_ms", now)
                    .putString("last_candidate_timeout_method", expired)
                    .apply();
            setFlow("GENEL ADAY ZAMAN AŞIMI",
                    expired + " olayı " + Prefs.candidateTimeoutMinutes(this)
                            + " dk içinde tamamlanamadı. Eski olay silindi; sonradan MQTT tetikleyemez.");
        }
    }

    private void handleTestAction(String action) {
        if (ACTION_TEST_BT.equals(action)) {
            setFlow("TEST: Golf6 kesildi", "Sahte test: Golf6 bağlantısı kesildi varsayıldı.");
            requestCandidate("test_bluetooth_golf6", true);
            return;
        }
        if (ACTION_TEST_WIFI.equals(action)) {
            long now = System.currentTimeMillis();
            long awaySince = Prefs.p(this).getLong("wifi_away_since", 0L);
            String cur = Prefs.p(this).getString("current_ssid", "?");
            if (awaySince == 0L || Prefs.ssid(this).equals(cur)) {
                setFlow("TEST: Wi‑Fi olayı", "Şu anda 10 dk Wi‑Fi uzak kalma sayacı aktif değil. Önce ev Wi‑Fi'dan ayrılman gerekir.");
                return;
            }
            long away = now - awaySince;
            if (away < Prefs.wifiAwayMs(this)) {
                setFlow("TEST: Wi‑Fi erken", "Wi‑Fi geri gelirse bile " + Prefs.wifiAwayMinutes(this) + " dk dolmamış olur. Kalan=" + ((Prefs.wifiAwayMs(this) - away) / 1000L) + " sn");
                return;
            }
            setFlow("TEST: Wi‑Fi geri geldi", "Sahte test: Wi‑Fi geri bağlandı ve 10 dk şartı dolu varsayıldı.");
            requestCandidate("test_wifi", true);
            return;
        }
        if (ACTION_TEST_AA.equals(action)) {
            if (carBtConnected) {
                setFlow("TEST: Android Auto reddi", "Golf6 hâlâ bağlı görünüyor; bu durumda Android Auto çıkışı tetik sayılmaz.");
            } else {
                setFlow("TEST: Android Auto", "Sahte test: Android Auto kapandı.");
                requestCandidate("test_android_auto", true);
            }
            return;
        }
        if (ACTION_TEST_CHARGE.equals(action)) {
            if (Prefs.p(this).getBoolean("charge_arrival_pending", false)) {
                setFlow("TEST: Şarjdan çıktı", "Sahte test: ev girişinden sonra şarjdan çıkış oldu.");
                requestCandidate("test_charge", true);
            } else {
                setFlow("TEST: Şarj reddi", "Şu anda şarj-geliş beklemesi aktif değil. Önce ev alanına şarjda giriş gerekir.");
            }
            return;
        }
        if (ACTION_TEST_HOME_ENTER.equals(action)) {
            boolean c = isCharging();
            if (c) {
                setFlow("TEST: Eve giriş", "Sahte test: eve giriş oldu ve telefon şarjda. Sonraki beklenen olay şarjdan çıkış olur.");
            } else {
                setFlow("TEST: Eve giriş", "Sahte test: eve giriş oldu. Sonraki beklenen olay Golf6 ayrılması / Wi‑Fi geri gelişi / Android Auto çıkışı olur.");
            }
            return;
        }
        if (ACTION_TEST_HOME_LEAVE.equals(action)) {
            setFlow("TEST: Evden çıkış", "Sahte test: evden çıkış oldu. Sonraki aşama: Wi‑Fi uzak kalma sayacı / Golf6 bağlantısı / Android Auto izlenir.");
            return;
        }
        if (ACTION_TEST_WIFI_AWAY.equals(action)) {
            setFlow("TEST: Wi‑Fi ayrıldı", "Sahte test: ev Wi‑Fi bağlantısı kesildi. Gerçekte burada 10 dakika geri sayım başlar.");
            return;
        }
        if (ACTION_TEST_WIFI_READY.equals(action)) {
            setFlow("TEST: Wi‑Fi 10 dk tamam", "Sahte test: 10 dakika dışarıda kalındı ve ev Wi‑Fi'ye geri bağlanıldı. Ev konumu + kilit kontrolü yapılır.");
            requestCandidate("test_wifi_10dk_hazir", true);
            return;
        }
        if (ACTION_TEST_BT_CONNECTED.equals(action)) {
            setFlow("TEST: Golf6 bağlı", "Sahte test: Ysf Golf6 bağlı. Sonraki beklenen olay Golf6 bağlantısının kesilmesi.");
            return;
        }
        if (ACTION_TEST_CHARGE_ARRIVAL.equals(action)) {
            setFlow("TEST: Eve şarjda geldi", "Sahte test: telefon başka yerden ev alanına şarjdayken geldi. Sonraki beklenen olay şarjdan çıkarma.");
            return;
        }
        if (ACTION_TEST_FULL.equals(action)) {
            setFlow("TEST: Tam akış", "Sahte test: kaynak olay oluştu. Güncel zaman ayarları ve güvenlik kilitleri kontrol edilir; MQTT gönderilmez.");
            requestCandidate("test_tam_akis", true);
            return;
        }
        if (ACTION_TEST_CHARGE_TIMEOUT.equals(action)) {
            setFlow("TEST: ŞARJ ZAMAN AŞIMI",
                    "Sahte test: şarjdan çıkarma süresi doldu. Gerçekte eski şarj-geliş kaydı silinir ve sonraki şarjdan çıkış MQTT göndermez.");
            return;
        }
        if (ACTION_TEST_CANDIDATE_TIMEOUT.equals(action)) {
            setFlow("TEST: GENEL ADAY ZAMAN AŞIMI",
                    "Sahte test: Bluetooth / Android Auto / Wi‑Fi geliş adayının geçerlilik süresi doldu. Gerçekte eski aday silinir.");
            return;
        }
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void updateNotification(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, notification(text));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_FORCE_LOCATION.equals(action)) {
            setFlow("MANUEL TAZE KONUM", "Kullanıcı yüksek doğruluklu taze konum istedi.");
            requestFreshLocation("manuel_buton", false);
        } else if (action != null && action.startsWith("com.yusuf.evegiris.TEST_")) {
            handleTestAction(action);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        timeoutHandler.removeCallbacksAndMessages(null);
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        try {
            if (fusedLocationClient != null && fusedLocationCallback != null) {
                fusedLocationClient.removeLocationUpdates(fusedLocationCallback);
            }
        } catch (Exception ignored) {}
        try { if (locationManager != null) locationManager.removeUpdates(this); } catch (Exception ignored) {}
        try { if (connectivityManager != null && wifiCallback != null) connectivityManager.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) {}
        Prefs.p(this).edit().putBoolean("mqtt_sending", false).apply();
        setFlow("Servis durdu", "Monitor servis durdu.");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
