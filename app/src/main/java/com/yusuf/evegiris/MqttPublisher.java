package com.yusuf.evegiris;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class MqttPublisher {
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

    private static Socket connect(String host, int port, String user, String pass) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setSoTimeout(5000);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(utf("MQTT"));
        body.write(4); // MQTT 3.1.1
        int flags = 0x02; // clean session
        boolean hasPass = pass != null && !pass.isEmpty();
        boolean hasUser = user != null && !user.isEmpty();
        if (hasUser || hasPass) flags |= 0x80;
        if (hasPass) flags |= 0x40;
        body.write(flags);
        body.write(0);
        body.write(20); // keep alive
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
        int h = in.readUnsignedByte();
        int len = in.readUnsignedByte();
        if (h != 0x20 || len < 2) throw new Exception("MQTT CONNACK gelmedi");
        int ackFlags = in.readUnsignedByte();
        int rc = in.readUnsignedByte();
        if (rc != 0) throw new Exception("MQTT baglanti reddedildi, kod=" + rc);
        return s;
    }

    public static void test(String host, int port, String user, String pass) throws Exception {
        Socket s = connect(host, port, user, pass);
        try {
            s.getOutputStream().write(new byte[]{(byte)0xE0, 0x00});
            s.getOutputStream().flush();
        } finally {
            s.close();
        }
    }

    public static void publish(String host, int port, String user, String pass,
                               String topic, String payload) throws Exception {
        Socket s = connect(host, port, user, pass);
        try {
            byte[] tb = utf(topic);
            byte[] pb = payload.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(tb);
            body.write(pb);

            ByteArrayOutputStream pkt = new ByteArrayOutputStream();
            pkt.write(0x30); // PUBLISH QoS0, retain false
            remaining(pkt, body.size());
            pkt.write(body.toByteArray());

            OutputStream out = s.getOutputStream();
            out.write(pkt.toByteArray());
            out.flush();
            Thread.sleep(150);
            out.write(new byte[]{(byte)0xE0, 0x00});
            out.flush();
        } finally {
            s.close();
        }
    }

    private MqttPublisher() {}
}
