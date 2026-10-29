package com.yusuf.evegiris;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Real MQTT QoS1 PUBACK and HTTPS safeProbe; never sends YUSUF_EVE_GELDI. */
public final class RealNetworkDiagnostics {
    private RealNetworkDiagnostics() {}

    private static boolean transport(NetworkCapabilities c, int type) {
        return c != null && c.hasTransport(type);
    }

    private static Network pickNetwork(ConnectivityManager cm, String mode) {
        Network selected = null;
        Network backup = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null) continue;
            boolean vpn = transport(caps, NetworkCapabilities.TRANSPORT_VPN);
            boolean wifi = transport(caps, NetworkCapabilities.TRANSPORT_WIFI);
            boolean mobile = transport(caps, NetworkCapabilities.TRANSPORT_CELLULAR);
            if ("TAILSCALE".equals(mode) && vpn) return n;
            if ("LAN".equals(mode) && wifi && !vpn) return n;
            if ("WAN".equals(mode) && mobile && !vpn) selected = n;
            if ("WAN".equals(mode) && wifi && !vpn) backup = n;
        }
        return selected != null ? selected : backup;
    }

    private static void putUtf(ByteArrayOutputStream b, String value) throws Exception {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        if (raw.length > 65535) throw new Exception("MQTT metni fazla uzun");
        b.write((raw.length >>> 8) & 255);
        b.write(raw.length & 255);
        b.write(raw);
    }

    private static void length(ByteArrayOutputStream dst, int size) {
        do {
            int digit = size % 128;
            size /= 128;
            if (size > 0) digit |= 0x80;
            dst.write(digit);
        } while (size > 0);
    }

    private static void packet(OutputStream dst, int header, byte[] body) throws Exception {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(header);
        length(p, body.length);
        p.write(body);
        dst.write(p.toByteArray());
        dst.flush();
    }

    private static int readLength(DataInputStream in) throws Exception {
        int value = 0, mult = 1;
        for (int count = 0; count < 4; count++) {
            int b = in.readUnsignedByte();
            value += (b & 127) * mult;
            if ((b & 128) == 0) return value;
            mult *= 128;
        }
        throw new Exception("MQTT length yanlis");
    }

    private static String mqttTest(Context ctx, Network route) throws Exception {
        String host = Prefs.host(ctx);
        int port = Prefs.port(ctx);
        String user = Prefs.user(ctx), pass = Prefs.pass(ctx);
        if (host == null || host.trim().isEmpty()) throw new Exception("MQTT sunucu adresi bos");
        if (port < 1 || port > 65535) throw new Exception("MQTT port gecersiz");
        long started = System.currentTimeMillis();
        try (Socket socket = route.getSocketFactory().createSocket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            String local = socket.getLocalAddress().getHostAddress();
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            ByteArrayOutputStream b = new ByteArrayOutputStream();
            putUtf(b, "MQTT");
            b.write(4);  // MQTT 3.1.1
            boolean hasUser = user != null && !user.isEmpty();
            boolean hasPass = pass != null && !pass.isEmpty();
            b.write(0x02 | ((hasUser || hasPass) ? 0x80 : 0) | (hasPass ? 0x40 : 0));
            b.write(0); b.write(20);
            putUtf(b, "yf-test-" + UUID.randomUUID().toString().substring(0, 12));
            if (hasUser || hasPass) putUtf(b, user == null ? "" : user);
            if (hasPass) putUtf(b, pass);
            packet(out, 0x10, b.toByteArray());

            int header = in.readUnsignedByte(), receivedLength = readLength(in);
            if (header != 0x20 || receivedLength != 2) throw new Exception("CONNACK formati hatali");
            in.readUnsignedByte();
            int result = in.readUnsignedByte();
            if (result != 0) throw new Exception("MQTT CONNACK=" + result + (result == 4 || result == 5 ? " (kullanici/parola/yetki reddedildi)" : ""));

            // Ayrı bir tanı konusu ve güvenli içerik: gerçek eve-giriş topic'i değil.
            String topic = "diagnostics/yusuf-eve-giris/network/" + UUID.randomUUID().toString();
            ByteArrayOutputStream publish = new ByteArrayOutputStream();
            putUtf(publish, topic);
            int packetId = 1;
            publish.write(0); publish.write(packetId);
            publish.write(("{\"kind\":\"network_diagnostic\",\"trigger\":false}")
                    .getBytes(StandardCharsets.UTF_8));
            packet(out, 0x32, publish.toByteArray()); // QoS1, retain=false

            int ackHeader = in.readUnsignedByte(), ackLength = readLength(in);
            if (ackHeader != 0x40 || ackLength != 2) throw new Exception("PUBACK gelmedi (paket=" + ackHeader + ")");
            int ackId = (in.readUnsignedByte() << 8) | in.readUnsignedByte();
            if (ackId != packetId) throw new Exception("PUBACK id uyusmuyor");
            packet(out, 0xE0, new byte[0]);
            return "BAŞARILI: TCP + MQTT CONNACK=0 + QoS1 PUBACK\nKaynak IP: " + local
                    + "\nHedef: " + host + ":" + port
                    + "\nSüre: " + (System.currentTimeMillis() - started) + " ms"
                    + "\nTest konusu: diagnostics/yusuf-eve-giris/network/ (ışık tetiklemez)";
        }
    }

    public static String run(Context ctx, String mode) {
        long started = System.currentTimeMillis();
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) throw new Exception("ConnectivityManager yok");
            Network network = pickNetwork(cm, mode);
            if (network == null) {
                if ("TAILSCALE".equals(mode)) throw new Exception("VPN agi gorunmuyor. Tailscale baglantisini kontrol et.");
                if ("LAN".equals(mode)) throw new Exception("Wi-Fi agi gorunmuyor. Ev Wi-Fi baglantisini kontrol et.");
                throw new Exception("WAN icin Wi-Fi/mobil fiziksel ag gorunmuyor");
            }
            NetworkCapabilities cap = cm.getNetworkCapabilities(network);
            String networkName = "WAN".equals(mode)
                ? (transport(cap, NetworkCapabilities.TRANSPORT_CELLULAR) ? "MOBIL" : "WI-FI")
                : ("LAN".equals(mode) ? "WI-FI" : "VPN");
            String header = "[" + mode + " / " + networkName + "]\n";
            if ("WAN".equals(mode)) {
                // HTTPS / HMAC / TLS pin ile gerçek ama güvenli probe: gateway test_ yöntemini reddeder.
                int code = HttpsGatewayPublisher.safeProbe(network);
                return header + "BAŞARILI: HTTPS 449 TLS + SPKI + HMAC test yaniti HTTP " + code
                        + "\nSüre: " + (System.currentTimeMillis() - started) + " ms"
                        + "\nTest olayi Home Assistant'a iletilmez.";
            }
            return header + mqttTest(ctx, network);
        } catch (Throwable err) {
            String msg = err.getMessage();
            if (msg == null || msg.trim().isEmpty()) msg = "Ayrintili hata mesaji yok";
            // Gizli yapılandırma değerlerini veya parolayı loglama.
            String cause = err.getClass().getSimpleName();
            return "[" + mode + "]\nBAŞARISIZ: " + cause + " - " + msg
                    + "\nSüre: " + (System.currentTimeMillis() - started) + " ms"
                    + "\nGerçek eve-giriş bildirimi gönderilmedi.";
        }
    }
}
