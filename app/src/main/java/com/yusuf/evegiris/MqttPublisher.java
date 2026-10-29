package com.yusuf.evegiris;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class MqttPublisher {
    private static final AtomicInteger PACKET_ID = new AtomicInteger(1);
    private static final int CONNECT_TIMEOUT_MS = 4500;
    private static final int READ_TIMEOUT_MS = 6500;

    private static byte[] utf(String str) throws IOException {
        byte[] b = (str == null ? "" : str).getBytes(StandardCharsets.UTF_8);
        if (b.length > 65535) throw new IOException("MQTT UTF-8 alan uzunlugu gecersiz");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((b.length >>> 8) & 255);
        o.write(b.length & 255);
        o.write(b);
        return o.toByteArray();
    }

    private static void remaining(ByteArrayOutputStream o, int n) {
        do {
            int v = n % 128;
            n /= 128;
            if (n > 0) v |= 0x80;
            o.write(v);
        } while (n > 0);
    }

    private static int readByte(DataInputStream in, String phase) throws IOException {
        try {
            return in.readUnsignedByte();
        } catch (EOFException e) {
            throw new IOException(phase + ": Broker TCP baglantisini cevapsiz kapatti (EOF)", e);
        } catch (SocketTimeoutException e) {
            throw new IOException(phase + ": Broker cevap suresi doldu (timeout)", e);
        }
    }

    private static int readRemainingLength(DataInputStream in, String phase) throws IOException {
        int multiplier = 1;
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int v = readByte(in, phase);
            value += (v & 127) * multiplier;
            if ((v & 128) == 0) return value;
            multiplier *= 128;
        }
        throw new IOException(phase + ": MQTT uzunluk alani gecersiz");
    }

    private static Socket openSocket(Context context, boolean preferHomeWifi, String host, int port)
            throws IOException {
        IOException wifiError = null;
        if (context != null && preferHomeWifi) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    for (Network network : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                        Socket socket = network.getSocketFactory().createSocket();
                        try {
                            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                            socket.setSoTimeout(READ_TIMEOUT_MS);
                            socket.setTcpNoDelay(true);
                            return socket;
                        } catch (IOException e) {
                            wifiError = e;
                            try { socket.close(); } catch (IOException ignored) {}
                        }
                    }
                }
            } catch (SecurityException e) {
                // Android ag izinleri kisitliysa normal varsayilan ag yolu denenir.
            }
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
            if (wifiError != null) {
                throw new IOException("Ev Wi-Fi ve varsayilan agdan MQTT TCP erisim hatasi: "
                        + e.getClass().getSimpleName() + " / " + readable(e), e);
            }
            throw new IOException("MQTT TCP erisim hatasi: " + e.getClass().getSimpleName()
                    + " / " + readable(e), e);
        }
    }

    private static String readable(Throwable e) {
        String message = e.getMessage();
        return (message == null || message.trim().isEmpty())
                ? e.getClass().getSimpleName() : message;
    }

    private static Socket connect(Context context, boolean preferHomeWifi,
                                  String host, int port, String user, String pass) throws IOException {
        Socket socket = openSocket(context, preferHomeWifi, host, port);
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(utf("MQTT"));
            body.write(4); // MQTT 3.1.1
            boolean hasPass = pass != null && !pass.isEmpty();
            boolean hasUser = user != null && !user.isEmpty();
            int flags = 0x02;
            if (hasUser || hasPass) flags |= 0x80;
            if (hasPass) flags |= 0x40;
            body.write(flags);
            body.write(0);
            body.write(20);
            body.write(utf("yusuf-" + UUID.randomUUID().toString().substring(0, 8)));
            if (hasUser || hasPass) body.write(utf(user));
            if (hasPass) body.write(utf(pass));

            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.write(0x10);
            remaining(packet, body.size());
            packet.write(body.toByteArray());

            OutputStream out = socket.getOutputStream();
            out.write(packet.toByteArray());
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int header = readByte(in, "CONNACK");
            int len = readRemainingLength(in, "CONNACK");
            if (header != 0x20 || len != 2) {
                throw new IOException("MQTT CONNECT reddedildi: CONNACK basligi/uzunlugu hatali"
                        + " header=" + header + " len=" + len);
            }
            readByte(in, "CONNACK flags");
            int rc = readByte(in, "CONNACK code");
            if (rc == 4) throw new IOException("MQTT CONNACK 4: Kullanici adi veya parola hatali");
            if (rc == 5) throw new IOException("MQTT CONNACK 5: Broker yetki vermedi (kullanici/parola)");
            if (rc != 0) throw new IOException("MQTT CONNACK hata kodu: " + rc);
            return socket;
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw e;
        } catch (RuntimeException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw new IOException("MQTT CONNECT islem hatasi: " + readable(e), e);
        }
    }

    public static void test(String host, int port, String user, String pass) throws Exception {
        Socket socket = connect(null, false, host, port, user, pass);
        try {
            socket.getOutputStream().write(new byte[]{(byte) 0xE0, 0});
            socket.getOutputStream().flush();
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public static void publish(Context context, boolean preferHomeWifi,
                               String host, int port, String user, String pass,
                               String topic, String payload) throws Exception {
        Socket socket = connect(context, preferHomeWifi, host, port, user, pass);
        try {
            int packetId = PACKET_ID.getAndUpdate(v -> v >= 65535 ? 1 : v + 1);
            byte[] topicBytes = utf(topic);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(topicBytes);
            body.write((packetId >>> 8) & 255);
            body.write(packetId & 255);
            body.write(payloadBytes);
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.write(0x32); // PUBLISH QoS1 RETAIN=0
            remaining(packet, body.size());
            packet.write(body.toByteArray());
            OutputStream out = socket.getOutputStream();
            out.write(packet.toByteArray());
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int header;
            try {
                header = readByte(in, "PUBACK");
            } catch (IOException e) {
                throw new IOException("MQTT PUBLISH gonderildi ama broker PUBACK vermedi: "
                        + readable(e) + ". Topic ACL/yetki ve broker loglarini kontrol et", e);
            }
            int len = readRemainingLength(in, "PUBACK");
            if (header != 0x40 || len != 2) {
                throw new IOException("MQTT PUBACK gecersiz (header=" + header + ", len=" + len + ")");
            }
            int ackId = (readByte(in, "PUBACK packetId") << 8)
                    | readByte(in, "PUBACK packetId");
            if (ackId != packetId) {
                throw new IOException("MQTT PUBACK ID uyusmuyor: beklenen=" + packetId + " gelen=" + ackId);
            }
            // PUBACK alindigi anda basarili sayilir. DISCONNECT hatasi bunu bozamaz.
            try {
                out.write(new byte[]{(byte) 0xE0, 0});
                out.flush();
            } catch (IOException ignored) {}
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private MqttPublisher() {}
}
