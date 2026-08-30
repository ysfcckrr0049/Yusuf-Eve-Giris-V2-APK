package com.yusuf.evegiris;

import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class HttpsGatewayPublisher {
    private static final String BASE = "https://95.70.192.182:449/eve-giris/";

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v & 0xff));
        return sb.toString();
    }

    private static String spki(X509Certificate cert) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(cert.getPublicKey().getEncoded());
        return "sha256/" + Base64.getEncoder().encodeToString(digest);
    }

    private static javax.net.ssl.SSLSocketFactory pinnedFactory() throws Exception {
        final String expectedPin = GatewayConfig.SPKI_PIN;

        X509TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("Client certificate unsupported");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Server certificate missing");
                }
                try {
                    String actual = spki(chain[0]);
                    boolean ok = MessageDigest.isEqual(
                            actual.getBytes(StandardCharsets.US_ASCII),
                            expectedPin.getBytes(StandardCharsets.US_ASCII)
                    );
                    if (!ok) throw new CertificateException("TLS SPKI pin mismatch");
                } catch (CertificateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CertificateException("TLS pin check failed", e);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
        return ctx.getSocketFactory();
    }

    public static int publish(String method, String eventId, long eventTs) throws Exception {
        String body = "{\"event\":\"YUSUF_EVE_GELDI\",\"method\":\""
                + jsonEscape(method)
                + "\",\"ts\":" + eventTs
                + ",\"source\":\"YusufEveGirisAPK\",\"event_id\":\""
                + jsonEscape(eventId) + "\"}";

        byte[] raw = body.getBytes(StandardCharsets.UTF_8);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                GatewayConfig.HMAC_SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        ));
        String sig = hex(mac.doFinal(raw));

        URL url = new URL(BASE + GatewayConfig.PATH_TOKEN);
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        c.setSSLSocketFactory(pinnedFactory());
        // Default hostname verifier korunur; IP SAN=95.70.192.182 kontrol edilir.
        c.setConnectTimeout(7000);
        c.setReadTimeout(10000);
        c.setUseCaches(false);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Yusuf-Signature", sig);
        c.setFixedLengthStreamingMode(raw.length);

        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(raw);
                out.flush();
            }
            int code = c.getResponseCode();

            // 204 normal kabul.
            // 409 ayni event_id daha once islendi: kayip cevap sonrasi retry'da
            // MQTT'ye dusup cift tetiklememek icin basari kabul edilir.
            if (code != 204 && code != 409) {
                throw new Exception("HTTPS gateway HTTP " + code);
            }
            return code;
        } finally {
            c.disconnect();
        }
    }

    private HttpsGatewayPublisher() {}
}
