package com.yusuf.evegiris;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Single WAN MQTT/TLS connection. No LAN fallback, no persistent GPS requests. */
public final class WanMqttManager {
    public interface StatusListener { void onStatus(boolean connected, String detail); }

    private static final int KEEPALIVE_SECONDS = 120;
    private static final long RECONNECT_CHECK_MS = 30000L;
    private static final long SAME_ALERT_THROTTLE_MS = 60000L;
    private static final String CHANNEL = "yusuf_wan_connection_v8";
    private static final int ALERT_ID = 8042;

    private final Context app;
    private final StatusListener statusListener;
    private final Object connectionMonitor = new Object();
    private volatile MqttAsyncClient client;
    private volatile boolean stopped = true;
    private volatile long lastConnectTry = 0L;
    private volatile int lastState = -1;
    private long lastConnectedAlert = 0L;
    private long lastDisconnectedAlert = 0L;

    public WanMqttManager(Context context, StatusListener listener) {
        app = context.getApplicationContext();
        statusListener = listener;
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Yusuf WAN bağlantı durumu", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("WAN MQTT oturumu açılınca ve kopunca tek bildirim");
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void notifyTransition(boolean online, String details) {
        long now = System.currentTimeMillis();
        if (online) {
            if (now - lastConnectedAlert < SAME_ALERT_THROTTLE_MS) return;
            lastConnectedAlert = now;
        } else {
            if (now - lastDisconnectedAlert < SAME_ALERT_THROTTLE_MS) return;
            lastDisconnectedAlert = now;
        }
        if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        Intent intent = new Intent(app, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(app, 9, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(online ? "WAN bağlantısı kuruldu" : "WAN bağlantısı koptu")
                .setContentText(online ? "MQTT/TLS oturumu açık, keepalive 120 sn" : "Otomatik yeniden bağlanma deneniyor")
                .setAutoCancel(true).setContentIntent(pending);
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ALERT_ID, b.build());
    }

    private synchronized void transition(boolean online, String detail) {
        int next = online ? 1 : 0;
        SharedPreferences sp = Prefs.p(app);
        sp.edit().putBoolean("wan_connected", online)
                .putString("wan_status", detail)
                .putLong("wan_last_status_ms", System.currentTimeMillis()).apply();
        if (lastState == next) return;
        int previous = lastState;
        lastState = next;
        Prefs.addEvent(app, online ? "WAN BAĞLANDI" : "WAN BAĞLANTI KESİLDİ", detail);
        if (statusListener != null) statusListener.onStatus(online, detail);
        // Initial offline state is recorded but not announced as a drop.
        if (online || previous == 1) notifyTransition(online, detail);
        synchronized (connectionMonitor) { connectionMonitor.notifyAll(); }
    }

    private String getStableClientId() {
        SharedPreferences sp = Prefs.p(app);
        String id = sp.getString("wan_mqtt_client_id", "");
        if (!id.isEmpty()) return id;
        id = "yusuf-android-" + UUID.randomUUID().toString().substring(0, 12);
        sp.edit().putString("wan_mqtt_client_id", id).apply();
        return id;
    }

    private MqttConnectOptions connectionOptions() {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setUserName(Prefs.wanUser(app));
        o.setPassword(Prefs.wanPassword(app).toCharArray());
        o.setKeepAliveInterval(KEEPALIVE_SECONDS);
        o.setConnectionTimeout(8);
        o.setAutomaticReconnect(true);
        o.setCleanSession(true);
        o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        o.setHttpsHostnameVerificationEnabled(true);
        // Will is sent by broker if the TCP/TLS session disappears unexpectedly.
        o.setWill(Prefs.topic(app) + "/status",
                "offline".getBytes(StandardCharsets.UTF_8), 1, false);
        return o;
    }

    public synchronized void start() {
        stop();
        if (!Prefs.wanEnabled(app)) return;
        String host = Prefs.wanHost(app).trim();
        if (!Prefs.wanConfigured(app)) {
            transition(false, "WAN broker adı / kullanıcı / şifre eksik. LAN/VPN kullanılmayacak.");
            return;
        }
        stopped = false;
        try {
            final MqttAsyncClient c = new MqttAsyncClient(
                    "ssl://" + host + ":" + Prefs.wanPort(app),
                    getStableClientId(), new MemoryPersistence());
            client = c;
            c.setCallback(new MqttCallbackExtended() {
                @Override public void connectComplete(boolean reconnect, String serverURI) {
                    if (stopped || c != client) return;
                    transition(true, reconnect ? "WAN TLS yeniden bağlandı" : "WAN TLS bağlı");
                    try {
                        c.subscribe(Prefs.topic(app) + "/ack", 1);
                    } catch (MqttException e) {
                        Prefs.addEvent(app, "WAN ACK abonelik hata", safeError(e));
                    }
                }
                @Override public void connectionLost(Throwable cause) {
                    if (!stopped && c == client) transition(false, "Bağlantı koptu: " + safeError(cause));
                }
                @Override public void messageArrived(String topic, MqttMessage message) {
                    if (!topic.equals(Prefs.topic(app) + "/ack")) return;
                    String body = new String(message.getPayload(), StandardCharsets.UTF_8);
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(body);
                        String id = obj.optString("event_id", "");
                        if (!id.isEmpty()) {
                            Prefs.p(app).edit().putString("wan_last_ha_ack", id)
                                    .putLong("wan_last_ha_ack_ms", System.currentTimeMillis()).apply();
                            Prefs.addEvent(app, "HA ACK ALINDI", "event_id=" + id);
                        }
                    } catch (Exception ignored) {
                        Prefs.addEvent(app, "WAN ACK geçersiz", "ACK JSON çözümlenemedi");
                    }
                }
                @Override public void deliveryComplete(IMqttDeliveryToken token) { }
            });
            connectIfNeeded();
        } catch (Exception e) {
            transition(false, "WAN başlatılamadı: " + safeError(e));
        }
    }

    public void ensureConnected() {
        if (stopped || !Prefs.wanEnabled(app)) return;
        MqttAsyncClient c = client;
        if (c == null) { start(); return; }
        if (!c.isConnected()) connectIfNeeded();
    }

    private synchronized void connectIfNeeded() {
        MqttAsyncClient c = client;
        if (stopped || c == null || c.isConnected()) return;
        long now = System.currentTimeMillis();
        if (now - lastConnectTry < RECONNECT_CHECK_MS) return;
        lastConnectTry = now;
        try {
            c.connect(connectionOptions(), null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken token) { /* connectComplete updates state */ }
                @Override public void onFailure(IMqttToken token, Throwable exception) {
                    if (!stopped) transition(false, "WAN TLS bağlanamadı: " + safeError(exception));
                }
            });
        } catch (MqttException e) {
            // An in-progress automatic Paho reconnect can report CONNECT_IN_PROGRESS.
            if (!stopped && !c.isConnected()) transition(false, "WAN bekleniyor: " + safeError(e));
        }
    }

    /** Called from an existing worker thread, never from Android's UI thread. */
    public void publish(String payload) throws Exception {
        ensureConnected();
        long deadline = System.currentTimeMillis() + 8000L;
        MqttAsyncClient c;
        while ((c = client) == null || !c.isConnected()) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0 || stopped) throw new IOException("WAN TLS bağlı değil; MQTT gönderilmedi");
            synchronized (connectionMonitor) { connectionMonitor.wait(Math.min(1000L, left)); }
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        IMqttDeliveryToken token = c.publish(Prefs.topic(app), bytes, 1, false);
        token.waitForCompletion(12000);
        // QoS1 PUBACK = broker acknowledged. HA ACK is logged separately if configured.
    }

    public synchronized void stop() {
        stopped = true;
        MqttAsyncClient old = client;
        client = null;
        if (old != null) {
            try { old.disconnectForcibly(1000, 1000); } catch (Exception ignored) {}
            try { old.close(); } catch (Exception ignored) {}
        }
        lastConnectTry = 0;
        lastState = -1;
        Prefs.p(app).edit().putBoolean("wan_connected", false).apply();
        synchronized (connectionMonitor) { connectionMonitor.notifyAll(); }
    }

    private static String safeError(Throwable err) {
        if (err == null) return "Ayrıntı yok";
        String s = err.getMessage();
        return s == null || s.isEmpty() ? err.getClass().getSimpleName() : s;
    }
}
