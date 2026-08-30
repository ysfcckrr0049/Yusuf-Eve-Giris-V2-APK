package com.yusuf.evegiris;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class MqttPublisher {
    private static final AtomicInteger PACKET_ID = new AtomicInteger(1);

    private static byte[] utf(String s) throws Exception {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((b.length >> 8) & 0xff);
        o.write(b.length & 0xff);
        o.write(b);
        return o.toByteArray();
    }

    private static void remaining(ByteArrayOutputStream o, int n) {
        do {
            int d = n % 128;
            n /= 128;
            if (n > 0) d |= 0x80;
            o.write(d);
        } while (n > 0);
    }

    private static int readRemainingLength(DataInputStream in) throws Exception {
        int multiplier = 1;
        int value = 0;
        int loops = 0;
        int encoded;

        do {
            encoded = in.readUnsignedByte();
            value += (encoded & 127) * multiplier;
            multiplier *= 128;
            loops++;
            if (loops > 4) {
                throw new Exception("MQTT remaining length gecersiz");
            }
        } while ((encoded & 128) != 0);

        return value;
    }

    private static int nextPacketId() {
        while (true) {
            int cur = PACKET_ID.getAndUpdate(v -> v >= 65535 ? 1 : v + 1);
            if (cur > 0 && cur <= 65535) return cur;
        }
    }

    private static Socket connect(
            String host,
            int port,
            String user,
            String pass
    ) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(5000);
        s.setTcpNoDelay(true);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(utf("MQTT"));
        body.write(4);

        int flags = 0x02;
        boolean hasPass = pass != null && !pass.isEmpty();
        boolean hasUser = user != null && !user.isEmpty();

        if (hasUser || hasPass) flags |= 0x80;
        if (hasPass) flags |= 0x40;

        body.write(flags);
        body.write(0);
        body.write(20);
        body.write(utf("yusuf-" + UUID.randomUUID().toString().substring(0, 8)));

        if (hasUser || hasPass) body.write(utf(user == null ? "" : user));
        if (hasPass) body.write(utf(pass));

        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x10);
        remaining(pkt, body.size());
        pkt.write(body.toByteArray());

        OutputStream out = s.getOutputStream();
        out.write(pkt.toByteArray());
        out.flush();

        DataInputStream in = new DataInputStream(s.getInputStream());

        int header = in.readUnsignedByte();
        int len = readRemainingLength(in);

        if (header != 0x20 || len < 2) {
            throw new Exception("MQTT CONNACK gelmedi");
        }

        in.readUnsignedByte();
        int rc = in.readUnsignedByte();

        for (int i = 2; i < len; i++) {
            in.readUnsignedByte();
        }

        if (rc != 0) {
            throw new Exception("MQTT baglanti reddedildi, kod=" + rc);
        }

        return s;
    }

    public static void test(
            String host,
            int port,
            String user,
            String pass
    ) throws Exception {
        Socket s = connect(host, port, user, pass);
        try {
            s.getOutputStream().write(new byte[]{(byte) 0xE0, 0x00});
            s.getOutputStream().flush();
        } finally {
            s.close();
        }
    }

    public static void publish(
            String host,
            int port,
            String user,
            String pass,
            String topic,
            String payload
    ) throws Exception {
        Socket s = connect(host, port, user, pass);

        try {
            int packetId = nextPacketId();
            byte[] topicBytes = utf(topic);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(topicBytes);
            body.write((packetId >> 8) & 0xff);
            body.write(packetId & 0xff);
            body.write(payloadBytes);

            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x32); // PUBLISH QoS1, DUP=0, RETAIN=0
            remaining(pkt, body.size());
            pkt.write(body.toByteArray());

            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());

            out.write(pkt.toByteArray());
            out.flush();

            int header = in.readUnsignedByte();
            int len = readRemainingLength(in);

            if ((header & 0xF0) != 0x40 || len != 2) {
                throw new Exception("MQTT PUBACK gelmedi");
            }

            int ackId = (in.readUnsignedByte() << 8) | in.readUnsignedByte();

            if (ackId != packetId) {
                throw new Exception(
                        "MQTT PUBACK packet id uyusmuyor: beklenen="
                                + packetId + " gelen=" + ackId
                );
            }

            out.write(new byte[]{(byte) 0xE0, 0x00});
            out.flush();

        } finally {
            s.close();
        }
    }

    private MqttPublisher() {}
}
