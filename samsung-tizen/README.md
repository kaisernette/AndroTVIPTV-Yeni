# KaiserTv+ — Samsung Tizen TV ön sürümü

Bu klasör, Samsung U40JU6400 gibi 2015 Tizen 2.3 televizyonlar için ayrı web uygulaması başlangıç projesidir. Android APK'sından bağımsızdır.

## Şu anki kapsam
- Kumanda ile gezilebilen TV arayüzü
- Ana Sayfa, Canlı TV, Filmler, Diziler, Favoriler, Ara ve Kaynaklar bölümleri
- M3U/M3U8 kaynak adresini cihazın yerel depolamasına kaydetme ve silme
- Koyu arka plan, mavi seçim vurgusu ve 16:9 TV yerleşimi

## Henüz tamamlanmayanlar
Bu ön sürüm henüz M3U listesini indirme/ayrıştırma, Xtream Codes, gerçek afişler, favori içerikler veya video oynatmayı uygulamaz. Bunlar sonraki geliştirme adımlarıdır. Televizyonda gerçek oynatma testi yapılmadan çalışıyor denemez.

## Paketleme notu
Samsung TV uygulamaları config.xml, HTML, CSS ve JavaScript dosyalarıyla hazırlanır. TV'ye kurulabilir .wgt paketi için Samsung/Tizen geliştirme araçları ve geçerli sertifika gerekir. GitHub Actions kaynak dosyalarını paketleyebilir; ancak sertifika olmadan imzalı .wgt ürettiğini varsaymamalıyız.
