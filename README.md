# Yusuf Eve Giriş V6 — Zaman Aşımı / Güvenlik Ayarları

Varsayılanlar:
- Ev Wi‑Fi minimum uzak kalma: 10 dk
- Eve şarjdayken geliş sonrası şarjdan çıkarma penceresi: 15 dk
- Bluetooth / Android Auto / Wi‑Fi genel geliş adayı penceresi: 5 dk
- Servis başlangıç koruması: 5 dk
- Başarılı MQTT tetikleri arası ortak kilit: 60 dk

V6 sert zaman aşımı mantığı:
- Şarj penceresi dolarsa `charge_arrival_pending` gerçekten temizlenir.
  Daha sonra şarjdan çıkarmak eski geliş kaydını tetiklemez.
- Bluetooth / Android Auto / Wi‑Fi adayı genel süre içinde ev/konum teyidi alamazsa
  bekleyen aday gerçekten temizlenir.
- Konum kalitesi için yapılan tekrar denemeleri genel aday süresini sıfırlamaz;
  süre ilk gerçek olay anından itibaren işler.
- 1 saniyelik servis içi timeout denetleyici, telefon hareketsiz olsa bile
  süreleri zamanında iptal eder.
- Her iptal olay günlüğüne yazılır.
- Tüm süreler ayrı `Zaman Aşımı / Güvenlik Ayarları` ekranından değiştirilebilir.

V5 özellikleri korunur:
Fused high-accuracy taze konum, harita/yarıçap, Ysf Golf6 Bluetooth,
Wi‑Fi, Android Auto, şarj, Tailscale L3 MQTT, detaylı algoritma görseli,
geri sayımlar, sahte testler ve olay günlüğü.
