# Yusuf Eve Giriş V7.4 — Olay Tetikli Konum / Pil Tasarrufu

**Sürekli yüksek hassasiyetli konum aboneliği kaldırıldı.** Servis sadece ev Wi-Fi yeniden
bağlanınca (uzakta kalma süresi koşuluyla), Ysf Golf6 Bluetooth kopunca veya Android Auto
Car Mode çıkışı yayınlanınca (Golf6 hâlâ bağlı değilse) **taze** Fused konum ister.

- Her olay için maksimum **3 kez 10 saniyelik** hassas konum isteği, en az **15 saniye arayla**.
- Konum 15 saniyeden eskiyse veya 150 m'den düşük hassasiyetliyse güvenli tetik yapılmaz.
- Konum ev alanındaysa mevcut HTTPS 449 / MQTT yedek ve event_id kuralları çalışır.
- Başlangıç koruması, Wi-Fi minimum uzak kalma, genel aday zaman aşımı ve 1 saat ortak kilit korunur.
- Şarja takma/çıkarma artık konum/MQTT tetikleyicisi değildir.
- 'Konumu şimdi tazele' manuel düğmesi ayrı olarak çalışmaya devam eder.
- Android Auto için sistemin `EXIT_CAR_MODE` olayını yayınlaması gerekir; bazı cihazlarda bu
  olay doğrudan Android Auto bağlantı kesilmesini temsil etmeyebilir.
- Arka plan izinleri verilmediyse veya Android konumu kapattıysa doğrulama başarısız olabilir.

**Not:** Bu güncelleme kaynak kodudur; imzalı APK'nın GitHub Actions ile derlenmesi gerekir.
