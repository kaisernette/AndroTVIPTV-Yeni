# AndroTV IPTV 2.1 — Android TV

Kumanda odaklı, koyu/Netflix esintili yerel Android uygulaması. Yalnızca kullanma hakkınız olan içerik kaynaklarını ekleyin; uygulama kanal veya film sağlamaz.

## Özellikler
- M3U/M3U8 URL'sinden liste yükleme
- Xtream Codes API ile canlı TV, film ve dizi kataloğu
- Dizi seçildiğinde sezon ve bölüm listesini Xtream `get_series_info` API'sinden yükleme ve bölümü oynatma
- Canlı TV, Filmler, Diziler, Favoriler, Son İzlenenler ve Tümü sekmeleri
- M3U/Xtream kategorileri için filtre düğmeleri
- Sağlayıcı veriyorsa film/dizi afişlerini gösterme
- Favoriler ve son izlenen 20 içerik cihazda saklanır
- AndroidX Media3 ExoPlayer ile HTTP(S) akış oynatma
- Android TV kumandası için odaklanabilir kontroller ve yatay içerik satırları
- EPG dahil değildir

## Android Studio ile APK oluşturma
1. Android Studio'nun güncel kararlı sürümünü ve Android SDK Platform 35'i kurun.
2. ZIP'i çıkarın; Android Studio'da `AndroTVIPTV` klasörünü **Open** ile açın.
3. Gradle Sync'in bitmesini bekleyin. JDK 17 kullanın (Android Studio'nun gömülü JDK'sı uygundur).
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)** seçin.
5. APK'yı `app/build/outputs/apk/debug/app-debug.apk` konumundan Android TV'ye aktarın ve kurun. TV güvenlik ayarlarında bu dosya yöneticisi için bilinmeyen uygulama kurulumuna izin vermeniz gerekebilir.

## Bağlantı
- **M3U/M3U8:** `https://...` biçimindeki oynatma listesi URL'sini girin.
- **Xtream Codes:** Sunucu kök adresi, kullanıcı adı ve şifreyi girin. Uygulama `player_api.php` ile canlı, film ve dizi kataloglarını ister.
- Dizi seçince sezonlar/bölümler API'den alınır; bir bölüm seçilince `/series/` akış adresi Media3 ile açılır.

## Bilinen sınırlar
- EPG bilerek dahil edilmedi.
- Afişler sağlayıcının URL'si varsa gösterilir; bazı sağlayıcılar farklı alan adları kullanabilir.
- M3U kategorileri sağlayıcıya göre değişir; sınıflandırma grup ve ad metnine göre tahminidir.
- Xtream API sağlayıcıları arasında alan adı/yanıt biçimi farklılıkları olabilir. Bu durumda bazı diziler veya bölümler görünmeyebilir.
- Bazı akışlar kodek, DRM, sağlayıcı kısıtlaması veya süresi dolmuş bağlantılar nedeniyle oynatılamayabilir.
- Bu kaynak kodu Android SDK/Gradle bulunan bir bilgisayarda derlenmelidir; bu pakette hazır APK bulunmaz.
