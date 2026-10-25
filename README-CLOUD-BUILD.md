# AndroTV IPTV — telefondan bulut üzerinden APK oluşturma

Bu paket, GitHub Actions ile bulutta debug APK derlemek için bir iş akışı içerir. APK henüz bu ZIP'in içinde değildir; GitHub Actions derleme tamamlanınca üretir.

## Telefon tarayıcısından adımlar

1. GitHub hesabına giriş yap ve **New repository** ile yeni bir repository oluştur. Örneğin `AndroTVIPTV`; test için **Public** veya **Private** seçebilirsin.
2. Bu ZIP'i telefonda çıkar.
3. Repository sayfasında **Add file → Upload files** seçeneğini aç. ZIP'ten çıkan proje dosyalarını ve `.github/workflows/android-apk.yml` dosyasını, klasör yapısı korunacak şekilde yükle. Gizli `.github` klasörü görünmüyorsa dosya yöneticisinde gizli dosyaları göster.
4. **Commit changes** ile dosyaları kaydet.
5. Repository içinde **Actions** sekmesine gir. `Build AndroTV IPTV APK` iş akışını açıp **Run workflow** düğmesine bas. İlk `push` işlemi de otomatik derleme başlatır.
6. İşlem tamamlandığında yeşil tikli çalıştırmaya dokun. Sayfanın altındaki **Artifacts** bölümünde `AndroTVIPTV-debug-apk` dosyasını indir.
7. İndirilen artifact genellikle ayrıca ZIP olarak gelir. Onu çıkar ve `app-debug.apk` dosyasını Android TV'ye aktar.

## Önemli

- Workflow Gradle 8.9 ve Java 17 kullanır; Android SDK 35'i kurar.
- Bu, test amaçlı debug APK'dır; Play Store yayını için uygun bir imzalı release paketi değildir.
- Derleme başarısız olursa Actions çalıştırmasına girip ilk kırmızı hata bölümünü incele. Projede veya bağımlılıklarda uyumsuzluk çıkabilir.
- IPTV bağlantı bilgilerini GitHub'a, özellikle herkese açık bir depoya, kod içine yazma. Yalnızca kullanma yetkin olan yayın listelerini kullan.
