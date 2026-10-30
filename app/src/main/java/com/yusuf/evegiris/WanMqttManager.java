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
import android.net.ConnectivityManager;
import android.net.Network;

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
    private ConnectivityManager networkManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network activeDefaultNetwork;
    private volatile long networkEpoch = 0L;
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

    private javax.net.ssl.SSLSocketFactory yusufPrivateCaSocketFactory() throws Exception {
        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.cert.Certificate root;
        try (java.io.InputStream in = app.getAssets().open("yusuf_v8_wan_ca.pem")) {
            root = cf.generateCertificate(in);
        }
        java.security.KeyStore ks = java.security.KeyStore.getInstance(
                java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("yusuf_v8_ca", root);
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        javax.net.ssl.SSLContext ssl = javax.net.ssl.SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        final javax.net.ssl.SSLSocketFactory trusted = ssl.getSocketFactory();
        return new javax.net.ssl.SSLSocketFactory() {
            private java.net.Socket checkEndpoint(java.net.Socket socket) {
                if (socket instanceof javax.net.ssl.SSLSocket) {
                    javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket) socket;
                    javax.net.ssl.SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    tls.setSSLParameters(parameters);
                }
                return socket;
            }
            @Override public String[] getDefaultCipherSuites() {
                return trusted.getDefaultCipherSuites();
            }
            @Override public String[] getSupportedCipherSuites() {
                return trusted.getSupportedCipherSuites();
            }
            @Override public java.net.Socket createSocket(java.net.Socket s, String h, int p,
                                                          boolean autoclose) throws IOException {
                return checkEndpoint(trusted.createSocket(s, h, p, autoclose));
            }
            @Override public java.net.Socket createSocket(String h, int p) throws IOException {
                return checkEndpoint(trusted.createSocket(h, p));
            }
            @Override public java.net.Socket createSocket(java.net.InetAddress h, int p)
                    throws IOException {
                return checkEndpoint(trusted.createSocket(h, p));
            }
            @Override public java.net.Socket createSocket(String h, int p,
                                                          java.net.InetAddress local, int lp)
                    throws IOException {
                return checkEndpoint(trusted.createSocket(h, p, local, lp));
            }
            @Override public java.net.Socket createSocket(java.net.InetAddress h, int p,
                                                          java.net.InetAddress local, int lp)
                    throws IOException {
                return checkEndpoint(trusted.createSocket(h, p, local, lp));
            }
        };
    }

    private MqttConnectOptions connectionOptions() {
        return connectionOptions(false);
    }

    private MqttConnectOptions connectionOptions(boolean diagnostic) {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setUserName(Prefs.wanUser(app));
        o.setPassword(Prefs.wanPassword(app).toCharArray());
        o.setKeepAliveInterval(KEEPALIVE_SECONDS);
        o.setConnectionTimeout(8);
        // Tek yeniden baglanti yoneticisi: MonitorService 30 saniyede ensureConnected().
        // Paho otomatik reconnect acikken ikinci c.connect() yarisi gorulebilir.
        o.setAutomaticReconnect(false);
        o.setCleanSession(true);
        o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        o.setHttpsHostnameVerificationEnabled(true);
        // SADECE HA'da uretilen CA guvenilir: guvenlik kontrolunu ASLA kapatma.
        try {
            o.setSocketFactory(yusufPrivateCaSocketFactory());
        } catch (Exception e) {
            throw new IllegalStateException("WAN TLS guven sertifikasi yuklenemedi", e);
        }
        // Will is sent by broker if the TCP/TLS session disappears unexpectedly.
        if (!diagnostic) {
            o.setWill(Prefs.topic(app) + "/status",
                    "offline".getBytes(StandardCharsets.UTF_8), 1, false);
        }
        return o;
    }

    /** Android varsayilan agi Wi-Fi/mobil olarak degisince eski TCP oturumu birakilir. */
    private synchronized void watchDefaultNetwork() {
        if (networkCallback != null) return;
        final ConnectivityManager manager = (ConnectivityManager)
                app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return;
        networkManager = manager;
        activeDefaultNetwork = manager.getActiveNetwork();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                defaultNetworkChanged(this, network);
            }
            @Override public void onLost(Network network) {
                if (network != null && network.equals(activeDefaultNetwork)) {
                    defaultNetworkChanged(this, null);
                }
            }
        };
        networkCallback = cb;
        try {
            manager.registerDefaultNetworkCallback(cb);
        } catch (Exception e) {
            networkCallback = null;
            networkManager = null;
            Prefs.addEvent(app, "WAN AĞ İZLEME", "Ag callback: " + safeError(e));
        }
    }

    private synchronized void defaultNetworkChanged(ConnectivityManager.NetworkCallback source,
                                                     Network next) {
        if (stopped || source != networkCallback) return;
        Network previous = activeDefaultNetwork;
        if (previous == next || (previous != null && previous.equals(next))) return;
        activeDefaultNetwork = next;
        final long version = ++networkEpoch;
        Prefs.addEvent(app, "WAN AĞ DEĞİŞTİ", "Wi-Fi / mobil veri gecisi; yeni TLS el sikisma");
        new Thread(() -> {
            try { Thread.sleep(1400L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            synchronized (WanMqttManager.this) {
                if (stopped || source != networkCallback || version != networkEpoch) return;
                transition(false, "Ag degisti; taze TCP/TLS + MQTT CONNECT basliyor");
                start();
            }
        }, "yusuf-v83-network-rehandshake").start();
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
        watchDefaultNetwork();
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
        } catch (Exception e) {
            // An in-progress automatic Paho reconnect can report CONNECT_IN_PROGRESS.
            if (!stopped && !c.isConnected()) transition(false, "WAN bekleniyor: " + safeError(e));
        }
    }

    /** Java TLS + MQTT 3.1.1 dogrudan CONNECT. No publish, no arrival, no HA trigger. */
    private String rawMqttConnectProbe() throws Exception {
        final String host = Prefs.wanHost(app);
        final int port = Prefs.wanPort(app);
        final javax.net.ssl.SSLSocketFactory sf = yusufPrivateCaSocketFactory();
        try (javax.net.ssl.SSLSocket socket =
                (javax.net.ssl.SSLSocket) sf.createSocket(host, port)) {
            socket.setSoTimeout(9000);
            socket.startHandshake();
            final String tlsVersion = socket.getSession().getProtocol();
            final java.io.ByteArrayOutputStream msg = new java.io.ByteArrayOutputStream();
            final java.io.DataOutputStream m = new java.io.DataOutputStream(msg);
            writeMqttUtf8(m, "MQTT");
            m.writeByte(4); // MQTT 3.1.1 protocol level
            m.writeByte(0xC2); // clean session + username + password
            m.writeShort(30);
            writeMqttUtf8(m, "yusuf-v83-raw-" + UUID.randomUUID().toString().substring(0, 9));
            writeMqttUtf8(m, Prefs.wanUser(app));
            writeMqttUtf8(m, Prefs.wanPassword(app));
            final byte[] body = msg.toByteArray();
            final java.io.OutputStream out = socket.getOutputStream();
            out.write(0x10);
            int length = body.length;
            do {
                int digit = length % 128;
                length /= 128;
                if (length > 0) digit |= 128;
                out.write(digit);
            } while (length > 0);
            out.write(body);
            out.flush();
            final java.io.InputStream in = socket.getInputStream();
            int kind = in.read(), rem = in.read(), flags = in.read(), code = in.read();
            if (kind != 0x20 || rem != 2 || flags != 0 || code < 0)
                throw new IOException("MQTT CONNACK yaniti okunamadi");
            return tlsVersion + ", MQTT3.1.1 CONNACK=" + code
                    + (code == 0 ? " (PAROLA KABUL)" : " (GIRIS RET)");
        }
    }

    private static void writeMqttUtf8(java.io.DataOutputStream out, String value)
            throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) throw new IOException("MQTT metni cok uzun");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    /** Independent, real MQTT/TLS session: never sends an arrival event. */
    public static String liveTest(Context context, boolean publishProbe) throws Exception {
        if (!Prefs.wanConfigured(context))
            throw new IOException("Statik WAN IP / kullanıcı / şifre eksik");
        WanMqttManager helper = new WanMqttManager(context, null);
        MqttAsyncClient testClient = new MqttAsyncClient(
                "ssl://" + Prefs.wanHost(context) + ":" + Prefs.wanPort(context),
                "yusuf-v82-realtest-" + UUID.randomUUID().toString().substring(0, 12),
                new MemoryPersistence());
        try {
            MqttConnectOptions opts = helper.connectionOptions(true);
            opts.setAutomaticReconnect(false);
            try {
                testClient.connect(opts).waitForCompletion(14000);
            } catch (Exception pahoError) {
                String rawResult;
                try { rawResult = helper.rawMqttConnectProbe(); }
                catch (Exception rawError) { rawResult = "HAM MQTT/TLS HATA: " + explainError(rawError); }
                throw new IOException("PAHO: " + explainError(pahoError)
                        + " | Bagimsiz TLS+MQTT: " + rawResult, pahoError);
            }
            if (!testClient.isConnected()) throw new IOException("MQTT CONNACK başarısız");
            if (publishProbe) {
                // ACL permits only the special probe topic. Never publish to the arrival topic.
                String probe = "v82-test:" + UUID.randomUUID();
                IMqttDeliveryToken sent = testClient.publish("yusuf_v81_probe/up",
                        probe.getBytes(StandardCharsets.UTF_8), 1, false);
                sent.waitForCompletion(12000);
                return "BAŞARILI: Gerçek TLS + MQTT CONNACK + QoS1 PUBACK alındı. "
                        + "Test konusu: yusuf_v81_probe/up. Eve giriş tetiklenmedi. "
                        + "(HA ACK ayrı doğrulanır.)";
            }
            IMqttToken subscription = testClient.subscribe(Prefs.topic(context) + "/ack", 1);
            subscription.waitForCompletion(9000);
            return "BAŞARILI: Gerçek TLS sertifika kontrolü, MQTT CONNACK ve ACK aboneliği tamam.";
        } finally {
            try {
                if (testClient.isConnected()) testClient.disconnect().waitForCompletion(4000);
            } catch (Exception ignored) { }
            try { testClient.close(); } catch (Exception ignored) { }
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
        ++networkEpoch;
        if (networkManager != null && networkCallback != null) {
            try { networkManager.unregisterNetworkCallback(networkCallback); }
            catch (Exception ignored) { }
        }
        networkCallback = null;
        networkManager = null;
        activeDefaultNetwork = null;
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

    public static String explainError(Throwable err) {
        if (err == null) return "Ayrıntı yok";
        StringBuilder s = new StringBuilder();
        java.util.IdentityHashMap<Throwable, Boolean> seen = new java.util.IdentityHashMap<>();
        Throwable cur = err;
        for (int depth = 0; cur != null && depth < 5 && !seen.containsKey(cur); depth++) {
            seen.put(cur, true);
            if (s.length() > 0) s.append(" | neden: ");
            s.append(cur.getClass().getSimpleName());
            if (cur instanceof MqttException)
                s.append(" (MQTT kod ").append(((MqttException) cur).getReasonCode()).append(")");
            String message = cur.getMessage();
            if (message != null && !message.trim().isEmpty())
                s.append(": ").append(message.trim());
            cur = cur.getCause();
        }
        return s.length() > 700 ? s.substring(0, 700) : s.toString();
    }

    private static String safeError(Throwable err) { return explainError(err); }
}
