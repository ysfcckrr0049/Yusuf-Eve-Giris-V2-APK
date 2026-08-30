package com.yusuf.evegiris;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class TimingSettingsActivity extends Activity {
    private EditText wifiAway, chargeTimeout, candidateTimeout, startupGrace, cooldown;

    private int dp(int n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView title(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(22f);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(8), 0, dp(12));
        return v;
    }

    private TextView label(String s, String help) {
        TextView v = new TextView(this);
        v.setText(s + "\n" + help);
        v.setTextSize(15f);
        v.setPadding(0, dp(16), 0, dp(4));
        return v;
    }

    private EditText minutes(int value) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setText(String.valueOf(value));
        return e;
    }

    private Button button(String text, android.view.View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        return b;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(root);

        root.addView(title("Zaman Aşımı / Güvenlik Ayarları"));

        TextView info = new TextView(this);
        info.setText(
                "Süre dolunca bekleyen geliş kaydı gerçekten iptal edilir. " +
                "Sonradan gelen Bluetooth / Android Auto / Wi‑Fi / şarj olayı eski kaydı kullanarak MQTT gönderemez.\n\n" +
                "Değerler dakika cinsindedir.");
        root.addView(info);

        root.addView(label(
                "Ev Wi‑Fi minimum uzak kalma süresi",
                "Ev Wi‑Fi'dan ayrıldıktan sonra yeniden bağlanmanın geliş sinyali sayılabilmesi için gereken minimum süre. Varsayılan 10 dk."));
        wifiAway = minutes(Prefs.wifiAwayMinutes(this));
        root.addView(wifiAway);

        root.addView(label(
                "Şarjdan çıkarma zaman aşımı",
                "Telefon eve şarjdayken geldiyse bu süre içinde şarjdan çıkarılmalıdır. Süre dolarsa şarj yöntemi tamamen iptal edilir. Varsayılan 15 dk."));
        chargeTimeout = minutes(Prefs.chargeTimeoutMinutes(this));
        root.addView(chargeTimeout);

        root.addView(label(
                "Genel geliş adayı zaman aşımı",
                "Golf6 Bluetooth ayrılması, Android Auto kapanması veya Wi‑Fi geri bağlanması sonrası konum teyidi için maksimum bekleme süresi. Varsayılan 5 dk."));
        candidateTimeout = minutes(Prefs.candidateTimeoutMinutes(this));
        root.addView(candidateTimeout);

        root.addView(label(
                "Servis başlangıç koruması",
                "Uygulama/servis yeni başladığında yanlış tetikleri engeller. 0 yapılırsa kapatılır. Varsayılan 5 dk. Değişiklik bir sonraki servis başlangıcında uygulanır."));
        startupGrace = minutes(Prefs.startupGraceMinutes(this));
        root.addView(startupGrace);

        root.addView(label(
                "Başarılı tetikler arası ortak kilit",
                "Hangi yöntem tetiklerse tetiklesin iki başarılı MQTT arasında minimum süre. 0 yapılırsa kapatılır. Varsayılan 60 dk."));
        cooldown = minutes(Prefs.cooldownMinutes(this));
        root.addView(cooldown);

        root.addView(button("AYARLARI KAYDET", v -> save()));
        root.addView(button("VARSAYILANLARA DÖN (10 / 15 / 5 / 5 / 60)", v -> {
            Prefs.resetTimingSettings(this);
            load();
            Prefs.addEvent(this, "Zaman ayarları", "Varsayılan süreler geri yüklendi.");
            Toast.makeText(this, "Varsayılan süreler geri yüklendi", Toast.LENGTH_LONG).show();
        }));
        root.addView(button("GERİ DÖN", v -> finish()));

        setContentView(scroll);
    }

    private int value(EditText e, int def, int min, int max) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception ex) {
            return def;
        }
    }

    private void load() {
        wifiAway.setText(String.valueOf(Prefs.wifiAwayMinutes(this)));
        chargeTimeout.setText(String.valueOf(Prefs.chargeTimeoutMinutes(this)));
        candidateTimeout.setText(String.valueOf(Prefs.candidateTimeoutMinutes(this)));
        startupGrace.setText(String.valueOf(Prefs.startupGraceMinutes(this)));
        cooldown.setText(String.valueOf(Prefs.cooldownMinutes(this)));
    }

    private void save() {
        int w = value(wifiAway, Prefs.DEF_WIFI_AWAY_MIN, 1, 120);
        int ch = value(chargeTimeout, Prefs.DEF_CHARGE_TIMEOUT_MIN, 1, 120);
        int ca = value(candidateTimeout, Prefs.DEF_CANDIDATE_TIMEOUT_MIN, 1, 60);
        int st = value(startupGrace, Prefs.DEF_STARTUP_GRACE_MIN, 0, 30);
        int co = value(cooldown, Prefs.DEF_COOLDOWN_MIN, 0, 240);

        Prefs.saveTimingSettings(this, w, ch, ca, st, co);
        Prefs.addEvent(this, "Zaman ayarları kaydedildi",
                "WiFi=" + w + "dk, şarj=" + ch + "dk, genel aday=" + ca
                        + "dk, başlangıç=" + st + "dk, ortak kilit=" + co + "dk");
        Toast.makeText(this, "Zaman aşımı ayarları kaydedildi", Toast.LENGTH_LONG).show();
        finish();
    }
}
