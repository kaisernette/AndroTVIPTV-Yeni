# KaiserTv+ — Samsung Tizen TV uygulaması

Bu klasör, Android APK'sından ayrı olarak Samsung Tizen 2.3 tabanlı televizyonlar için hazırlanan web uygulamasıdır. Referans arayüz ve özellikler Android KaiserTv+ 340 sürümünden uyarlanır.

## Şu anki geliştirme kapsamı
- Koyu zemin, mavi seçim/odak rengi, yuvarlatılmış dikdörtgen düğmeler
- Sol menü: Ana Sayfa, Canlı TV, Filmler, Diziler, Favoriler, Ara, Kaynaklar
- M3U/M3U8 bağlantısı kaydetme ve listeyi indirme/ayrıştırma
- Xtream Codes sunucu, kullanıcı adı ve şifre ile API bağlantısı
- Xtream canlı kanal, film ve dizi listeleri ile kategori eşlemesi
- Dizi için sezon/bölüm listesini Xtream API üzerinden alma
- Favorileri TV'nin yerel depolamasında tutma
- Video başlatma için Samsung AVPlay API köprüsü
- Kumanda yön/geri tuşları için ilk gezinme desteği

## Bilinen sınırlar ve test gereksinimleri
- Xtream/M3U ağ erişimi, sağlayıcının CORS ve ağ izinlerine bağlıdır; U40JU6400 üzerinde doğrulanmalıdır.
- Xtream dizi bölüm listesi alınır; bölüm oynatma ve ayrıntı ekranı test/iyileştirme aşamasındadır.
- AVPlay çağrısı entegre edilmiştir ancak gerçek yayın, altyazı, codec, kanal değiştirme ve oynatıcı kontrolleri TV üzerinde test edilmemiştir.
- Android uygulamasındaki tüm özellikler henüz Tizen sürümüne taşınmış değildir.
- Kaynak bilgileri ve Xtream giriş bilgileri cihazın yerel depolamasında saklanır. Yalnızca güvendiğin cihazda ve kendi erişim bilgilerinle kullan.

## Paketleme
GitHub Actions şu an kaynak arşivini ve JavaScript sözdizimini doğrulamak üzere hazırlanmıştır. Bu arşiv imzalı Tizen .wgt paketi değildir. TV'ye kurulum için Tizen Studio/CLI ve cihaza uygun geçerli sertifika gerekir.
