package com.example.androtviptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Bitmap
import android.util.LruCache
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.concurrent.thread
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.lang.ref.WeakReference

private data class SavedSource(val id: String, val name: String, val mode: String, val address: String, val username: String = "", val password: String = "")

private data class MediaItem(
    val id: String, val name: String, val url: String, val group: String,
    val type: String, val icon: String = "", val extension: String = "", val seriesId: String = "", val sourceId: String = "", val plot: String = "", val actors: String = "", val rating: String = "", val releaseDate: String = "", val genre: String = ""
) {
    val favoriteKey: String get() = "$type:$id:$url"
}

private class WrappingRowLayout(context: Context) : ViewGroup(context) {
    private val lineTops = mutableListOf<Int>()
    private val childLefts = mutableListOf<Int>()
    private val childTops = mutableListOf<Int>()
    private val lineHeights = mutableListOf<Int>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        lineTops.clear(); childLefts.clear(); childTops.clear(); lineHeights.clear()
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var maxRight = paddingLeft
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) {
                childLefts.add(x); childTops.add(y)
                continue
            }
            val lp = child.layoutParams as MarginLayoutParams
            val childWidthSpec = getChildMeasureSpec(widthMeasureSpec,
                paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin, lp.width)
            val childHeightSpec = getChildMeasureSpec(heightMeasureSpec,
                paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin, lp.height)
            child.measure(childWidthSpec, childHeightSpec)
            val outerWidth = lp.leftMargin + child.measuredWidth + lp.rightMargin
            val outerHeight = lp.topMargin + child.measuredHeight + lp.bottomMargin
            if (x > paddingLeft && x + outerWidth > paddingLeft + available) {
                y += lineHeight
                x = paddingLeft
                lineHeight = 0
            }
            childLefts.add(x + lp.leftMargin)
            childTops.add(y + lp.topMargin)
            x += outerWidth
            lineHeight = maxOf(lineHeight, outerHeight)
            maxRight = maxOf(maxRight, x)
        }
        val desiredHeight = y + lineHeight + paddingBottom
        setMeasuredDimension(
            resolveSize(maxRight + paddingRight, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val x = childLefts.getOrElse(i) { paddingLeft }
            val y = childTops.getOrElse(i) { paddingTop }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: android.util.AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams =
        MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}

class MainActivity : Activity() {
    companion object {
        @Volatile var openTvTabOnResume: Boolean = false
    }
    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("androtv", Context.MODE_PRIVATE)
        val selected = prefs.getString("app_language", "system") ?: "system"
        val locale = if (selected == "system") java.util.Locale.getDefault() else java.util.Locale.forLanguageTag(selected)
        java.util.Locale.setDefault(locale)
        val configuration = android.content.res.Configuration(newBase.resources.configuration)
        configuration.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    private fun t(value: String): String {
        val selected = prefs.getString("app_language", "system") ?: "system"
        val language = if (selected == "system") java.util.Locale.getDefault().language else selected
        val translations = when (language) {
            "en" -> mapOf("Ayarlar" to "Settings", "Bir bölümü seç; ayrı ekranda açılacak." to "Choose a section to open it.", "Kaynak Yönetimi" to "Source Management", "M3U / Xtream kaynak ekle, düzenle" to "Add or edit M3U / Xtream sources", "İçerik Ayarları" to "Content Settings", "Kategoriler, gizleme ve sıralama" to "Categories, visibility and sorting", "Tema ve Görünüm" to "Theme & Appearance", "Renk, arka plan ve arayüz stili" to "Colors, background and interface style", "İzleme Geçmişi" to "Watch History", "Son izlenen içerikleri görüntüle" to "View recently watched content", "Hakkında" to "About", "Uygulama sürümü ve teknik bilgiler" to "App version and technical information", "Dil / Language" to "Language", "Uygulama dilini seç" to "Choose the app language", "Dil" to "Language", "Cihaz dili" to "Device language", "Uygulama dili" to "App language")
            "de" -> mapOf("Ayarlar" to "Einstellungen", "Bir bölümü seç; ayrı ekranda açılacak." to "Wähle einen Bereich aus.", "Kaynak Yönetimi" to "Quellenverwaltung", "M3U / Xtream kaynak ekle, düzenle" to "M3U-/Xtream-Quellen hinzufügen oder bearbeiten", "İçerik Ayarları" to "Inhaltseinstellungen", "Kategoriler, gizleme ve sıralama" to "Kategorien, Ausblenden und Sortierung", "Tema ve Görünüm" to "Design & Darstellung", "Renk, arka plan ve arayüz stili" to "Farben, Hintergrund und Oberflächenstil", "İzleme Geçmişi" to "Wiedergabeverlauf", "Son izlenen içerikleri görüntüle" to "Zuletzt angesehene Inhalte", "Hakkında" to "Über", "Uygulama sürümü ve teknik bilgiler" to "App-Version und technische Informationen", "Dil / Language" to "Sprache", "Uygulama dilini seç" to "App-Sprache auswählen", "Dil" to "Sprache", "Cihaz dili" to "Gerätesprache", "Uygulama dili" to "App-Sprache")
            "fr" -> mapOf("Ayarlar" to "Paramètres", "Bir bölümü seç; ayrı ekranda açılacak." to "Choisissez une rubrique.", "Kaynak Yönetimi" to "Gestion des sources", "M3U / Xtream kaynak ekle, düzenle" to "Ajouter ou modifier des sources M3U / Xtream", "İçerik Ayarları" to "Paramètres du contenu", "Kategoriler, gizleme ve sıralama" to "Catégories, masquage et tri", "Tema ve Görünüm" to "Thème et apparence", "Renk, arka plan ve arayüz stili" to "Couleurs, arrière-plan et style", "İzleme Geçmişi" to "Historique", "Son izlenen içerikleri görüntüle" to "Afficher les contenus récemment regardés", "Hakkında" to "À propos", "Uygulama sürümü ve teknik bilgiler" to "Version et informations techniques", "Dil / Language" to "Langue", "Uygulama dilini seç" to "Choisir la langue de l’application", "Dil" to "Langue", "Cihaz dili" to "Langue de l’appareil", "Uygulama dili" to "Langue de l’application")
            "it" -> mapOf("Ayarlar" to "Impostazioni", "Bir bölümü seç; ayrı ekranda açılacak." to "Scegli una sezione.", "Kaynak Yönetimi" to "Gestione sorgenti", "M3U / Xtream kaynak ekle, düzenle" to "Aggiungi o modifica sorgenti M3U / Xtream", "İçerik Ayarları" to "Impostazioni contenuti", "Kategoriler, gizleme ve sıralama" to "Categorie, visibilità e ordinamento", "Tema ve Görünüm" to "Tema e aspetto", "Renk, arka plan ve arayüz stili" to "Colori, sfondo e stile dell’interfaccia", "İzleme Geçmişi" to "Cronologia", "Son izlenen içerikleri görüntüle" to "Visualizza i contenuti guardati di recente", "Hakkında" to "Informazioni", "Uygulama sürümü ve teknik bilgiler" to "Versione e informazioni tecniche", "Dil / Language" to "Lingua", "Uygulama dilini seç" to "Scegli la lingua dell’app", "Dil" to "Lingua", "Cihaz dili" to "Lingua del dispositivo", "Uygulama dili" to "Lingua dell’app")
            "es" -> mapOf("Ayarlar" to "Ajustes", "Bir bölümü seç; ayrı ekranda açılacak." to "Elige una sección.", "Kaynak Yönetimi" to "Gestión de fuentes", "M3U / Xtream kaynak ekle, düzenle" to "Añadir o editar fuentes M3U / Xtream", "İçerik Ayarları" to "Ajustes de contenido", "Kategoriler, gizleme ve sıralama" to "Categorías, visibilidad y orden", "Tema ve Görünüm" to "Tema y apariencia", "Renk, arka plan ve arayüz stili" to "Colores, fondo y estilo de interfaz", "İzleme Geçmişi" to "Historial", "Son izlenen içerikleri görüntüle" to "Ver contenido visto recientemente", "Hakkında" to "Acerca de", "Uygulama sürümü ve teknik bilgiler" to "Versión e información técnica", "Dil / Language" to "Idioma", "Uygulama dilini seç" to "Elegir el idioma de la aplicación", "Dil" to "Idioma", "Cihaz dili" to "Idioma del dispositivo", "Uygulama dili" to "Idioma de la aplicación")
            "ru" -> mapOf("Ayarlar" to "Настройки", "Bir bölümü seç; ayrı ekranda açılacak." to "Выберите раздел.", "Kaynak Yönetimi" to "Управление источниками", "M3U / Xtream kaynak ekle, düzenle" to "Добавить или изменить источники M3U / Xtream", "İçerik Ayarları" to "Настройки контента", "Kategoriler, gizleme ve sıralama" to "Категории, скрытие и сортировка", "Tema ve Görünüm" to "Тема и оформление", "Renk, arka plan ve arayüz stili" to "Цвета, фон и стиль интерфейса", "İzleme Geçmişi" to "История просмотров", "Son izlenen içerikleri görüntüle" to "Недавно просмотренный контент", "Hakkında" to "О приложении", "Uygulama sürümü ve teknik bilgiler" to "Версия и техническая информация", "Dil / Language" to "Язык", "Uygulama dilini seç" to "Выберите язык приложения", "Dil" to "Язык", "Cihaz dili" to "Язык устройства", "Uygulama dili" to "Язык приложения")
            "ar" -> mapOf("Ayarlar" to "الإعدادات", "Bir bölümü seç; ayrı ekranda açılacak." to "اختر قسماً لفتحه.", "Kaynak Yönetimi" to "إدارة المصادر", "M3U / Xtream kaynak ekle, düzenle" to "إضافة أو تعديل مصادر M3U / Xtream", "İçerik Ayarları" to "إعدادات المحتوى", "Kategoriler, gizleme ve sıralama" to "الفئات والإخفاء والترتيب", "Tema ve Görünüm" to "المظهر والتصميم", "Renk, arka plan ve arayüz stili" to "الألوان والخلفية ونمط الواجهة", "İzleme Geçmişi" to "سجل المشاهدة", "Son izlenen içerikleri görüntüle" to "عرض المحتوى الذي تمت مشاهدته مؤخراً", "Hakkında" to "حول التطبيق", "Uygulama sürümü ve teknik bilgiler" to "إصدار التطبيق والمعلومات التقنية", "Dil / Language" to "اللغة", "Uygulama dilini seç" to "اختر لغة التطبيق", "Dil" to "اللغة", "Cihaz dili" to "لغة الجهاز", "Uygulama dili" to "لغة التطبيق")
            else -> emptyMap()
        }
        val common = mapOf(
            "en" to mapOf("Kaynak ekle ve kayıtlı bağlantılarını buradan yönet." to "Add and manage your saved connections here.",
                "Henüz içerik yok" to "No content yet",
                "Cihaz dili" to "Device language",
                "Menü" to "Menu",
                "Buton" to "Button",
                "Arkaplan" to "Background",
                "Şeffaf Menü" to "Transparent Menu",
                "Köşe biçimi" to "Corner style",
                "Zemin görünümü" to "Background style",
                "Açık" to "On",
                "Kapalı" to "Off",
                "Köşeli" to "Rounded",
                "Oval" to "Oval",
                "Düz" to "Solid",
                "Gradyan" to "Gradient",
                "Seçili tema" to "Selected theme",
                "Zemin ve vurgu rengi" to "Background and accent color",
                "Orman • Zümrüt" to "Forest • Emerald",
                "Gece • Yakut" to "Night • Ruby",
                "Gece • Lavanta" to "Night • Lavender",
                "Antrasit • Mavi" to "Anthracite • Blue",
                "Gece • Altın" to "Night • Gold",
                "Gece • Buzul" to "Night • Glacier",
                "Daha önce izlediğin içerikler" to "Previously watched content",
                "Önce içerik seç." to "Select content first.",
                "Geçmiş zaten boş." to "History is already empty.",
                "Tüm izleme geçmişi silinsin mi?" to "Delete all watch history?",
                "Seçileni Sil" to "Delete Selected",
                "Geçmişi Sil" to "Clear History",
                "Kaynak adı (ör. Ev TV)" to "Source name (e.g. Home TV)",
                "Kaynak adı (ör. Ev Xtream)" to "Source name (e.g. Home Xtream)",
                "Kaynak adı (ör. Ev Portal)" to "Source name (e.g. Home Portal)",
                "Portal URL (https://...)" to "Portal URL (https://...)",
                "MAC adresi (00:1A:79:XX:XX:XX)" to "MAC address (00:1A:79:XX:XX:XX)",
                "KAYNAKLAR" to "SOURCES",
                "Sunucu, kullanıcı adı ve şifreyi doldur." to "Enter the server, username and password.",
                "Geçerli bir M3U/M3U8 URL'si gir." to "Enter a valid M3U/M3U8 URL.",
                "Portal URL ve geçerli MAC adresini girin (00:1A:79:XX:XX:XX)." to "Enter the portal URL and a valid MAC address (00:1A:79:XX:XX:XX).",
                "MAC Portal bilgileri kaydedildi. Yayın bağlantısı henüz etkin değil." to "MAC Portal details saved. Streaming is not enabled yet.",
                "MAC Portal yayın bağlantısı henüz etkin değil." to "MAC Portal streaming is not enabled yet.",
                "Kaynak adı girin" to "Enter source name",
                "İzleme kaynağını ekleyerek başla" to "Start by adding a streaming source",
                "Köşe biçimi" to "Corner style",
                "Zemin görünümü" to "Background style",
                "Şeffaf Menü" to "Transparent Menu",
                "Düz" to "Solid",
                "Gradyan" to "Gradient",
                "Köşeli" to "Rounded",
                "Oval" to "Oval", "Türü seçerek izleme geçmişini görüntüle." to "Choose a type to view your watch history.", "Kaynak ve içerikleri silinsin mi?" to "Delete this source and its content?", "Kaynağı sil" to "Delete source", "Yenile" to "Refresh", "Düzenle" to "Edit", "Sil" to "Delete", "İptal" to "Cancel", "Hepsini sil" to "Delete all", "Geçmişi sil" to "Clear history", "Seçileni sil (" to "Delete selected (", "İzleme geçmişini temizle" to "Clear watch history", "İzlenen tüm TV kanalları geçmişten silinsin mi?" to "Delete all watched TV channels from history?", "İzlenen tüm filmler geçmişten silinsin mi?" to "Delete all watched movies from history?", "İzlenen tüm diziler geçmişten silinsin mi?" to "Delete all watched series from history?", "Sezon" to "Season", "KONU" to "OVERVIEW", "OYUNCULAR" to "CAST", "FİLM" to "MOVIE", "DİZİ" to "SERIES", "Film, dizi veya kanal ara..." to "Search movies, series or channels...", "Arama geçmişi" to "Search history", "Kaynaklar" to "Sources", "Geçmiş" to "History", "Ara" to "Search", "Kategoriler" to "Categories", "İzlenen TV kanalları" to "Watched TV channels", "İzlenen filmler" to "Watched movies", "İzlenen diziler" to "Watched series", "Bu türde henüz izleme geçmişi yok." to "No watch history for this type yet.", "Bağlan" to "Connect", "Kaydet" to "Save", "Kaynak adı (ör. Ev Xtream)" to "Source name (e.g. Home Xtream)", "M3U / M3U8 oynatma listesi URL'si" to "M3U / M3U8 playlist URL", "Sunucu adresi (https://...)" to "Server address (https://...)", "Kullanıcı adı" to "Username", "Şifre" to "Password", "İzleme kaynağı ekle" to "Add streaming source", "Kaynağı düzenle" to "Edit source", "Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle." to "Only add streaming addresses you are authorized to use.", "İzleme kaynağı ekle" to "Add streaming source", "Kaynağı düzenle" to "Edit source", "Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle." to "Only add streaming addresses you are authorized to use.", "Kaynak adı (ör. Ev Xtream)" to "Source name (e.g. Home Xtream)", "M3U / M3U8 oynatma listesi URL'si" to "M3U / M3U8 playlist URL", "Sunucu adresi (https://...)" to "Server address (https://...)", "Kullanıcı adı" to "Username", "Şifre" to "Password", "Bağlan" to "Connect", "Kaydet" to "Save", "Sezon" to "Season", "Sunucu, kullanıcı adı ve şifreyi doldur." to "Enter the server, username and password.", "Geçerli bir M3U/M3U8 URL'si gir." to "Enter a valid M3U/M3U8 URL.", 
                "Tüm kaynaklarda ara" to "Search all sources", "Arama sonucu bulunamadı" to "No search results found",
                "Kütüphanen seni bekliyor" to "Your library is waiting",
                "Film, dizi veya kanal adını yaz. Bütün kayıtlı kaynaklar taranır." to "Enter a movie, series or channel name. All saved sources will be searched.",
                "Başka bir kelime deneyebilir veya yazımı kontrol edebilirsin." to "Try another word or check the spelling.",
                "Başlamak için kendi M3U/M3U8 veya Xtream kaynağını ekle." to "Add your own M3U/M3U8 or Xtream source to get started.",
                "KAYNAK EKLE  →" to "ADD SOURCE  →", "KAYITLI_ICERIK_HAZIR" to " saved items ready.",
                "CANLI YAYIN" to "LIVE", "FİLMİ İZLE" to "WATCH MOVIE", "FRAGMAN" to "TRAILER",
                "BAŞTAN İZLE" to "WATCH FROM BEGINNING", "DETAYLAR" to "DETAILS", "▶ İzle" to "▶ Watch",
                "Bu film için konu bilgisi kaynakta bulunamadı." to "Plot information is not available for this movie.",
                "Oyuncu bilgisi kaynakta bulunamadı." to "Cast information is not available.",
                "İzlenen TV kanalları" to "Watched TV channels", "İzlenen filmler" to "Watched movies", "İzlenen diziler" to "Watched series",
                "Geçmiş" to "History", "Türü seçerek izleme geçmişini görüntüle." to "Choose a type to view your watch history.",
                "Kaynaklar" to "Sources", "KAISERTV ORIGINAL  •  SENİN KÜTÜPHANEN" to "KAISERTV ORIGINAL  •  YOUR LIBRARY",
                "Eğlenceye yeniden hoş geldin" to "Welcome back to entertainment", "Canlı yayınlarını, filmlerini ve dizilerini keşfet." to "Explore your live channels, movies and series.",
                "İÇERİKLERİ KEŞFET  →" to "EXPLORE CONTENT  →", "Kaisertv hazır. Kaynak ekleyerek içeriklerini yükleyebilirsin." to "KaiserTV is ready. Add a source to load your content.",
                "Ara" to "Search", "Ana Sayfa" to "Home", "TV" to "TV", "Filmler" to "Movies", "Diziler" to "Series", "Favoriler" to "Favorites", "İzlediklerim" to "Recently Watched", "Son İzlenenler" to "Recently Watched",
                "KATEGORİLER" to "CATEGORIES", "Tüm kategoriler" to "All categories", "Tüm Kanallar" to "All Channels",
                "Film, dizi veya kanal ara..." to "Search movies, series or channels...", "ARA" to "SEARCH", "Arama geçmişi" to "Search history", "Geçmişi sil" to "Clear history", "Seçileni sil (" to "Delete selected (",
                "Aradığın film, dizi veya kanal adını yaz." to "Enter a movie, series or channel name.", "Bu kategoride kanal bulunamadı." to "No channels found in this category.",
                "Daha fazla kanal göster  (" to "Show more channels  (", "Henüz favori eklemedin" to "No favorites yet", "İzleme geçmişin burada görünecek" to "Your watch history will appear here",
                "Bir içerikte yıldız düğmesine basarak favorilerine ekle." to "Press the star button on a title to add it to favorites.",
                "Bir içerik oynattığında burada kolayca devam edebilirsin." to "Your recently played content will appear here.",
                "Bu bölümde şu anda gösterilecek içerik yok." to "There is no content to show in this section.",
                "Önce kaynak ekle." to "Add a source first.", "Kanal bulunamadı." to "No channels found.",
                "Kanal listesi" to "Channel list", "Kaynak ekle" to "Add source", "Yenile" to "Refresh", "Düzenle" to "Edit", "Sil" to "Delete", "İptal" to "Cancel",
                "Henüz kayıtlı kaynak yok." to "No saved sources yet.", "Konu bilgisi alınamadı." to "Plot information unavailable.", "Oyuncu bilgisi alınamadı." to "Cast information unavailable.", "Puan bilgisi alınamadı." to "Rating information unavailable.",
                "Bilgiler yükleniyor…" to "Loading information…", "Bilgi yükleniyor…" to "Loading information…", "KONU" to "OVERVIEW", "OYUNCULAR" to "CAST", "FİLM" to "MOVIE", "DİZİ" to "SERIES",
                "İzleme geçmişin burada görünecek" to "Your watch history will appear here.", "Bu türde henüz izleme geçmişi yok." to "No watch history for this type yet.",
                "Hepsini sil" to "Delete all", "İzleme geçmişini temizle" to "Clear watch history", "İzlenen tüm TV kanalları geçmişten silinsin mi?" to "Delete all watched TV channels from history?",
                "İzlenen tüm filmler geçmişten silinsin mi?" to "Delete all watched movies from history?", "İzlenen tüm diziler geçmişten silinsin mi?" to "Delete all watched series from history?",
                "Sezon" to "Season", "Bölüm" to "Episode", "← Geri dön" to "← Go back", "Uygulamadan çıkmak istiyor musunuz?" to "Do you want to exit the app?",
                "Canlı yayın zamanı" to "It's live time", "Film gecesi başlasın" to "Movie night starts here", "Bir sonraki bölüm seni bekliyor" to "Your next episode is waiting",
                "Favorilerin burada" to "Your favorites", "Kaldığın yerden devam et" to "Continue where you left off", "Kütüphaneni keşfet" to "Explore your library"
            ),
            "de" to mapOf("İzleme kaynağı ekle" to "Streamingquelle hinzufügen", "Kaynağı düzenle" to "Quelle bearbeiten", "Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle." to "Füge nur Streaming-Adressen hinzu, zu deren Nutzung du berechtigt bist.", "Kaynak adı (ör. Ev Xtream)" to "Quellenname (z. B. Zuhause)", "M3U / M3U8 oynatma listesi URL'si" to "M3U-/M3U8-Playlist-URL", "Sunucu adresi (https://...)" to "Serveradresse (https://...)", "Kullanıcı adı" to "Benutzername", "Şifre" to "Passwort", "Bağlan" to "Verbinden", "Kaydet" to "Speichern", "Sezon" to "Staffel", "Sunucu, kullanıcı adı ve şifreyi doldur." to "Server, Benutzername und Passwort eingeben.", "Geçerli bir M3U/M3U8 URL'si gir." to "Eine gültige M3U/M3U8-URL eingeben.", 
                "Kaynaklar" to "Quellen", "Eğlenceye yeniden hoş geldin" to "Willkommen zurück", "Canlı yayınlarını, filmlerini ve dizilerini keşfet." to "Entdecke Live-TV, Filme und Serien.",
                "İÇERİKLERİ KEŞFET  →" to "INHALTE ENTDECKEN  →", "Ara" to "Suche", "Ana Sayfa" to "Startseite", "Filmler" to "Filme", "Diziler" to "Serien", "Favoriler" to "Favoriten", "İzlediklerim" to "Zuletzt gesehen",
                "KATEGORİLER" to "KATEGORIEN", "Tüm kategoriler" to "Alle Kategorien", "Tüm Kanallar" to "Alle Sender", "ARA" to "SUCHEN",
                "Arama geçmişi" to "Suchverlauf", "Geçmişi sil" to "Verlauf löschen", "Yenile" to "Aktualisieren", "Düzenle" to "Bearbeiten", "Sil" to "Löschen", "İptal" to "Abbrechen",
                "Kaynak ekle" to "Quelle hinzufügen", "Henüz kayıtlı kaynak yok." to "Noch keine Quellen gespeichert.", "KONU" to "ÜBERSICHT", "OYUNCULAR" to "BESETZUNG", "FİLM" to "FILM", "DİZİ" to "SERIE",
                "Hepsini sil" to "Alle löschen", "← Geri dön" to "← Zurück", "Uygulamadan çıkmak istiyor musunuz?" to "Möchtest du die App verlassen?"
            ),
            "fr" to mapOf("İzleme kaynağı ekle" to "Ajouter une source", "Kaynağı düzenle" to "Modifier la source", "Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle." to "Ajoutez uniquement des adresses de diffusion que vous êtes autorisé à utiliser.", "Kaynak adı (ör. Ev Xtream)" to "Nom de la source (ex. Maison)", "M3U / M3U8 oynatma listesi URL'si" to "URL de la liste M3U / M3U8", "Sunucu adresi (https://...)" to "Adresse du serveur (https://...)", "Kullanıcı adı" to "Nom d’utilisateur", "Şifre" to "Mot de passe", "Bağlan" to "Connecter", "Kaydet" to "Enregistrer", "Sezon" to "Saison", "Sunucu, kullanıcı adı ve şifreyi doldur." to "Renseignez le serveur, le nom d’utilisateur et le mot de passe.", "Geçerli bir M3U/M3U8 URL'si gir." to "Saisissez une URL M3U/M3U8 valide.", 
                "Kaynaklar" to "Sources", "Eğlenceye yeniden hoş geldin" to "Bon retour", "Canlı yayınlarını, filmlerini ve dizilerini keşfet." to "Découvrez vos chaînes, films et séries.",
                "İÇERİKLERİ KEŞFET  →" to "DÉCOUVRIR  →", "Ara" to "Recherche", "Ana Sayfa" to "Accueil", "Filmler" to "Films", "Diziler" to "Séries", "Favoriler" to "Favoris", "İzlediklerim" to "Récemment vus",
                "KATEGORİLER" to "CATÉGORIES", "Tüm kategoriler" to "Toutes les catégories", "Tüm Kanallar" to "Toutes les chaînes", "ARA" to "RECHERCHER",
                "Arama geçmişi" to "Historique de recherche", "Geçmişi sil" to "Effacer l’historique", "Yenile" to "Actualiser", "Düzenle" to "Modifier", "Sil" to "Supprimer", "İptal" to "Annuler",
                "Kaynak ekle" to "Ajouter une source", "Henüz kayıtlı kaynak yok." to "Aucune source enregistrée.", "KONU" to "SYNOPSIS", "OYUNCULAR" to "ACTEURS", "FİLM" to "FILM", "DİZİ" to "SÉRIE",
                "Hepsini sil" to "Tout supprimer", "← Geri dön" to "← Retour", "Uygulamadan çıkmak istiyor musunuz?" to "Voulez-vous quitter l’application ?"
            ),
            "it" to mapOf("İzleme kaynağı ekle" to "Aggiungi sorgente", "Kaynağı düzenle" to "Modifica sorgente", "Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle." to "Aggiungi solo indirizzi streaming che sei autorizzato a utilizzare.", "Kaynak adı (ör. Ev Xtream)" to "Nome sorgente (es. Casa)", "M3U / M3U8 oynatma listesi URL'si" to "URL playlist M3U / M3U8", "Sunucu adresi (https://...)" to "Indirizzo server (https://...)", "Kullanıcı adı" to "Nome utente", "Şifre" to "Password", "Bağlan" to "Connetti", "Kaydet" to "Salva", "Sezon" to "Stagione", "Sunucu, kullanıcı adı ve şifreyi doldur." to "Inserisci server, nome utente e password.", "Geçerli bir M3U/M3U8 URL'si gir." to "Inserisci un URL M3U/M3U8 valido.", 
                "Kaynaklar" to "Sorgenti", "Eğlenceye yeniden hoş geldin" to "Bentornato", "Canlı yayınlarını, filmlerini ve dizilerini keşfet." to "Scopri canali TV, film e serie.",
                "İÇERİKLERİ KEŞFET  →" to "SCOPRI I CONTENUTI  →", "Ara" to "Cerca", "Ana Sayfa" to "Home", "Filmler" to "Film", "Diziler" to "Serie TV", "Favoriler" to "Preferiti", "İzlediklerim" to "Visti di recente",
                "KATEGORİLER" to "CATEGORIE", "Tüm kategoriler" to "Tutte le categorie", "Tüm Kanallar" to "Tutti i canali", "ARA" to "CERCA",
                "Arama geçmişi" to "Cronologia ricerche", "Geçmişi sil" to "Cancella cronologia", "Yenile" to "Aggiorna", "Düzenle" to "Modifica", "Sil" to "Elimina", "İptal" to "Annulla",
                "Kaynak ekle" to "Aggiungi sorgente", "Henüz kayıtlı kaynak yok." to "Nessuna sorgente salvata.", "KONU" to "TRAMA", "OYUNCULAR" to "CAST", "FİLM" to "FILM", "DİZİ" to "SERIE",
                "Hepsini sil" to "Elimina tutto", "← Geri dön" to "← Indietro", "Uygulamadan çıkmak istiyor musunuz?" to "Vuoi uscire dall’app?"
            ),
            "es" to mapOf("İzleme kaynağı ekle" to "Añadir fuente", "Kaynağı düzenle" to "Editar fuente", "Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle." to "Añade solo direcciones de streaming que tengas autorización para usar.", "Kaynak adı (ör. Ev Xtream)" to "Nombre de la fuente (p. ej., Casa)", "M3U / M3U8 oynatma listesi URL'si" to "URL de lista M3U / M3U8", "Sunucu adresi (https://...)" to "Dirección del servidor (https://...)", "Kullanıcı adı" to "Nombre de usuario", "Şifre" to "Contraseña", "Bağlan" to "Conectar", "Kaydet" to "Guardar", "Sezon" to "Temporada", "Sunucu, kullanıcı adı ve şifreyi doldur." to "Introduce el servidor, el usuario y la contraseña.", "Geçerli bir M3U/M3U8 URL'si gir." to "Introduce una URL M3U/M3U8 válida.", 
                "Kaynaklar" to "Fuentes", "Eğlenceye yeniden hoş geldin" to "Bienvenido de nuevo", "Canlı yayınlarını, filmlerini ve dizilerini keşfet." to "Descubre tus canales, películas y series.",
                "İÇERİKLERİ KEŞFET  →" to "EXPLORAR CONTENIDO  →", "Ara" to "Buscar", "Ana Sayfa" to "Inicio", "Filmler" to "Películas", "Diziler" to "Series", "Favoriler" to "Favoritos", "İzlediklerim" to "Vistos recientemente",
                "KATEGORİLER" to "CATEGORÍAS", "Tüm kategoriler" to "Todas las categorías", "Tüm Kanallar" to "Todos los canales", "ARA" to "BUSCAR",
                "Arama geçmişi" to "Historial de búsqueda", "Geçmişi sil" to "Borrar historial", "Yenile" to "Actualizar", "Düzenle" to "Editar", "Sil" to "Eliminar", "İptal" to "Cancelar",
                "Kaynak ekle" to "Añadir fuente", "Henüz kayıtlı kaynak yok." to "No hay fuentes guardadas.", "KONU" to "SINOPSIS", "OYUNCULAR" to "REPARTO", "FİLM" to "PELÍCULA", "DİZİ" to "SERIE",
                "Hepsini sil" to "Borrar todo", "← Geri dön" to "← Volver", "Uygulamadan çıkmak istiyor musunuz?" to "¿Quieres salir de la aplicación?"
            ),
            "ru" to mapOf("İzleme kaynağı ekle" to "Добавить источник", "Kaynağı düzenle" to "Изменить источник", "Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle." to "Добавляйте только потоковые адреса, которыми вы имеете право пользоваться.", "Kaynak adı (ör. Ev Xtream)" to "Название источника (например, Дом)", "M3U / M3U8 oynatma listesi URL'si" to "URL плейлиста M3U / M3U8", "Sunucu adresi (https://...)" to "Адрес сервера (https://...)", "Kullanıcı adı" to "Имя пользователя", "Şifre" to "Пароль", "Bağlan" to "Подключить", "Kaydet" to "Сохранить", "Sezon" to "Сезон", "Sunucu, kullanıcı adı ve şifreyi doldur." to "Введите сервер, имя пользователя и пароль.", "Geçerli bir M3U/M3U8 URL'si gir." to "Введите действительный URL M3U/M3U8.", 
                "Kaynaklar" to "Источники", "Eğlenceye yeniden hoş geldin" to "С возвращением", "Canlı yayınlarını, filmlerini ve dizilerini keşfet." to "Откройте для себя ТВ, фильмы и сериалы.",
                "İÇERİKLERİ KEŞFET  →" to "ОТКРЫТЬ КОНТЕНТ  →", "Ara" to "Поиск", "Ana Sayfa" to "Главная", "Filmler" to "Фильмы", "Diziler" to "Сериалы", "Favoriler" to "Избранное", "İzlediklerim" to "Недавно просмотрено",
                "KATEGORİLER" to "КАТЕГОРИИ", "Tüm kategoriler" to "Все категории", "Tüm Kanallar" to "Все каналы", "ARA" to "ПОИСК",
                "Arama geçmişi" to "История поиска", "Geçmişi sil" to "Очистить историю", "Yenile" to "Обновить", "Düzenle" to "Изменить", "Sil" to "Удалить", "İptal" to "Отмена",
                "Kaynak ekle" to "Добавить источник", "Henüz kayıtlı kaynak yok." to "Нет сохранённых источников.", "KONU" to "ОПИСАНИЕ", "OYUNCULAR" to "АКТЁРЫ", "FİLM" to "ФИЛЬМ", "DİZİ" to "СЕРИАЛ",
                "Hepsini sil" to "Удалить всё", "← Geri dön" to "← Назад", "Uygulamadan çıkmak istiyor musunuz?" to "Выйти из приложения?"
            ),
            "ar" to mapOf("İzleme kaynağı ekle" to "إضافة مصدر بث", "Kaynağı düzenle" to "تعديل المصدر", "Yalnızca kullanma hakkına sahip olduğun yayın adreslerini ekle." to "أضف فقط عناوين البث المصرح لك باستخدامها.", "Kaynak adı (ör. Ev Xtream)" to "اسم المصدر (مثال: المنزل)", "M3U / M3U8 oynatma listesi URL'si" to "رابط قائمة تشغيل M3U / M3U8", "Sunucu adresi (https://...)" to "عنوان الخادم (https://...)", "Kullanıcı adı" to "اسم المستخدم", "Şifre" to "كلمة المرور", "Bağlan" to "اتصال", "Kaydet" to "حفظ", "Sezon" to "الموسم", "Sunucu, kullanıcı adı ve şifreyi doldur." to "أدخل الخادم واسم المستخدم وكلمة المرور.", "Geçerli bir M3U/M3U8 URL'si gir." to "أدخل رابط M3U/M3U8 صالحاً.", 
                "Kaynaklar" to "المصادر", "Eğlenceye yeniden hoş geldin" to "مرحباً بعودتك", "Canlı yayınlarını, filmlerini ve dizilerini keşfet." to "اكتشف القنوات والأفلام والمسلسلات.",
                "İÇERİKLERİ KEŞFET  →" to "اكتشف المحتوى  →", "Ara" to "بحث", "Ana Sayfa" to "الرئيسية", "Filmler" to "أفلام", "Diziler" to "مسلسلات", "Favoriler" to "المفضلة", "İzlediklerim" to "شوهد مؤخراً",
                "KATEGORİLER" to "الفئات", "Tüm kategoriler" to "كل الفئات", "Tüm Kanallar" to "كل القنوات", "ARA" to "بحث",
                "Arama geçmişi" to "سجل البحث", "Geçmişi sil" to "مسح السجل", "Yenile" to "تحديث", "Düzenle" to "تعديل", "Sil" to "حذف", "İptal" to "إلغاء",
                "Kaynak ekle" to "إضافة مصدر", "Henüz kayıtlı kaynak yok." to "لا توجد مصادر محفوظة.", "KONU" to "الملخص", "OYUNCULAR" to "طاقم التمثيل", "FİLM" to "فيلم", "DİZİ" to "مسلسل",
                "Hepsini sil" to "حذف الكل", "← Geri dön" to "← رجوع", "Uygulamadan çıkmak istiyor musunuz?" to "هل تريد الخروج من التطبيق؟"
            )
        )
        val extraTranslations = mapOf(
            "en" to mapOf(
                "Hepsini gizle" to "Hide all", "Hepsini göster" to "Show all", "Seçili dilin kategorilerini göster" to "Show English categories",
                "Gösterilecek TV kategorisi yok." to "No TV categories to display.", "Gösterilecek film kategorisi yok." to "No movie categories to display.",
                "Gösterilecek dizi kategorisi yok." to "No series categories to display.", "Gösteriliyor — seçerek gizle" to "Visible — select to hide",
                "Gizli — seçerek göster" to "Hidden — select to show", "Zemin ve vurgu rengi" to "Background and accent color",
                "Renk seçimi anında uygulanır." to "Theme changes are applied immediately.", "Şeffaf menü" to "Transparent menu",
                "Açık" to "On", "Kapalı" to "Off", "Sol menü arka planı saydam; kapatmak için seç." to "The sidebar background is transparent; select to turn it off.",
                "Sol menü ayrı bir panel olarak görünür; saydam yapmak için seç." to "The sidebar appears as a separate panel; select to make it transparent.",
                "Sürüm" to "Version", "Derleme tarihi" to "Build date", "Uygulama boyutu" to "App size", "Bilinmiyor" to "Unknown",
                "TV" to "TV", "Filmler" to "Movies", "Diziler" to "Series", "Film" to "Movies", "Dizi" to "Series",
                "Geçmiş kaydını sil" to "Delete history entry", "izleme geçmişinden silinsin mi?" to "be removed from watch history?",
                "Sunucuya ulaşılamadı. İnternet bağlantısını ve sunucuyu kontrol et." to "Could not reach the server. Check your internet connection and the server.",
                "Yüklenemedi" to "Could not load", "Kaynak yükleniyor…" to "Loading source…",
                "Seçileni sil (" to "Delete selected (", "Geçmişi sil" to "Clear history",
                "Kayıtlı kaynaklar" to "Saved sources", "Kapat" to "Close", "Kaynak ekle" to "Add source",
                "İzleme geçmişini temizle" to "Clear watch history", "İptal" to "Cancel", "Sil" to "Delete",
                "Antrasit • Mavi" to "Anthracite • Blue", "Gece • Altın" to "Night • Gold",
                "Lacivert • Turkuaz" to "Navy • Turquoise", "Koyu • Mor" to "Dark • Purple"
            ),
            "de" to mapOf(
                "Hepsini gizle" to "Alle ausblenden", "Hepsini göster" to "Alle anzeigen", "Seçili dilin kategorilerini göster" to "Deutsche Kategorien anzeigen",
                "Gösterilecek TV kategorisi yok." to "Keine TV-Kategorien vorhanden.", "Gösterilecek film kategorisi yok." to "Keine Filmkategorien vorhanden.",
                "Gösterilecek dizi kategorisi yok." to "Keine Serienkategorien vorhanden.", "Gösteriliyor — seçerek gizle" to "Sichtbar — zum Ausblenden auswählen",
                "Gizli — seçerek göster" to "Ausgeblendet — zum Anzeigen auswählen", "Zemin ve vurgu rengi" to "Hintergrund- und Akzentfarbe",
                "Renk seçimi anında uygulanır." to "Farbänderungen werden sofort übernommen.", "Şeffaf menü" to "Transparentes Menü",
                "Açık" to "Ein", "Kapalı" to "Aus", "Sürüm" to "Version", "Derleme tarihi" to "Build-Datum",
                "Uygulama boyutu" to "App-Größe", "Bilinmiyor" to "Unbekannt", "Film" to "Filme", "Dizi" to "Serien",
                "Geçmiş kaydını sil" to "Verlaufseintrag löschen", "Sunucuya ulaşılamadı. İnternet bağlantısını ve sunucuyu kontrol et." to "Server nicht erreichbar. Prüfe Internetverbindung und Server.",
                "Yüklenemedi" to "Laden fehlgeschlagen", "Kaynak yükleniyor…" to "Quelle wird geladen…", "Seçileni sil (" to "Auswahl löschen (",
                "Geçmişi sil" to "Verlauf löschen", "Kayıtlı kaynaklar" to "Gespeicherte Quellen", "Kapat" to "Schließen",
                "Antrasit • Mavi" to "Anthrazit • Blau", "Gece • Altın" to "Nacht • Gold", "Lacivert • Turkuaz" to "Marineblau • Türkis", "Koyu • Mor" to "Dunkel • Violett"
            ),
            "fr" to mapOf(
                "Hepsini gizle" to "Tout masquer", "Hepsini göster" to "Tout afficher", "Seçili dilin kategorilerini göster" to "Afficher les catégories françaises",
                "Gösterilecek TV kategorisi yok." to "Aucune catégorie TV à afficher.", "Gösterilecek film kategorisi yok." to "Aucune catégorie de films à afficher.",
                "Gösterilecek dizi kategorisi yok." to "Aucune catégorie de séries à afficher.", "Gösteriliyor — seçerek gizle" to "Visible — sélectionner pour masquer",
                "Gizli — seçerek göster" to "Masqué — sélectionner pour afficher", "Zemin ve vurgu rengi" to "Arrière-plan et couleur d’accent",
                "Renk seçimi anında uygulanır." to "Les changements sont appliqués immédiatement.", "Şeffaf menü" to "Menu transparent",
                "Açık" to "Activé", "Kapalı" to "Désactivé", "Sürüm" to "Version", "Derleme tarihi" to "Date de compilation",
                "Uygulama boyutu" to "Taille de l’application", "Bilinmiyor" to "Inconnu", "Film" to "Films", "Dizi" to "Séries",
                "Geçmiş kaydını sil" to "Supprimer l’entrée de l’historique", "Sunucuya ulaşılamadı. İnternet bağlantısını ve sunucuyu kontrol et." to "Serveur inaccessible. Vérifiez votre connexion et le serveur.",
                "Yüklenemedi" to "Échec du chargement", "Kaynak yükleniyor…" to "Chargement de la source…", "Seçileni sil (" to "Supprimer la sélection (",
                "Geçmişi sil" to "Effacer l’historique", "Kayıtlı kaynaklar" to "Sources enregistrées", "Kapat" to "Fermer",
                "Antrasit • Mavi" to "Anthracite • Bleu", "Gece • Altın" to "Nuit • Or", "Lacivert • Turkuaz" to "Bleu marine • Turquoise", "Koyu • Mor" to "Sombre • Violet"
            ),
            "it" to mapOf(
                "Hepsini gizle" to "Nascondi tutto", "Hepsini göster" to "Mostra tutto", "Seçili dilin kategorilerini göster" to "Mostra le categorie italiane",
                "Gösterilecek TV kategorisi yok." to "Nessuna categoria TV da mostrare.", "Gösterilecek film kategorisi yok." to "Nessuna categoria film da mostrare.",
                "Gösterilecek dizi kategorisi yok." to "Nessuna categoria serie da mostrare.", "Gösteriliyor — seçerek gizle" to "Visibile — seleziona per nascondere",
                "Gizli — seçerek göster" to "Nascosto — seleziona per mostrare", "Zemin ve vurgu rengi" to "Sfondo e colore principale",
                "Renk seçimi anında uygulanır." to "Le modifiche vengono applicate subito.", "Şeffaf menü" to "Menu trasparente",
                "Açık" to "Attivo", "Kapalı" to "Disattivato", "Sürüm" to "Versione", "Derleme tarihi" to "Data build",
                "Uygulama boyutu" to "Dimensione app", "Bilinmiyor" to "Sconosciuto", "Film" to "Film", "Dizi" to "Serie",
                "Geçmiş kaydını sil" to "Elimina voce cronologia", "Sunucuya ulaşılamadı. İnternet bağlantısını ve sunucuyu kontrol et." to "Server non raggiungibile. Controlla la connessione e il server.",
                "Yüklenemedi" to "Caricamento non riuscito", "Kaynak yükleniyor…" to "Caricamento sorgente…", "Seçileni sil (" to "Elimina selezionati (",
                "Geçmişi sil" to "Cancella cronologia", "Kayıtlı kaynaklar" to "Sorgenti salvate", "Kapat" to "Chiudi",
                "Antrasit • Mavi" to "Antracite • Blu", "Gece • Altın" to "Notte • Oro", "Lacivert • Turkuaz" to "Blu navy • Turchese", "Koyu • Mor" to "Scuro • Viola"
            ),
            "es" to mapOf(
                "Hepsini gizle" to "Ocultar todo", "Hepsini göster" to "Mostrar todo", "Seçili dilin kategorilerini göster" to "Mostrar categorías españolas",
                "Gösterilecek TV kategorisi yok." to "No hay categorías de TV para mostrar.", "Gösterilecek film kategorisi yok." to "No hay categorías de películas para mostrar.",
                "Gösterilecek dizi kategorisi yok." to "No hay categorías de series para mostrar.", "Gösteriliyor — seçerek gizle" to "Visible — selecciona para ocultar",
                "Gizli — seçerek göster" to "Oculto — selecciona para mostrar", "Zemin ve vurgu rengi" to "Fondo y color de acento",
                "Renk seçimi anında uygulanır." to "Los cambios se aplican inmediatamente.", "Şeffaf menü" to "Menú transparente",
                "Açık" to "Activado", "Kapalı" to "Desactivado", "Sürüm" to "Versión", "Derleme tarihi" to "Fecha de compilación",
                "Uygulama boyutu" to "Tamaño de la aplicación", "Bilinmiyor" to "Desconocido", "Film" to "Películas", "Dizi" to "Series",
                "Geçmiş kaydını sil" to "Eliminar entrada del historial", "Sunucuya ulaşılamadı. İnternet bağlantısını ve sunucuyu kontrol et." to "No se pudo acceder al servidor. Comprueba la conexión y el servidor.",
                "Yüklenemedi" to "No se pudo cargar", "Kaynak yükleniyor…" to "Cargando fuente…", "Seçileni sil (" to "Eliminar selección (",
                "Geçmişi sil" to "Borrar historial", "Kayıtlı kaynaklar" to "Fuentes guardadas", "Kapat" to "Cerrar",
                "Antrasit • Mavi" to "Antracita • Azul", "Gece • Altın" to "Noche • Dorado", "Lacivert • Turkuaz" to "Azul marino • Turquesa", "Koyu • Mor" to "Oscuro • Morado"
            ),
            "ru" to mapOf(
                "Hepsini gizle" to "Скрыть все", "Hepsini göster" to "Показать все", "Seçili dilin kategorilerini göster" to "Показать русские категории",
                "Gösterilecek TV kategorisi yok." to "Нет категорий ТВ для отображения.", "Gösterilecek film kategorisi yok." to "Нет категорий фильмов для отображения.",
                "Gösterilecek dizi kategorisi yok." to "Нет категорий сериалов для отображения.", "Gösteriliyor — seçerek gizle" to "Показано — выберите, чтобы скрыть",
                "Gizli — seçerek göster" to "Скрыто — выберите, чтобы показать", "Zemin ve vurgu rengi" to "Цвет фона и акцента",
                "Renk seçimi anında uygulanır." to "Изменения применяются сразу.", "Şeffaf menü" to "Прозрачное меню",
                "Açık" to "Вкл.", "Kapalı" to "Выкл.", "Sürüm" to "Версия", "Derleme tarihi" to "Дата сборки",
                "Uygulama boyutu" to "Размер приложения", "Bilinmiyor" to "Неизвестно", "Film" to "Фильмы", "Dizi" to "Сериалы",
                "Geçmiş kaydını sil" to "Удалить запись истории", "Sunucuya ulaşılamadı. İnternet bağlantısını ve sunucuyu kontrol et." to "Не удалось связаться с сервером. Проверьте подключение и сервер.",
                "Yüklenemedi" to "Не удалось загрузить", "Kaynak yükleniyor…" to "Загрузка источника…", "Seçileni sil (" to "Удалить выбранное (",
                "Geçmişi sil" to "Очистить историю", "Kayıtlı kaynaklar" to "Сохранённые источники", "Kapat" to "Закрыть",
                "Antrasit • Mavi" to "Антрацит • Синий", "Gece • Altın" to "Ночь • Золото", "Lacivert • Turkuaz" to "Тёмно-синий • Бирюзовый", "Koyu • Mor" to "Тёмный • Фиолетовый"
            ),
            "ar" to mapOf(
                "Hepsini gizle" to "إخفاء الكل", "Hepsini göster" to "إظهار الكل", "Seçili dilin kategorilerini göster" to "إظهار الفئات العربية",
                "Gösterilecek TV kategorisi yok." to "لا توجد فئات تلفزيونية لعرضها.", "Gösterilecek film kategorisi yok." to "لا توجد فئات أفلام لعرضها.",
                "Gösterilecek dizi kategorisi yok." to "لا توجد فئات مسلسلات لعرضها.", "Gösteriliyor — seçerek gizle" to "ظاهر — اختر للإخفاء",
                "Gizli — seçerek göster" to "مخفي — اختر للإظهار", "Zemin ve vurgu rengi" to "لون الخلفية والتمييز",
                "Renk seçimi anında uygulanır." to "تُطبّق التغييرات فوراً.", "Şeffaf menü" to "قائمة شفافة",
                "Açık" to "مفعّل", "Kapalı" to "متوقف", "Sürüm" to "الإصدار", "Derleme tarihi" to "تاريخ البناء",
                "Uygulama boyutu" to "حجم التطبيق", "Bilinmiyor" to "غير معروف", "Film" to "أفلام", "Dizi" to "مسلسلات",
                "Geçmiş kaydını sil" to "حذف سجل المشاهدة", "Sunucuya ulaşılamadı. İnternet bağlantısını ve sunucuyu kontrol et." to "تعذر الوصول إلى الخادم. تحقق من الاتصال والخادم.",
                "Yüklenemedi" to "تعذر التحميل", "Kaynak yükleniyor…" to "جارٍ تحميل المصدر…", "Seçileni sil (" to "حذف المحدد (",
                "Geçmişi sil" to "مسح السجل", "Kayıtlı kaynaklar" to "المصادر المحفوظة", "Kapat" to "إغلاق",
                "Antrasit • Mavi" to "فحمي • أزرق", "Gece • Altın" to "ليلي • ذهبي", "Lacivert • Turkuaz" to "كحلي • فيروزي", "Koyu • Mor" to "داكن • بنفسجي"
            )
        )
        val runtimeTranslations = mapOf(
            "en" to mapOf(
                "TV, film ve dizi kategorilerini ayrı ayrı yönet" to "Manage TV, movie and series categories separately",
                "TV, filmler ve diziler yükleniyor…" to "Loading TV, movies and series…",
                "Sunucu bağlantıyı kapattı. Yeniden deneyin." to "The server closed the connection. Please try again.",
                "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin." to "Could not reach the server. Check your internet connection.",
                "Bağlantı zaman aşımına uğradı. Yeniden deneyin." to "Connection timed out. Please try again.",
                "Güvenli bağlantı kurulamadı." to "A secure connection could not be established.",
                "Sunucu isteği reddetti" to "The server rejected the request",
                "Kaynak yüklenemedi. İnternet bağlantınızı ve kaynak bilgilerini kontrol edin." to "The source could not be loaded. Check your internet connection and source details.",
                "Bölümler yüklenemedi" to "Episodes could not be loaded"
            ),
            "de" to mapOf(
                "TV, film ve dizi kategorilerini ayrı ayrı yönet" to "TV-, Film- und Serienkategorien getrennt verwalten",
                "TV, filmler ve diziler yükleniyor…" to "TV, Filme und Serien werden geladen…",
                "Sunucu bağlantıyı kapattı. Yeniden deneyin." to "Der Server hat die Verbindung geschlossen. Bitte erneut versuchen.",
                "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin." to "Server nicht erreichbar. Bitte Internetverbindung prüfen.",
                "Bağlantı zaman aşımına uğradı. Yeniden deneyin." to "Zeitüberschreitung der Verbindung. Bitte erneut versuchen.",
                "Güvenli bağlantı kurulamadı." to "Sichere Verbindung konnte nicht hergestellt werden.",
                "Sunucu isteği reddetti" to "Der Server hat die Anfrage abgelehnt",
                "Kaynak yüklenemedi. İnternet bağlantınızı ve kaynak bilgilerini kontrol edin." to "Quelle konnte nicht geladen werden. Internetverbindung und Quelldaten prüfen."
            ),
            "fr" to mapOf(
                "TV, film ve dizi kategorilerini ayrı ayrı yönet" to "Gérer séparément les catégories TV, films et séries",
                "TV, filmler ve diziler yükleniyor…" to "Chargement de la TV, des films et des séries…",
                "Sunucu bağlantıyı kapattı. Yeniden deneyin." to "Le serveur a fermé la connexion. Réessayez.",
                "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin." to "Serveur inaccessible. Vérifiez votre connexion Internet.",
                "Bağlantı zaman aşımına uğradı. Yeniden deneyin." to "Délai de connexion dépassé. Réessayez.",
                "Güvenli bağlantı kurulamadı." to "Impossible d’établir une connexion sécurisée.",
                "Sunucu isteği reddetti" to "Le serveur a refusé la requête",
                "Kaynak yüklenemedi. İnternet bağlantınızı ve kaynak bilgilerini kontrol edin." to "Impossible de charger la source. Vérifiez la connexion et les informations de la source."
            ),
            "it" to mapOf(
                "TV, film ve dizi kategorilerini ayrı ayrı yönet" to "Gestisci separatamente le categorie TV, film e serie",
                "TV, filmler ve diziler yükleniyor…" to "Caricamento di TV, film e serie…",
                "Sunucu bağlantıyı kapattı. Yeniden deneyin." to "Il server ha chiuso la connessione. Riprova.",
                "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin." to "Server non raggiungibile. Controlla la connessione Internet.",
                "Bağlantı zaman aşımına uğradı. Yeniden deneyin." to "Connessione scaduta. Riprova.",
                "Güvenli bağlantı kurulamadı." to "Impossibile stabilire una connessione sicura.",
                "Sunucu isteği reddetti" to "Il server ha rifiutato la richiesta",
                "Kaynak yüklenemedi. İnternet bağlantınızı ve kaynak bilgilerini kontrol edin." to "Impossibile caricare la sorgente. Controlla la connessione e i dati della sorgente."
            ),
            "es" to mapOf(
                "TV, film ve dizi kategorilerini ayrı ayrı yönet" to "Gestiona por separado las categorías de TV, películas y series",
                "TV, filmler ve diziler yükleniyor…" to "Cargando TV, películas y series…",
                "Sunucu bağlantıyı kapattı. Yeniden deneyin." to "El servidor cerró la conexión. Inténtalo de nuevo.",
                "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin." to "No se pudo acceder al servidor. Comprueba tu conexión a Internet.",
                "Bağlantı zaman aşımına uğradı. Yeniden deneyin." to "Se agotó el tiempo de conexión. Inténtalo de nuevo.",
                "Güvenli bağlantı kurulamadı." to "No se pudo establecer una conexión segura.",
                "Sunucu isteği reddetti" to "El servidor rechazó la solicitud",
                "Kaynak yüklenemedi. İnternet bağlantınızı ve kaynak bilgilerini kontrol edin." to "No se pudo cargar la fuente. Comprueba la conexión y los datos de la fuente."
            ),
            "ru" to mapOf(
                "TV, film ve dizi kategorilerini ayrı ayrı yönet" to "Управляйте категориями ТВ, фильмов и сериалов отдельно",
                "TV, filmler ve diziler yükleniyor…" to "Загрузка ТВ, фильмов и сериалов…",
                "Sunucu bağlantıyı kapattı. Yeniden deneyin." to "Сервер закрыл соединение. Попробуйте ещё раз.",
                "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin." to "Не удалось связаться с сервером. Проверьте подключение к интернету.",
                "Bağlantı zaman aşımına uğradı. Yeniden deneyin." to "Время ожидания соединения истекло. Попробуйте ещё раз.",
                "Güvenli bağlantı kurulamadı." to "Не удалось установить защищённое соединение.",
                "Sunucu isteği reddetti" to "Сервер отклонил запрос",
                "Kaynak yüklenemedi. İnternet bağlantınızı ve kaynak bilgilerini kontrol edin." to "Не удалось загрузить источник. Проверьте подключение и данные источника."
            ),
            "ar" to mapOf(
                "TV, film ve dizi kategorilerini ayrı ayrı yönet" to "إدارة فئات التلفاز والأفلام والمسلسلات بشكل منفصل",
                "TV, filmler ve diziler yükleniyor…" to "جارٍ تحميل التلفاز والأفلام والمسلسلات…",
                "Sunucu bağlantıyı kapattı. Yeniden deneyin." to "أغلق الخادم الاتصال. حاول مرة أخرى.",
                "Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin." to "تعذر الوصول إلى الخادم. تحقق من اتصال الإنترنت.",
                "Bağlantı zaman aşımına uğradı. Yeniden deneyin." to "انتهت مهلة الاتصال. حاول مرة أخرى.",
                "Güvenli bağlantı kurulamadı." to "تعذر إنشاء اتصال آمن.",
                "Sunucu isteği reddetti" to "رفض الخادم الطلب",
                "Kaynak yüklenemedi. İnternet bağlantınızı ve kaynak bilgilerini kontrol edin." to "تعذر تحميل المصدر. تحقق من الاتصال وبيانات المصدر."
            )
        )
        // Complete translations for shared UI labels used across every settings and history screen.
        val sharedUiTranslations = when (language) {
            "de" -> mapOf(
                "Henüz içerik yok" to "Noch keine Inhalte vorhanden", "Daha önce izlediğin içerikler" to "Zuvor angesehene Inhalte",
                "Seçileni Sil" to "Ausgewählte löschen", "Geçmişi Sil" to "Verlauf löschen", "Cihaz dili" to "Gerätesprache",
                "Menü" to "Menü", "Buton" to "Schaltfläche", "Arkaplan" to "Hintergrund", "Şeffaf Menü" to "Transparentes Menü",
                "Köşe biçimi" to "Eckenform", "Zemin görünümü" to "Hintergrunddarstellung", "Açık" to "Ein", "Kapalı" to "Aus",
                "Köşeli" to "Abgerundet", "Oval" to "Oval", "Düz" to "Einfarbig", "Gradyan" to "Verlauf",
                "Seçili tema" to "Ausgewähltes Design", "Zemin ve vurgu rengi" to "Hintergrund- und Akzentfarbe",
                "Orman • Zümrüt" to "Wald • Smaragd", "Gece • Yakut" to "Nacht • Rubin", "Gece • Lavanta" to "Nacht • Lavendel",
                "Antrasit • Mavi" to "Anthrazit • Blau", "Gece • Altın" to "Nacht • Gold", "Gece • Buzul" to "Nacht • Gletscher",
                "Kaynak adı (ör. Ev TV)" to "Quellenname (z. B. Zuhause TV)", "Kaynak adı (ör. Ev Portal)" to "Quellenname (z. B. Zuhause Portal)",
                "Portal URL (https://...)" to "Portal-URL (https://...)", "MAC adresi (00:1A:79:XX:XX:XX)" to "MAC-Adresse (00:1A:79:XX:XX:XX)",
                "KAYNAKLAR" to "QUELLEN", "Kaynak adı girin" to "Quellennamen eingeben", "İzleme kaynağını ekleyerek başla" to "Beginne mit dem Hinzufügen einer Quelle",
                "Önce içerik seç." to "Wähle zuerst einen Inhalt aus.", "Geçmiş zaten boş." to "Der Verlauf ist bereits leer.",
                "Tüm izleme geçmişi silinsin mi?" to "Den gesamten Wiedergabeverlauf löschen?", "Kaynak ve içerikleri silinsin mi?" to "Diese Quelle und ihre Inhalte löschen?",
                "Kaynak ekle" to "Quelle hinzufügen", "Bağlan" to "Verbinden", "Kaydet" to "Speichern", "İptal" to "Abbrechen",
                "Düzenle" to "Bearbeiten", "Sil" to "Löschen", "Yenile" to "Aktualisieren", "Kapat" to "Schließen",
                "No content yet" to "Noch keine Inhalte vorhanden"
            )
            "fr" -> mapOf(
                "Henüz içerik yok" to "Aucun contenu pour le moment", "Daha önce izlediğin içerikler" to "Contenus regardés précédemment",
                "Seçileni Sil" to "Supprimer la sélection", "Geçmişi Sil" to "Effacer l’historique", "Cihaz dili" to "Langue de l’appareil",
                "Menü" to "Menu", "Buton" to "Bouton", "Arkaplan" to "Arrière-plan", "Şeffaf Menü" to "Menu transparent",
                "Köşe biçimi" to "Forme des coins", "Zemin görünümü" to "Style d’arrière-plan", "Açık" to "Activé", "Kapalı" to "Désactivé",
                "Köşeli" to "Arrondi", "Oval" to "Ovale", "Düz" to "Uni", "Gradyan" to "Dégradé",
                "Seçili tema" to "Thème sélectionné", "Zemin ve vurgu rengi" to "Arrière-plan et couleur d’accent",
                "Orman • Zümrüt" to "Forêt • Émeraude", "Gece • Yakut" to "Nuit • Rubis", "Gece • Lavanta" to "Nuit • Lavande",
                "Antrasit • Mavi" to "Anthracite • Bleu", "Gece • Altın" to "Nuit • Or", "Gece • Buzul" to "Nuit • Glacier",
                "Kaynak adı (ör. Ev TV)" to "Nom de la source (ex. TV maison)", "Kaynak adı (ör. Ev Portal)" to "Nom de la source (ex. Portail maison)",
                "Portal URL (https://...)" to "URL du portail (https://...)", "MAC adresi (00:1A:79:XX:XX:XX)" to "Adresse MAC (00:1A:79:XX:XX:XX)",
                "KAYNAKLAR" to "SOURCES", "Kaynak adı girin" to "Saisir le nom de la source", "İzleme kaynağını ekleyerek başla" to "Commencez par ajouter une source",
                "Önce içerik seç." to "Sélectionnez d’abord un contenu.", "Geçmiş zaten boş." to "L’historique est déjà vide.",
                "Tüm izleme geçmişi silinsin mi?" to "Supprimer tout l’historique de lecture ?", "Kaynak ve içerikleri silinsin mi?" to "Supprimer cette source et son contenu ?",
                "Kaynak ekle" to "Ajouter une source", "Bağlan" to "Connecter", "Kaydet" to "Enregistrer", "İptal" to "Annuler",
                "Düzenle" to "Modifier", "Sil" to "Supprimer", "Yenile" to "Actualiser", "Kapat" to "Fermer"
            )
            "it" -> mapOf(
                "Henüz içerik yok" to "Nessun contenuto", "Daha önce izlediğin içerikler" to "Contenuti guardati in precedenza",
                "Seçileni Sil" to "Elimina selezionati", "Geçmişi Sil" to "Cancella cronologia", "Cihaz dili" to "Lingua del dispositivo",
                "Menü" to "Menu", "Buton" to "Pulsante", "Arkaplan" to "Sfondo", "Şeffaf Menü" to "Menu trasparente",
                "Köşe biçimi" to "Forma degli angoli", "Zemin görünümü" to "Stile dello sfondo", "Açık" to "Attivo", "Kapalı" to "Disattivo",
                "Köşeli" to "Arrotondato", "Oval" to "Ovale", "Düz" to "Tinta unita", "Gradyan" to "Sfumatura",
                "Seçili tema" to "Tema selezionato", "Zemin ve vurgu rengi" to "Sfondo e colore di accento",
                "Orman • Zümrüt" to "Foresta • Smeraldo", "Gece • Yakut" to "Notte • Rubino", "Gece • Lavanta" to "Notte • Lavanda",
                "Antrasit • Mavi" to "Antracite • Blu", "Gece • Altın" to "Notte • Oro", "Gece • Buzul" to "Notte • Ghiacciaio",
                "Kaynak adı (ör. Ev TV)" to "Nome sorgente (es. TV casa)", "Kaynak adı (ör. Ev Portal)" to "Nome sorgente (es. Portale casa)",
                "Portal URL (https://...)" to "URL del portale (https://...)", "MAC adresi (00:1A:79:XX:XX:XX)" to "Indirizzo MAC (00:1A:79:XX:XX:XX)",
                "KAYNAKLAR" to "SORGENTI", "Kaynak adı girin" to "Inserisci il nome della sorgente", "İzleme kaynağını ekleyerek başla" to "Inizia aggiungendo una sorgente",
                "Önce içerik seç." to "Seleziona prima un contenuto.", "Geçmiş zaten boş." to "La cronologia è già vuota.",
                "Tüm izleme geçmişi silinsin mi?" to "Eliminare tutta la cronologia?", "Kaynak ve içerikleri silinsin mi?" to "Eliminare questa sorgente e i suoi contenuti?",
                "Kaynak ekle" to "Aggiungi sorgente", "Bağlan" to "Connetti", "Kaydet" to "Salva", "İptal" to "Annulla",
                "Düzenle" to "Modifica", "Sil" to "Elimina", "Yenile" to "Aggiorna", "Kapat" to "Chiudi"
            )
            "es" -> mapOf(
                "Henüz içerik yok" to "Aún no hay contenido", "Daha önce izlediğin içerikler" to "Contenido visto anteriormente",
                "Seçileni Sil" to "Eliminar seleccionados", "Geçmişi Sil" to "Borrar historial", "Cihaz dili" to "Idioma del dispositivo",
                "Menü" to "Menú", "Buton" to "Botón", "Arkaplan" to "Fondo", "Şeffaf Menü" to "Menú transparente",
                "Köşe biçimi" to "Forma de las esquinas", "Zemin görünümü" to "Estilo del fondo", "Açık" to "Activado", "Kapalı" to "Desactivado",
                "Köşeli" to "Redondeado", "Oval" to "Ovalado", "Düz" to "Liso", "Gradyan" to "Degradado",
                "Seçili tema" to "Tema seleccionado", "Zemin ve vurgu rengi" to "Fondo y color de acento",
                "Orman • Zümrüt" to "Bosque • Esmeralda", "Gece • Yakut" to "Noche • Rubí", "Gece • Lavanta" to "Noche • Lavanda",
                "Antrasit • Mavi" to "Antracita • Azul", "Gece • Altın" to "Noche • Oro", "Gece • Buzul" to "Noche • Glaciar",
                "Kaynak adı (ör. Ev TV)" to "Nombre de fuente (p. ej., TV casa)", "Kaynak adı (ör. Ev Portal)" to "Nombre de fuente (p. ej., Portal casa)",
                "Portal URL (https://...)" to "URL del portal (https://...)", "MAC adresi (00:1A:79:XX:XX:XX)" to "Dirección MAC (00:1A:79:XX:XX:XX)",
                "KAYNAKLAR" to "FUENTES", "Kaynak adı girin" to "Introduce el nombre de la fuente", "İzleme kaynağını ekleyerek başla" to "Empieza añadiendo una fuente",
                "Önce içerik seç." to "Selecciona primero un contenido.", "Geçmiş zaten boş." to "El historial ya está vacío.",
                "Tüm izleme geçmişi silinsin mi?" to "¿Eliminar todo el historial?", "Kaynak ve içerikleri silinsin mi?" to "¿Eliminar esta fuente y su contenido?",
                "Kaynak ekle" to "Añadir fuente", "Bağlan" to "Conectar", "Kaydet" to "Guardar", "İptal" to "Cancelar",
                "Düzenle" to "Editar", "Sil" to "Eliminar", "Yenile" to "Actualizar", "Kapat" to "Cerrar"
            )
            "ru" -> mapOf(
                "Henüz içerik yok" to "Пока нет контента", "Daha önce izlediğin içerikler" to "Ранее просмотренный контент",
                "Seçileni Sil" to "Удалить выбранное", "Geçmişi Sil" to "Очистить историю", "Cihaz dili" to "Язык устройства",
                "Menü" to "Меню", "Buton" to "Кнопка", "Arkaplan" to "Фон", "Şeffaf Menü" to "Прозрачное меню",
                "Köşe biçimi" to "Форма углов", "Zemin görünümü" to "Стиль фона", "Açık" to "Вкл.", "Kapalı" to "Выкл.",
                "Köşeli" to "Скруглённые", "Oval" to "Овальные", "Düz" to "Сплошной", "Gradyan" to "Градиент",
                "Seçili tema" to "Выбранная тема", "Zemin ve vurgu rengi" to "Цвет фона и акцента",
                "Orman • Zümrüt" to "Лес • Изумруд", "Gece • Yakut" to "Ночь • Рубин", "Gece • Lavanta" to "Ночь • Лаванда",
                "Antrasit • Mavi" to "Антрацит • Синий", "Gece • Altın" to "Ночь • Золото", "Gece • Buzul" to "Ночь • Ледник",
                "Kaynak adı (ör. Ev TV)" to "Имя источника (например, Дом ТВ)", "Kaynak adı (ör. Ev Portal)" to "Имя источника (например, Домашний портал)",
                "Portal URL (https://...)" to "URL портала (https://...)", "MAC adresi (00:1A:79:XX:XX:XX)" to "MAC-адрес (00:1A:79:XX:XX:XX)",
                "KAYNAKLAR" to "ИСТОЧНИКИ", "Kaynak adı girin" to "Введите имя источника", "İzleme kaynağını ekleyerek başla" to "Начните с добавления источника",
                "Önce içerik seç." to "Сначала выберите контент.", "Geçmiş zaten boş." to "История уже пуста.",
                "Tüm izleme geçmişi silinsin mi?" to "Удалить всю историю просмотров?", "Kaynak ve içerikleri silinsin mi?" to "Удалить этот источник и его содержимое?",
                "Kaynak ekle" to "Добавить источник", "Bağlan" to "Подключить", "Kaydet" to "Сохранить", "İptal" to "Отмена",
                "Düzenle" to "Изменить", "Sil" to "Удалить", "Yenile" to "Обновить", "Kapat" to "Закрыть"
            )
            "ar" -> mapOf(
                "Henüz içerik yok" to "لا يوجد محتوى بعد", "Daha önce izlediğin içerikler" to "المحتوى الذي تمت مشاهدته سابقاً",
                "Seçileni Sil" to "حذف المحدد", "Geçmişi Sil" to "مسح السجل", "Cihaz dili" to "لغة الجهاز",
                "Menü" to "القائمة", "Buton" to "زر", "Arkaplan" to "الخلفية", "Şeffaf Menü" to "قائمة شفافة",
                "Köşe biçimi" to "شكل الزوايا", "Zemin görünümü" to "نمط الخلفية", "Açık" to "تشغيل", "Kapalı" to "إيقاف",
                "Köşeli" to "مستدير", "Oval" to "بيضاوي", "Düz" to "لون موحد", "Gradyan" to "تدرج",
                "Seçili tema" to "السمة المحددة", "Zemin ve vurgu rengi" to "لون الخلفية والإبراز",
                "Orman • Zümrüt" to "الغابة • الزمرد", "Gece • Yakut" to "الليل • الياقوت", "Gece • Lavanta" to "الليل • اللافندر",
                "Antrasit • Mavi" to "فحمي • أزرق", "Gece • Altın" to "الليل • ذهبي", "Gece • Buzul" to "الليل • جليدي",
                "Kaynak adı (ör. Ev TV)" to "اسم المصدر (مثال: تلفاز المنزل)", "Kaynak adı (ör. Ev Portal)" to "اسم المصدر (مثال: بوابة المنزل)",
                "Portal URL (https://...)" to "رابط البوابة (https://...)", "MAC adresi (00:1A:79:XX:XX:XX)" to "عنوان MAC (00:1A:79:XX:XX:XX)",
                "KAYNAKLAR" to "المصادر", "Kaynak adı girin" to "أدخل اسم المصدر", "İzleme kaynağını ekleyerek başla" to "ابدأ بإضافة مصدر",
                "Önce içerik seç." to "اختر محتوى أولاً.", "Geçmiş zaten boş." to "السجل فارغ بالفعل.",
                "Tüm izleme geçmişi silinsin mi?" to "هل تريد حذف سجل المشاهدة بالكامل؟", "Kaynak ve içerikleri silinsin mi?" to "هل تريد حذف هذا المصدر ومحتواه؟",
                "Kaynak ekle" to "إضافة مصدر", "Bağlan" to "اتصال", "Kaydet" to "حفظ", "İptal" to "إلغاء",
                "Düzenle" to "تعديل", "Sil" to "حذف", "Yenile" to "تحديث", "Kapat" to "إغلاق"
            )
            else -> emptyMap()
        }
        return translations[value] ?: common[language]?.get(value) ?: extraTranslations[language]?.get(value) ?: runtimeTranslations[language]?.get(value) ?: sharedUiTranslations[value] ?: value
    }
    private val posterExecutor = Executors.newFixedThreadPool(3)
    private val posterCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val items = mutableListOf<MediaItem>()
    private val posterRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<WeakReference<ImageView>>>()
    private val savedSources = mutableListOf<SavedSource>()
    private lateinit var sourceName: EditText
    private val favorites = mutableSetOf<String>()
    private val recent = mutableListOf<MediaItem>()
    private var selectedGroup = "Tüm kategoriler"
    private var focusedCategoryName: String? = null
    private var selectedCategorySection = "Canlı TV"
    private var categoryFocusRestoreKey: String? = null
    // Render live channels in small batches to avoid first-open freezes on Android TV.
    private var liveChannelsLimit = 72
    private var expandedCategory: Pair<String, List<MediaItem>>? = null
    private lateinit var categoryPanel: LinearLayout
    private lateinit var categoryListScroll: ScrollView
    private lateinit var categoryBar: LinearLayout
    private lateinit var categoryScroll: HorizontalScrollView
    private lateinit var mainHeader: LinearLayout
    private lateinit var dateClockRow: LinearLayout
    private lateinit var content: LinearLayout
    private var detailSeries: MediaItem? = null
    private var detailMovie: MediaItem? = null
    private var seriesEpisodeRows: List<Pair<String, List<MediaItem>>> = emptyList()
    private var seriesEpisodesLoading = false
    private var selectedEpisodeSeason = ""
    private var selectedEpisodeIndex = -1
    private var seriesEpisodesError = ""
    private lateinit var status: TextView
    private lateinit var playlistUrl: EditText
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var sourceMode: RadioGroup
    private var selectedType = "Tümü"
    private var xtreamUser = ""
    private var xtreamPass = ""
    private var xtreamBase = ""
    private val prefs by lazy { getSharedPreferences("androtv", MODE_PRIVATE) }
    private var bg = Color.rgb(17, 20, 25)
    // Shared cinematic anthracite surface used consistently across the app.
    private var panel = Color.rgb(8, 10, 14)
    private var accent = Color.rgb(0, 166, 255)
    private var accentBright = Color.rgb(72, 190, 255)
    // Focus styling is independent from the selectable app theme.
    private val focusColor = Color.rgb(112, 205, 255)
    private var muted = Color.rgb(190, 198, 210)
    // Sidebar transparency is independent from the selected color theme.
    private var transparentMenu = true
    private var ovalButtons = false
    private var gradientBackground = true
    private var selectedCategorySourceId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyKaiserImmersiveMode()
        installCrashLogger()
        transparentMenu = prefs.getBoolean("transparent_menu", true)
        ovalButtons = prefs.getBoolean("oval_buttons", false)
        gradientBackground = prefs.getBoolean("gradient_background", prefs.getBoolean("glass_panel_background", true))
        if (!prefs.contains("gradient_background")) prefs.edit().putBoolean("gradient_background", gradientBackground).apply()
        // Restore the selected KaiserTV Global theme before constructing any views.
        if (!prefs.getBoolean("global_themes_initialized", false)) {
            prefs.edit().putString("theme_name", "Antrasit • Mavi").putBoolean("global_themes_initialized", true).apply()
        }
        when (prefs.getString("theme_name", "Antrasit • Mavi")) {
            "Gece • Altın" -> { bg = Color.rgb(15, 16, 20); accent = Color.rgb(224, 184, 95); accentBright = accent }
            "Gece • Buzul" -> { bg = Color.rgb(16, 24, 32); accent = Color.rgb(84, 198, 217); accentBright = accent }
            "Orman • Zümrüt" -> { bg = Color.rgb(16, 27, 24); accent = Color.rgb(32, 201, 151); accentBright = accent }
            "Gece • Yakut" -> { bg = Color.rgb(25, 20, 23); accent = Color.rgb(240, 100, 121); accentBright = accent }
            "Gece • Lavanta" -> { bg = Color.rgb(23, 21, 29); accent = Color.rgb(167, 139, 250); accentBright = accent }
            "Lacivert • Turkuaz" -> { bg = Color.rgb(16, 24, 32); accent = Color.rgb(84, 198, 217); accentBright = accent }
            "Koyu • Mor" -> { bg = Color.rgb(23, 21, 29); accent = Color.rgb(167, 139, 250); accentBright = accent }
            else -> { bg = Color.rgb(17, 20, 25); accent = Color.rgb(0, 166, 255); accentBright = accent }
        }
        // The shared dark surface follows the selected theme instead of staying pure black.
        panel = Color.rgb(8, 10, 14)
        window.statusBarColor = bg; window.navigationBarColor = bg
        favorites.addAll(getSharedPreferences("androtv", MODE_PRIVATE).getStringSet("favorites", emptySet()) ?: emptySet())
        try {
            val saved = JSONArray(getSharedPreferences("androtv", MODE_PRIVATE).getString("recent", "[]"))
            for (i in 0 until saved.length()) {
                val o = saved.optJSONObject(i) ?: continue
                recent.add(MediaItem(o.optString("id"), o.optString("name"), o.optString("url"), o.optString("group"), o.optString("type"), o.optString("icon"), o.optString("extension"), o.optString("seriesId"), o.optString("sourceId"), o.optString("plot"), o.optString("actors"), o.optString("rating"), o.optString("releaseDate"), o.optString("genre")))
            }
        } catch (_: Exception) { }
        hiddenCategories.addAll(prefs.getStringSet("hidden_categories", emptySet()) ?: emptySet())
        loadCachedItems()
        // Start on Home every time; the user's last section remains saved for history only.
        selectedType = "Tümü"
        selectedGroup = "Tüm kategoriler"
        buildUi()
        applyThemeSurfaces()
        showPreviousCrashIfAny()
        loadSavedSources()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(writer))
                getSharedPreferences("androtv_diagnostics", MODE_PRIVATE).edit()
                    .putString("last_crash", writer.toString().take(12000))
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .commit()
            } catch (_: Exception) { }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showPreviousCrashIfAny() {
        val diagnostics = getSharedPreferences("androtv_diagnostics", MODE_PRIVATE)
        val trace = diagnostics.getString("last_crash", null) ?: return
        diagnostics.edit().remove("last_crash").apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("Önceki kanal açılış hatası")
            .setMessage(trace.take(3500))
            .setPositiveButton("Kopyala") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("KaiserTv hata kaydı", trace))
                android.widget.Toast.makeText(this, "Hata kaydı kopyalandı; buraya yapıştır.", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var heroArt: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var settingsBar: LinearLayout
    private lateinit var sidebarMenuScroll: ScrollView
    private lateinit var brandView: LinearLayout
    private lateinit var heroPanel: LinearLayout
    private lateinit var quickAccessScroll: HorizontalScrollView
    private lateinit var sidebar: LinearLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var mainContentScroll: ScrollView
    private var previewPlayer: ExoPlayer? = null
    private var previewView: PlayerView? = null
    private var previewUrl: String = ""
    private var previewChannel: MediaItem? = null
    private var sidebarMode = "main"
    private var settingsPage = "main"
    private var historyType = "Canlı TV"
    private val hiddenCategories = mutableSetOf<String>()
    private var searchQuery = ""
    private val selectedSearchHistory = mutableSetOf<String>()
    private var searchInputFocusPending = false
    private var focusFirstContentOnRender = false
    private var focusFirstCategoryOnRender = false
    private var firstCategoryFocusView: View? = null
    private var selectedSidebarFocusView: View? = null
    private var firstRightContentFocusView: View? = null
    private var initialSidebarFocusPending = true
    private var isLoadingSource = false
    private var loadProgress = 0
    private var hasLoadedSource = false

    private fun isDescendantView(parent: ViewGroup, view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    private fun buildUi() {
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val root = object : LinearLayout(this) {
            private fun centerOnScreen(view: View): Pair<Float, Float> {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                return Pair(location[0] + view.width / 2f, location[1] + view.height / 2f)
            }

            private fun isAhead(from: View, to: View, direction: Int): Boolean {
                val (fx, fy) = centerOnScreen(from)
                val (tx, ty) = centerOnScreen(to)
                return when (direction) {
                    View.FOCUS_LEFT -> tx < fx - 1f
                    View.FOCUS_RIGHT -> tx > fx + 1f
                    View.FOCUS_UP -> ty < fy - 1f
                    View.FOCUS_DOWN -> ty > fy + 1f
                    else -> false
                }
            }

            private fun directionalFallback(focused: View, direction: Int, sidebarOnly: Boolean?): View? {
                val (fx, fy) = centerOnScreen(focused)
                val candidates = ArrayList<View>()
                fun collect(parent: ViewGroup) {
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChildAt(i)
                        if (child.isShown && child.isEnabled && child.isFocusable &&
                            child.width > 0 && child.height > 0 && child !== focused) {
                            val inSidebar = ::sidebar.isInitialized && isDescendantView(sidebar, child)
                            if (sidebarOnly == null || inSidebar == sidebarOnly) candidates.add(child)
                        }
                        if (child is ViewGroup) collect(child)
                    }
                }
                collect(this)
                return candidates.mapNotNull { candidate ->
                    val (x, y) = centerOnScreen(candidate)
                    val primary = when (direction) {
                        View.FOCUS_LEFT -> fx - x
                        View.FOCUS_RIGHT -> x - fx
                        View.FOCUS_UP -> fy - y
                        View.FOCUS_DOWN -> y - fy
                        else -> -1f
                    }
                    if (primary <= 1f) null else {
                        val secondary = if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
                            kotlin.math.abs(y - fy)
                        } else kotlin.math.abs(x - fx)
                        candidate to (primary + secondary * 2.2f)
                    }
                }.minByOrNull { it.second }?.first
            }

            override fun focusSearch(focused: View, direction: Int): View? {
                val focusIsInSidebar = ::sidebar.isInitialized && isDescendantView(sidebar, focused)
                val focusIsInContent = ::mainContentScroll.isInitialized && isDescendantView(mainContentScroll, focused)
                val candidate = super.focusSearch(focused, direction)
                val crossingAllowed = (focusIsInSidebar && direction == View.FOCUS_RIGHT) ||
                    (focusIsInContent && direction == View.FOCUS_LEFT)
                val candidateIsInSidebar = candidate != null && ::sidebar.isInitialized &&
                    isDescendantView(sidebar, candidate)
                val crossesBoundary = (focusIsInSidebar && candidate != null && !candidateIsInSidebar) ||
                    (focusIsInContent && candidateIsInSidebar)
                if (crossesBoundary && !crossingAllowed) {
                    // Keep navigation in its current area unless the user presses the
                    // direction that explicitly crosses between sidebar and content.
                    return directionalFallback(focused, direction, focusIsInSidebar) ?: focused
                }
                if (candidate != null && candidate.isShown && candidate.isFocusable &&
                    isAhead(focused, candidate, direction) &&
                    (!crossesBoundary || crossingAllowed)) return candidate

                val sidebarOnly = when {
                    focusIsInSidebar && direction != View.FOCUS_RIGHT -> true
                    focusIsInContent && direction != View.FOCUS_LEFT -> false
                    else -> null
                }
                return directionalFallback(focused, direction, sidebarOnly) ?: candidate ?: focused
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14, 18, 12)
            setBackgroundColor(bg)
        }

        root.clipChildren = false
        root.clipToPadding = false
        rootLayout = root
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 12, dp(6), 10)
            background = android.graphics.drawable.ColorDrawable(if (transparentMenu) Color.TRANSPARENT else panel)
            // Keep rounded category-selection backgrounds fully visible in both themes.
            clipChildren = false
            clipToPadding = false
        }
        root.addView(sidebar, LinearLayout.LayoutParams(dp(190), -1).apply { setMargins(0, 0, dp(8), 0) })

        val primaryMenuColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(180), -1)
        }
        sidebar.addView(primaryMenuColumn)

        brandView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(2), dp(2), 0, dp(2))
            clipChildren = false
        }
        brandView.addView(TextView(this).apply {
            text = "▶"
            textSize = 30f
            setTextColor(Color.rgb(0, 174, 255))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(dp(38), dp(48)))
        val brandWords = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, 0, 0)
        }
        brandWords.addView(TextView(this).apply {
            text = "KAISER"
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-1, dp(22)))
        brandWords.addView(TextView(this).apply {
            val tvPlus = android.text.SpannableString("TV+")
            tvPlus.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 0, 2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tvPlus.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 196, 0)), 2, 3, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = tvPlus
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }, LinearLayout.LayoutParams(-1, dp(25)))
        brandView.addView(brandWords, LinearLayout.LayoutParams(0, dp(50), 1f))
        brandView.contentDescription = "KAISER TV+"
        primaryMenuColumn.addView(brandView, LinearLayout.LayoutParams(-1, dp(76)))
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
        }
        sidebarMenuScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(navBar)
        }
        primaryMenuColumn.addView(sidebarMenuScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        settingsBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        primaryMenuColumn.addView(settingsBar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        categoryPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(78), dp(4), dp(8))
            clipChildren = false
        }
        categoryListScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            clipToPadding = false
            clipChildren = false
            visibility = View.GONE
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(categoryPanel, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sidebar.addView(categoryListScroll, LinearLayout.LayoutParams(dp(138), -1))
        rebuildNavigation()

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        root.addView(main, LinearLayout.LayoutParams(0, -1, 1f))

        mainHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 0, 4, 8)
        }
        mainHeader.addView(View(this), LinearLayout.LayoutParams(0, dp(40), 1f))
        mainHeader.addView(TextView(this).apply {
            text = t("Kaynaklar"); textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { showSourcesDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        mainHeader.addView(TextView(this).apply {
            text = "+"; textSize = 25f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(15, 18, 27))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(accent); setStroke(dp(1), accentBright)
            }
            isFocusable = true
            setOnFocusChangeListener { v, hasFocus ->
                v.scaleX = if (hasFocus) 1.08f else 1f
                v.scaleY = if (hasFocus) 1.08f else 1f
            }
            contentDescription = "Kaynak ekle"
            setOnClickListener { showConnectionDialog() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(8), 0, dp(4), 0) })
        // Fixed date and live clock: remains visible while the content below scrolls.
        dateClockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(4), 0, dp(10), dp(8))
            // Keep the fixed search/date header visually above every scrolling poster,
            // including cards temporarily raised by their focus animation.
            setBackgroundColor(bg)
            clipChildren = true
            clipToPadding = true
            elevation = dp(48).toFloat()
            translationZ = dp(48).toFloat()
        }
        // Top search bar removed; search is opened from the navigation icon.
        main.addView(dateClockRow, 0, LinearLayout.LayoutParams(-1, 0))

        main.addView(mainHeader)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(26, 18, 18, 18)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(33, 42, 59), Color.rgb(12, 17, 28))).apply {
                cornerRadius = 0f; setStroke(1, Color.rgb(54, 64, 82))
            }
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroCopy.addView(TextView(this).apply {
            text = t("KAISERTV ORIGINAL  •  SENİN KÜTÜPHANEN"); textSize = 10f
            setTextColor(accentBright); letterSpacing = 0.10f
        })
        heroTitle = TextView(this).apply {
            text = t("Eğlenceye yeniden hoş geldin"); textSize = 27f
            setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 4); maxLines = 2
        }
        heroCopy.addView(heroTitle)
        heroSubtitle = TextView(this).apply {
            text = t("Canlı yayınlarını, filmlerini ve dizilerini keşfet."); textSize = 13f
            setTextColor(muted); maxLines = 2
        }
        heroCopy.addView(heroSubtitle)
        heroCopy.addView(makeButton(t("İÇERİKLERİ KEŞFET  →"), true) {
            selectedType = "Tümü"; selectedGroup = "Tüm kategoriler"; render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40).apply { setMargins(0, 13, 0, 0) })
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        heroArt = FrameLayout(this).apply {
            background = roundedDrawable(Color.rgb(25, 33, 48), 18, Color.rgb(60, 70, 89))
            clipToOutline = true
        }
        heroArt.addView(TextView(this).apply {
            text = "K"; textSize = 88f; setTextColor(accent)
            setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(heroArt, LinearLayout.LayoutParams(164, 150).apply { setMargins(16, 0, 0, 0) })
        heroPanel = hero
        main.addView(hero, LinearLayout.LayoutParams(-1, 220).apply { setMargins(0, 0, 0, 10) })
        hero.visibility = View.VISIBLE

        quickAccessScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val quickAccess = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val quickItems = listOf(
            Triple("◉\nCanlı TV", Color.rgb(0, 112, 230), "Canlı TV"),
            Triple("▣\nFilmler", Color.rgb(196, 39, 65), "Filmler"),
            Triple("▱\nDiziler", Color.rgb(123, 47, 190), "Diziler"),
            Triple("☺\nÇocuk", Color.rgb(0, 145, 95), "çocuk"),
            Triple("♧\nBelgeseller", Color.rgb(190, 104, 0), "belgesel"),
            Triple("♜\nSpor", Color.rgb(0, 112, 135), "spor")
        )
        quickItems.forEach { (label, tileColor, target) ->
            val tile = Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = roundedDrawable(tileColor, if (ovalButtons) 24 else 0, tileColor)
                stateListAnimator = null
                isFocusable = true
                setOnFocusChangeListener { v, focused ->
                    v.background = roundedDrawable(if (focused) accent else tileColor, if (ovalButtons) 24 else 0)
                    v.elevation = if (focused) dp(5).toFloat() else 0f
                    v.scaleX = if (focused) 1.03f else 1f
                    v.scaleY = if (focused) 1.03f else 1f
                }
                setOnClickListener {
                    detailMovie = null; detailSeries = null
                    if (target in listOf("Canlı TV", "Filmler", "Diziler")) {
                        selectedType = target
                        selectedGroup = "Tüm kategoriler"
                        sidebarMode = if (target == "Canlı TV" || target == "Filmler" || target == "Diziler") "context" else "main"
                        searchQuery = ""
                    } else {
                        selectedType = "Tümü"
                        sidebarMode = "search"
                        searchQuery = target
                    }
                    render()
                }
            }
            quickAccess.addView(tile, LinearLayout.LayoutParams(dp(142), dp(82)).apply { setMargins(0, 0, dp(9), 0) })
        }
        quickAccessScroll.addView(quickAccess)
        main.addView(quickAccessScroll, LinearLayout.LayoutParams(-1, dp(92)).apply { setMargins(0, 0, 0, dp(6)) })

        status = TextView(this).apply {
            text = t("Kaisertv hazır. Kaynak ekleyerek içeriklerini yükleyebilirsin.")
            textSize = 12f; setTextColor(muted); setPadding(3, 2, 0, 8); maxLines = 2
        }
        main.addView(status)
        categoryBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false
            addView(categoryBar)
            visibility = View.VISIBLE
        }
        main.addView(categoryScroll, LinearLayout.LayoutParams(-1, 46).apply { setMargins(0, 0, 0, 8) })
        mainContentScroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            // Keep scrolling posters inside the content viewport. Cards may still
            // scale within the viewport, but must never draw over the fixed header.
            clipChildren = true
            clipToPadding = true
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(16))
            clipChildren = true
            clipToPadding = true
        }
        mainContentScroll.addView(content)
        main.addView(mainContentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun rebuildNavigation() {
        if (!::navBar.isInitialized) return
        // Keep the main navigation labels visible and anchor the menu near the lower-left.
        navBar.gravity = if (sidebarMode == "main") Gravity.BOTTOM or Gravity.START else Gravity.TOP or Gravity.START
        // Expand the sidebar when a category list is open so full category names remain readable.
        if (::sidebar.isInitialized) {
            val showReadableCategories = sidebarMode == "context" &&
                selectedType in listOf("Canlı TV", "Filmler", "Diziler")
            val params = sidebar.layoutParams
            // 78dp icon rail + 138dp category rail + 12dp sidebar padding,
            // with a small safety margin so right rounded corners are never clipped.
            val desiredWidth = dp(if (showReadableCategories) 328 else 190)
            if (params != null && params.width != desiredWidth) {
                params.width = desiredWidth
                sidebar.layoutParams = params
            }
        }
        // Drop focus from old navigation children before detaching them. On Android TV,
        // removeAllViews() can synchronously dispatch focus-loss callbacks while rebuilding.
        if (::navBar.isInitialized) {
            navBar.findFocus()?.clearFocus()
            navBar.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        if (::categoryPanel.isInitialized) {
            categoryPanel.findFocus()?.clearFocus()
            categoryPanel.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        navBar.removeAllViews()
        if (::settingsBar.isInitialized) settingsBar.removeAllViews()
        // Re-enable descendant focus immediately after old children are detached.
        // Leaving FOCUS_BLOCK_DESCENDANTS active prevents the remote from entering the sidebar.
        navBar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        firstCategoryFocusView = null
        if (::categoryPanel.isInitialized) {
            categoryListScroll.visibility = if (sidebarMode == "context" && selectedType in listOf("Canlı TV", "Filmler", "Diziler")) View.VISIBLE else View.GONE
            categoryPanel.removeAllViews()
            categoryPanel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            categoryListScroll.scrollTo(0, 0)
        }
        selectedSidebarFocusView = null

        fun addSidebarButton(label: String, selected: Boolean = false, target: LinearLayout = navBar, action: () -> Unit) {
            val iconRes = when {
                label == "Ara" || label == "⌕" -> R.drawable.nav_search
                label.contains("Ana Sayfa") || label == "⌂" -> R.drawable.nav_home
                label.contains("TV") || label == "▣" -> R.drawable.nav_tv
                label.contains("Filmler") || label == "▧" -> R.drawable.nav_movies
                label.contains("Diziler") || label == "▤" -> R.drawable.nav_series
                label.contains("Favoriler") || label == "♡" -> R.drawable.nav_favorites
                label.contains("İzlediklerim") || label.contains("Geçmiş") || label == "◷" -> R.drawable.nav_history
                label.contains("Ayarlar") || label == "⚙" -> R.drawable.nav_settings
                else -> 0
            }
            val sidebarButton = makeButton(label, selected, action).apply {
                val iconDrawable = if (iconRes != 0) getDrawable(iconRes)?.mutate() else null
                iconDrawable?.setBounds(0, 0, dp(25), dp(25))
                // Sidebar rests flat with no surrounding tile; focus uses a square-corner theme fill.
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                setTextColor(Color.WHITE)
                setOnFocusChangeListener { v, hasFocus ->
                    val button = v as Button
                    button.background = if (hasFocus) GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(if (ovalButtons) 24 else 0).toFloat()
                        setColor(accent)
                    } else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    button.setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
                    iconDrawable?.setTint(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
                    button.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    v.elevation = 0f
                    v.scaleX = 1f
                    v.scaleY = 1f
                }
                textSize = 14f
                text = when {
                    label == "Ara" -> "Arama"
                    label.contains("Ana Sayfa") -> "Ana Sayfa"
                    label.contains("TV") -> "TV"
                    label.contains("Filmler") -> "Filmler"
                    label.contains("Diziler") -> "Diziler"
                    label.contains("Favoriler") -> "Favoriler"
                    label.contains("İzlediklerim") || label.contains("Geçmiş") -> "İzlediklerim"
                    label.contains("Ayarlar") -> "Ayarlar"
                    else -> label
                }
                compoundDrawablePadding = dp(10)
                setCompoundDrawables(iconDrawable, null, null, null)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = text.toString()
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(6), 0)
                minHeight = dp(44)
                id = View.generateViewId()
            }
            target.addView(sidebarButton, LinearLayout.LayoutParams(-1, dp(44)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(0, dp(2), 0, dp(2))
            })
            if (selected) selectedSidebarFocusView = sidebarButton
        }

        fun returnToMainMenu() {
            detailSeries = null
            detailMovie = null
            seriesEpisodeRows = emptyList()
            seriesEpisodesLoading = false
            seriesEpisodesError = ""
            selectedType = "Tümü"
            selectedGroup = "Tüm kategoriler"
            sidebarMode = "main"
            searchQuery = ""
            prefs.edit().putString("last_section", "Tümü").apply()
            render()
        }

        // Ana menü normal ekranlarda kalır; TV/Film/Dizi seçilince sol menü kategori listesine dönüşür.
        if (sidebarMode in listOf("main", "settings", "search")) {
            listOf("Ara", "⌂  Ana Sayfa", "TV  TV", "▧  Filmler", "▤  Diziler", "♡  Favoriler", "◷  İzlediklerim", "⚙  Ayarlar").forEach { tab ->
                val normalizedTab = when {
                    tab == "Ara" -> "Ara"
                    tab.endsWith("Ara") -> "Ara"
                    tab.contains("Ana Sayfa") -> "Ana Sayfa"
                    (tab.contains("Canlı TV") || tab.contains(" TV")) -> "TV"
                    tab.contains("Favoriler") -> "Favoriler"
                    (tab.contains("Geçmiş") || tab.contains("İzlediklerim")) -> "Geçmiş"
                    tab.contains("Ayarlar") -> "Ayarlar"
                    tab.contains("Filmler") -> "Filmler"
                    else -> "Diziler"
                }
                val key = when (normalizedTab) {
                    "TV" -> "Canlı TV"
                    "Ana Sayfa" -> "Tümü"
                    else -> normalizedTab
                }
                if (normalizedTab == "Ayarlar") {
                    navBar.addView(View(this).apply {
                        setBackgroundColor(Color.rgb(76, 87, 105))
                        isFocusable = false
                    }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                        setMargins(dp(10), dp(10), dp(10), dp(10))
                    })
                }
                if (normalizedTab == "Geçmiş") {
                    addSidebarButton(tab, selectedType == "Son İzlenenler") {
                        detailSeries = null
                        detailMovie = null
                        seriesEpisodeRows = emptyList()
                        seriesEpisodesLoading = false
                        seriesEpisodesError = ""
                        selectedType = "Son İzlenenler"
                        focusFirstCategoryOnRender = false
                        focusFirstContentOnRender = true
                        prefs.edit().putString("last_section", "Son İzlenenler").apply()
                        selectedGroup = "Tüm kategoriler"
                        expandedCategory = null
                        sidebarMode = "main"
                        rebuildNavigation()
                        render()
                    }
                } else if (normalizedTab == "Ayarlar") {
                    addSidebarButton(tab, selectedType == "Ayarlar" && settingsPage != "history", settingsBar) {
                        detailSeries = null
                        detailMovie = null
                        settingsPage = "main"
                        selectedType = "Ayarlar"
                        prefs.edit().putString("last_section", "Ayarlar").apply()
                        selectedGroup = "Tüm kategoriler"
                        sidebarMode = "settings"
                        render()
                    }
                } else {
                    addSidebarButton(tab, selectedType == key && normalizedTab != "Ara") {
                        if (normalizedTab == "Ara") {
                            detailSeries = null
                            detailMovie = null
                            sidebarMode = "search"
                            searchInputFocusPending = true
                            searchQuery = ""
                            selectedType = "Tümü"
                            selectedGroup = "Tüm kategoriler"
                            expandedCategory = null
                            render()
                            return@addSidebarButton
                        }
                        detailSeries = null
                        detailMovie = null
                        seriesEpisodeRows = emptyList()
                        seriesEpisodesLoading = false
                        seriesEpisodesError = ""
                        focusFirstCategoryOnRender = key in listOf("Canlı TV", "Filmler", "Diziler")
                        focusFirstContentOnRender = key in listOf("Favoriler", "Son İzlenenler")
                        selectedGroup = "Tüm kategoriler"
                        focusedCategoryName = null
                        expandedCategory = null
                        selectedType = key
                        sidebarMode = if (normalizedTab == "TV" || normalizedTab == "Filmler" || normalizedTab == "Diziler") "context" else "main"
                        prefs.edit().putString("last_section", key).putString("last_group", "Tüm kategoriler").apply()
                        rebuildNavigation()
                        render()
                    }
                }
                if (normalizedTab == "Ana Sayfa") {
                    // Visually separate the primary navigation from the utility items.
                }
            }
            if (sidebarMode == "main") return
        }

        if (sidebarMode == "context") {
            // In TV/Movies/Series, collapse the left rail to icons only.
            // Category filters remain in the main content area on the right.
            val compactTabs = listOf(
                Triple("⌕", "Ara", "Ara"),
                Triple("⌂", "Ana Sayfa", "Tümü"),
                Triple("▣", "TV", "Canlı TV"),
                Triple("▧", "Filmler", "Filmler"),
                Triple("▤", "Diziler", "Diziler"),
                Triple("♡", "Favoriler", "Favoriler"),
                Triple("◷", "İzlediklerim", "Son İzlenenler"),
                Triple("⚙", "Ayarlar", "Ayarlar")
            )
            compactTabs.forEach { (glyph, label, key) ->
                if (key == "Ayarlar") {
                    navBar.addView(View(this).apply { setBackgroundColor(Color.rgb(76, 87, 105)); isFocusable = false },
                        LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
                }
                val isSelected = when (key) {
                    "Ara" -> false
                    "Tümü" -> selectedType == "Tümü"
                    else -> selectedType == key
                }
                addSidebarButton(label, isSelected, if (key == "Ayarlar") settingsBar else navBar) {
                    when (key) {
                        "Ara" -> {
                            detailSeries = null
                            detailMovie = null
                            sidebarMode = "search"
                            searchInputFocusPending = true
                            searchQuery = ""
                            selectedType = "Tümü"
                            selectedGroup = "Tüm kategoriler"
                            expandedCategory = null
                            render()
                        }
                        "Tümü" -> returnToMainMenu()
                        "Son İzlenenler" -> {
                            selectedType = key
                            focusFirstCategoryOnRender = false
                            focusFirstContentOnRender = true
                            sidebarMode = "main"
                            prefs.edit().putString("last_section", key).apply()
                            rebuildNavigation()
                        }
                        "Ayarlar" -> {
                            selectedType = key
                            settingsPage = "main"
                            sidebarMode = "settings"
                            prefs.edit().putString("last_section", key).apply()
                        }
                        else -> {
                            focusFirstCategoryOnRender = key in listOf("Canlı TV", "Filmler", "Diziler")
                            focusFirstContentOnRender = key in listOf("Favoriler", "Son İzlenenler")
                            selectedType = key
                            selectedGroup = "Tüm kategoriler"
                            focusedCategoryName = null
                            expandedCategory = null
                            sidebarMode = if (key in listOf("Canlı TV", "Filmler", "Diziler")) "context" else "main"
                            prefs.edit().putString("last_section", key).putString("last_group", selectedGroup).apply()
                            if (key in listOf("Favoriler", "Son İzlenenler")) rebuildNavigation()
                        }
                    }
                    render()
                }
            }
            if (::categoryPanel.isInitialized && selectedType in listOf("Filmler", "Diziler", "Canlı TV")) {
                // Apply the same hidden-category rules to the sidebar category column
                // as the content grid, so hidden TV/movie/series groups disappear everywhere.
                // Kaynak isimleri kategori sütununda gösterilmez; kaynak seçimi Ayarlar bölümündedir.
                val categorySourceId = selectedCategorySourceId
                val categoryItems = items.filter {
                    it.type == selectedType &&
                    (categorySourceId.isBlank() || it.sourceId == categorySourceId) &&
                    (selectedType != "Canlı TV" || it.url.isNotBlank()) &&
                    !hiddenCategories.contains(it.type + "|" + it.group.ifBlank { "Diğer" })
                }
                val grouped = categoryItems.groupBy { it.group.ifBlank { "Diğer" } }.toList()
                // Keep category navigation separate from the content grid.
                // The first category receives focus, but opens only after explicit selection.
                val selectedName = expandedCategory?.first ?: grouped.firstOrNull()?.first
                categoryPanel.addView(TextView(this).apply {
                    text = t("KATEGORİLER")
                    textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(muted); setPadding(dp(8), 0, 0, dp(10))
                })
                grouped.forEachIndexed { categoryIndex, (name, entries) ->
                    val selected = name == (focusedCategoryName ?: expandedCategory?.first ?: selectedGroup)
                    val button = TextView(this).apply {
                        text = name
                        textSize = 13f
                        maxLines = 2
                        setTextColor(if (selected) Color.WHITE else muted)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(6), dp(8))
                        minHeight = dp(42)
                        isFocusable = true
                        isClickable = true
                        val activeCategory = name == (focusedCategoryName ?: expandedCategory?.first ?: selectedGroup)
                        background = if (activeCategory) roundedDrawable(accent, if (ovalButtons) 14 else 6) else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        setOnFocusChangeListener { v, hasFocus ->
                            if (hasFocus) focusedCategoryName = name
                            val highlighted = if (hasFocus) name else (focusedCategoryName ?: expandedCategory?.first ?: selectedGroup)
                            for (i in 0 until categoryPanel.childCount) {
                                val sibling = categoryPanel.getChildAt(i) as? TextView ?: continue
                                if (grouped.any { it.first == sibling.text.toString() }) {
                                    val active = sibling.text.toString() == highlighted
                                    sibling.background = if (active) roundedDrawable(accent, if (ovalButtons) 14 else 6) else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                                    sibling.setTextColor(if (active) Color.WHITE else muted)
                                }
                            }
                            // D-pad links are assigned after each category view is attached.
                            // Do not inspect categoryPanel children from focus callbacks: render()
                            // may be removing them and trigger focus loss while the list is empty.
                            if (hasFocus && v.isAttachedToWindow && categoryListScroll.isAttachedToWindow) {
                                categoryListScroll.post {
                                    // Ignore stale callbacks from category views detached by a render.
                                    if (!v.isAttachedToWindow || !categoryListScroll.isAttachedToWindow ||
                                        v.parent == null || v.id == View.NO_ID) return@post
                                    val rect = android.graphics.Rect(0, 0, v.width, v.height)
                                    v.getDrawingRect(rect)
                                    runCatching {
                                        categoryListScroll.offsetDescendantRectToMyCoords(v, rect)
                                        categoryListScroll.requestChildRectangleOnScreen(v, rect, true)
                                    }
                                }
                            }
                        }
                        setOnClickListener {
                            selectedGroup = name
                            expandedCategory = name to entries
                            focusFirstContentOnRender = true
                            render()
                        }
                    }
                    // Stable IDs and explicit D-pad links for the category column.
                    if (button.id == View.NO_ID) button.id = View.generateViewId()
                    button.nextFocusLeftId = selectedSidebarFocusView?.id ?: View.NO_ID
                    if (categoryIndex == 0) {
                        firstCategoryFocusView = button
                        selectedSidebarFocusView?.let { navButton ->
                            button.nextFocusLeftId = navButton.id
                            navButton.nextFocusRightId = button.id
                        }
                    } else {
                        val previousCategory = categoryPanel.getChildAt(categoryPanel.childCount - 1)
                        if (previousCategory != null && previousCategory.isFocusable &&
                            previousCategory.id != View.NO_ID && button.id != View.NO_ID) {
                            button.nextFocusUpId = previousCategory.id
                            previousCategory.nextFocusDownId = button.id
                        }
                    }
                    categoryPanel.addView(button, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(2), 0, dp(2))
                    })
                }
                categoryPanel.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                if (selectedType == "Canlı TV") {
                    categoryPanel.addView(TextView(this).apply {
                        text = t("Kanal listesi")
                        textSize = 12f; setTextColor(muted); setPadding(dp(8), dp(10), 0, 0)
                    })
                }
            }
            navBar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            return
        }

        if (sidebarMode == "search") {
            sidebarMenuScroll.post { sidebarMenuScroll.scrollTo(0, 0) }
        }

        // Kategori başlığını ve sol kategori listesini göstermiyoruz.
        // Ana menü seçenekleri ana menü modunda kalır; TV ekranındaki kanal listesi korunur.
        if (selectedType == "Canlı TV") {
            val channels = items.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
            if (channels.isEmpty()) {
                navBar.addView(TextView(this).apply {
                    text = if (items.isEmpty()) "Önce kaynak ekle." else "Kanal bulunamadı."
                    textSize = 12f; setTextColor(muted); setPadding(10, 8, 6, 8)
                })
            } else channels.take(40).forEach { channel ->
                addSidebarButton(channel.name) {
                    selectedType = "Canlı TV"
                    sidebarMode = "context"
                    render()
                    startChannelPreview(channel)
                }
                navBar.getChildAt(navBar.childCount - 1).setOnFocusChangeListener { v, hasFocus ->
                    (v as? Button)?.setTextColor(if (hasFocus) accentBright else Color.WHITE)
                    v.scaleX = if (hasFocus) 1.025f else 1f
                    v.scaleY = if (hasFocus) 1.025f else 1f
                    if (hasFocus) startChannelPreview(channel)
                }
            }
        }
        navBar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    private fun showConnectionDialog(editSource: SavedSource? = null) {
        val corner = if (ovalButtons) 22 else 8
        val outline = Color.rgb(48, 57, 73)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        form.addView(TextView(this).apply {
            text = t("Kaynaklar")
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(2), 0, 0, dp(4))
        })
        form.addView(TextView(this).apply {
            text = t("Yasal kullanım hakkına sahip olduğun yayın adreslerini ekle yönet.")
            textSize = 13f
            setTextColor(muted)
            setPadding(dp(2), 0, 0, dp(9))
        })

        sourceName = edit(t("Kaynak adı (ör. Ev Xtream)"), false).apply { setText(editSource?.name.orEmpty()) }
        playlistUrl = edit(t("M3U / M3U8 oynatma listesi URL'si"), false).apply {
            setText(if (editSource?.mode == "m3u") editSource.address else "")
        }
        server = edit(t("Sunucu adresi (https://...)"), false).apply {
            setText(if (editSource?.mode == "xtream") editSource.address else "")
        }
        username = edit(t("Kullanıcı adı"), false).apply { setText(editSource?.username.orEmpty()) }
        password = edit(t("Şifre"), true).apply { setText(editSource?.password.orEmpty()) }

        fun inputFrame(input: EditText): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedDrawable(panel, corner, outline)
            setPadding(dp(7), 0, dp(7), 0)
            input.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            input.setPadding(dp(5), 0, dp(5), 0)
            input.textSize = 13f
            addView(input, LinearLayout.LayoutParams(-1, dp(43)))
            input.setOnFocusChangeListener { _, focused ->
                background = roundedDrawable(panel, corner, if (focused) accent else outline)
            }
        }
        // MAC Portal uses a manually entered device MAC address; it is never read from the device.
        val macName = edit(t("Kaynak adı (ör. Ev Portal)"), false).apply {
            setText(if (editSource?.mode == "mac") editSource.name else "")
        }
        val macPortal = edit(t("Portal URL (https://...)"), false).apply {
            setText(if (editSource?.mode == "mac") editSource.address else "")
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        }
        val macAddress = edit(t("MAC adresi (00:1A:79:XX:XX:XX)"), false).apply {
            setText(if (editSource?.mode == "mac") editSource.username else "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val macFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(inputFrame(macName), LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(5) })
            addView(inputFrame(macPortal), LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(5) })
            addView(inputFrame(macAddress), LinearLayout.LayoutParams(-1, dp(43)))
        }

        val playlistFrame = inputFrame(playlistUrl)
        val serverFrame = inputFrame(server)
        val usernameFrame = inputFrame(username)
        val passwordFrame = inputFrame(password)

        sourceMode = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            visibility = View.GONE
        }
        val m3u = RadioButton(this).apply { id = View.generateViewId(); text = "M3U / M3U8" }
        val xtream = RadioButton(this).apply { id = View.generateViewId(); text = "Xtream Codes" }
        sourceMode.addView(m3u)
        sourceMode.addView(xtream)
        sourceMode.check(if (editSource?.mode == "xtream") xtream.id else m3u.id)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancelButton = makeButton(t("İptal"), false) { render() }.apply {
            minWidth = 0
            textSize = 13f
        }
        val connectButton = makeButton(t(if (editSource == null) "Bağlan" else "Kaydet"), true) {
            val useXtream = sourceMode.checkedRadioButtonId == xtream.id
            if (useXtream) {
                val address = server.text.toString().trim()
                if (!(address.startsWith("http://") || address.startsWith("https://")) ||
                    username.text.isNullOrBlank() || password.text.isNullOrBlank()) {
                    Toast.makeText(this, t("Sunucu, kullanıcı adı ve şifreyi doldur."), Toast.LENGTH_LONG).show()
                    return@makeButton
                }
                val label = sourceName.text.toString().trim().ifBlank { "Xtream ${savedSources.size + 1}" }
                val src = SavedSource(editSource?.id ?: java.util.UUID.randomUUID().toString(), label, "xtream",
                    address.trimEnd('/'), username.text.toString().trim(), password.text.toString())
                saveSource(src); hideKeyboard(); render(); loadXtream(src, editSource != null)
            } else {
                val address = playlistUrl.text.toString().trim()
                if (!address.startsWith("http://") && !address.startsWith("https://")) {
                    Toast.makeText(this, t("Geçerli bir M3U/M3U8 URL'si gir."), Toast.LENGTH_LONG).show()
                    return@makeButton
                }
                val label = sourceName.text.toString().trim().ifBlank { "M3U ${savedSources.size + 1}" }
                val src = SavedSource(editSource?.id ?: java.util.UUID.randomUUID().toString(), label, "m3u", address)
                saveSource(src); hideKeyboard(); render(); loadM3u(src, editSource != null)
            }
        }.apply { minWidth = 0; textSize = 13f }

        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        fun makeSourceCard(title: String, modeId: Int, selected: Boolean, fields: LinearLayout, mac: Boolean = false): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = roundedDrawable(panel, corner, outline)
                isFocusable = false
                isFocusableInTouchMode = false
                clipChildren = false
                clipToPadding = false
            }
            card.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(if (selected) accentBright else Color.WHITE)
            }, LinearLayout.LayoutParams(-1, dp(42)))
            card.addView(fields, LinearLayout.LayoutParams(-1, 0, 1f))
            val footer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val connect = makeButton(t(if (editSource == null) "Bağlan" else "Kaydet"), true) {
                if (mac) {
                    val portal = macPortal.text.toString().trim().trimEnd('/')
                    val macValue = macAddress.text.toString().trim().uppercase(java.util.Locale.ROOT)
                    val validMac = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(macValue)
                    if (!(portal.startsWith("http://") || portal.startsWith("https://")) || !validMac) {
                        Toast.makeText(this@MainActivity, t("Portal URL ve geçerli MAC adresini girin (00:1A:79:XX:XX:XX)."), Toast.LENGTH_LONG).show()
                        return@makeButton
                    }
                    val label = macName.text.toString().trim().ifBlank { "MAC Portal ${savedSources.size + 1}" }
                    val src = SavedSource(editSource?.id ?: java.util.UUID.randomUUID().toString(), label, "mac", portal, macValue)
                    saveSource(src)
                    hideKeyboard()
                    Toast.makeText(this@MainActivity, t("MAC Portal bilgileri kaydedildi. Yayın bağlantısı henüz etkin değil."), Toast.LENGTH_LONG).show()
                    render()
                } else {
                    sourceMode.check(modeId)
                    connectButton.performClick()
                }
            }.apply { minWidth = 0; textSize = 12f }
            footer.addView(connect, LinearLayout.LayoutParams(-1, dp(46)))
            card.addView(footer, LinearLayout.LayoutParams(-1, dp(50)))
            return card
        }

        val m3uFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            addView(playlistFrame, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val xtreamFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            addView(serverFrame, LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(5) })
            addView(usernameFrame, LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(5) })
            addView(passwordFrame, LinearLayout.LayoutParams(-1, dp(43)))
        }
        // Each card has its own source-name field and starts at the same top alignment.
        val m3uName = edit(t("Kaynak adı (ör. Ev TV)"), false).apply { setText(if (editSource?.mode == "m3u") editSource.name else "") }
        val xtreamName = edit(t("Kaynak adı (ör. Ev Xtream)"), false).apply { setText(if (editSource?.mode == "xtream") editSource.name else "") }
        val m3uNameFrame = inputFrame(m3uName)
        val xtreamNameFrame = inputFrame(xtreamName)
        m3uFields.addView(m3uNameFrame, 0, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(7) })
        xtreamFields.addView(xtreamNameFrame, 0, LinearLayout.LayoutParams(-1, dp(43)).apply { bottomMargin = dp(5) })
        m3uFields.gravity = Gravity.TOP
        xtreamFields.gravity = Gravity.TOP

        val cardHeight = dp(360)
        val m3uCard = makeSourceCard("▤  M3U / M3U8", m3u.id, editSource?.mode != "xtream", m3uFields)
        val xtreamCard = makeSourceCard("▣  Xtream Codes", xtream.id, editSource?.mode == "xtream", xtreamFields)
        val macCard = makeSourceCard("▱  MAC Portal", -1, false, macFields, true)
        listOf(m3uCard, macCard, xtreamCard).forEach { card ->
            columns.addView(card, LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        form.addView(columns, LinearLayout.LayoutParams(-1, cardHeight + dp(6)))
        sourceMode.setOnCheckedChangeListener { _, _ ->
            m3uCard.background = roundedDrawable(panel, corner, outline)
            xtreamCard.background = roundedDrawable(panel, corner, outline)
        }
        form.addView(sourceMode)
        content.addView(form, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(4), dp(5), dp(4), dp(8))
        })
        form.post { if (editSource?.mode == "xtream") xtreamName.requestFocus() else m3uName.requestFocus() }
    }

    private fun applyThemeSurfaces() {
        val base = bg
        // Shared cards, dialogs and panels use a darker shade tinted by the active theme.
        fun channelMix(baseChannel: Int, accentChannel: Int): Int =
            ((baseChannel * 0.62f) + (accentChannel * 0.08f)).toInt().coerceIn(0, 255)
        panel = Color.rgb(
            channelMix(Color.red(base), Color.red(accent)),
            channelMix(Color.green(base), Color.green(accent)),
            channelMix(Color.blue(base), Color.blue(accent))
        )
        val blend = Color.rgb(
            ((Color.red(base) * 0.72f) + (Color.red(accent) * 0.28f)).toInt().coerceIn(0, 255),
            ((Color.green(base) * 0.72f) + (Color.green(accent) * 0.28f)).toInt().coerceIn(0, 255),
            ((Color.blue(base) * 0.72f) + (Color.blue(accent) * 0.28f)).toInt().coerceIn(0, 255)
        )
        fun shade(color: Int, factor: Float): Int = Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
        val rightBase = shade(blend, 0.76f)
        val surface = if (gradientBackground) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(base, blend, rightBase))
        } else {
            GradientDrawable().apply { setColor(base) }
        }
        rootLayout.background = surface
        // In flat mode, give the right content area a slightly lighter shade
        // of the selected theme than the left navigation surface.
        val rightPanel = Color.rgb(
            ((Color.red(base) * 0.82f) + (Color.red(accent) * 0.18f)).toInt().coerceIn(0, 255),
            ((Color.green(base) * 0.82f) + (Color.green(accent) * 0.18f)).toInt().coerceIn(0, 255),
            ((Color.blue(base) * 0.82f) + (Color.blue(accent) * 0.18f)).toInt().coerceIn(0, 255)
        )
        mainContentScroll.setBackgroundColor(if (gradientBackground) Color.TRANSPARENT else rightPanel)
        content.setBackgroundColor(Color.TRANSPARENT)
        sidebar.setBackgroundColor(if (transparentMenu) Color.TRANSPARENT else panel)
        window.statusBarColor = bg
        window.navigationBarColor = bg
    }

    private fun buttonBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(if (ovalButtons) 24 else 0).toFloat()
        }

    private fun roundedDrawable(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(if (ovalButtons) 24 else radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke((2 * resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
            }
        }

    private fun edit(hintText: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintText; textSize = 14f; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(muted)
        setPadding(12, 0, 12, 0)
        background = roundedDrawable(panel, 10, Color.rgb(48, 57, 73))
        if (android.os.Build.VERSION.SDK_INT >= 24) imeHintLocales = android.os.LocaleList.getDefault()
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable?.setTint(accent)
        if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun fieldParams() = LinearLayout.LayoutParams(-1, 48).apply { setMargins(0, 3, 0, 3) }
    private fun makeFavoriteButton(item: MediaItem): Button {
        fun updateFavoriteStyle(button: Button, focused: Boolean) {
            val selected = favorites.contains(item.favoriteKey)
            val yellow = Color.rgb(255, 205, 55)
            button.text = "Favori"
            button.setTextColor(if (selected) Color.rgb(25, 25, 25) else if (focused) Color.rgb(8, 13, 22) else Color.WHITE)
            button.background = when {
                selected -> buttonBackground(yellow)
                focused -> buttonBackground(accent)
                else -> buttonBackground(panel)
            }
        }
        return makeButton("Favori", false) {
            if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey)
            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
            render()
        }.apply {
            updateFavoriteStyle(this, false)
            setOnFocusChangeListener { view, focused ->
                updateFavoriteStyle(view as Button, focused)
                view.scaleX = 1f
                view.scaleY = 1f
            }
        }
    }

    private fun makeButton(label: String, primary: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = t(label); textSize = 12f; isAllCaps = false; isFocusable = true; isFocusableInTouchMode = true
        setPadding(16, 0, 16, 0)
        setTextColor(Color.WHITE)
        background = buttonBackground(panel)
        stateListAnimator = null
        setOnFocusChangeListener { v, hasFocus ->
            v.background = buttonBackground(if (hasFocus) accent else panel)
            (v as Button).setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
            v.elevation = 0f
            v.scaleX = 1f; v.scaleY = 1f
        }
        setOnClickListener { action() }
    }
    private fun hideKeyboard() { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(playlistUrl.windowToken, 0) }


    private fun saveSource(source: SavedSource) {
        savedSources.removeAll { it.id == source.id }; savedSources.add(source)
        prefs.edit().putString("last_source_id", source.id).apply()
        persistSources()
    }
    private fun persistSources() {
        val arr = JSONArray()
        savedSources.forEach { s -> arr.put(JSONObject().put("id", s.id).put("name", s.name).put("mode", s.mode).put("address", s.address).put("username", s.username).put("password", s.password)) }
        getSharedPreferences("androtv", MODE_PRIVATE).edit().putString("sources", arr.toString()).apply()
    }
    private fun loadSavedSources() {
        try {
            val arr = JSONArray(getSharedPreferences("androtv", MODE_PRIVATE).getString("sources", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                savedSources.add(SavedSource(o.optString("id"), o.optString("name"), o.optString("mode"), o.optString("address"), o.optString("username"), o.optString("password")))
            }
        } catch (_: Exception) { }

        // Keep a built-in mock source available for the demo. If this is the
        // first version that registers it, make it the initial source once;
        // existing user sources remain saved and can still be selected later.
        val demoSource = SavedSource(
            "kaisertv-demo-222",
            "KaiserTV+ Demo",
            "xtream",
            "https://xtream-codes-mock-server.vercel.app",
            "test_user",
            "test_pass"
        )
        val previousLastSourceId = prefs.getString("last_source_id", null)
        val demoWasAlreadySaved = savedSources.any { it.id == demoSource.id }
        if (!demoWasAlreadySaved) {
            savedSources.add(demoSource)
            persistSources()
            // Use the demo only on a fresh installation with no previously selected source.
            if (previousLastSourceId.isNullOrBlank()) {
                prefs.edit().putString("last_source_id", demoSource.id).apply()
            }
        }

        if (savedSources.isNotEmpty()) {
            // Keep the user's last selected source across Activity recreation (including language changes).
            val lastId = prefs.getString("last_source_id", null)
            val last = savedSources.firstOrNull { it.id == lastId } ?: savedSources.firstOrNull()
            if (last != null && last.id != lastId) {
                prefs.edit().putString("last_source_id", last.id).apply()
            }

            // Validate the cache against the selected source, not against items
            // belonging to any other saved source.
            val sourceToRestore = last ?: savedSources.lastOrNull()
            val restoredSourceItems = items.filter { it.sourceId == sourceToRestore?.id }
            val cacheHasLiveChannels = restoredSourceItems.any { it.type == "Canlı TV" && it.url.isNotBlank() }
            val cacheHasMovies = restoredSourceItems.any { it.type == "Filmler" }
            val cacheHasSeries = restoredSourceItems.any { it.type == "Diziler" }
            // A cache missing any major Xtream section must not be treated as complete.
            // In particular, otherwise TV can open into an empty "Tüm Kanallar" screen.
            val cacheNeedsRepair = sourceToRestore?.mode == "xtream" &&
                (!cacheHasLiveChannels || !cacheHasMovies || !cacheHasSeries)
            if (items.isNotEmpty() && !cacheNeedsRepair) {
                val availableGroups = items.map { it.group.ifBlank { "Diğer" } }.toSet()
                if (selectedGroup != "Tüm kategoriler" && selectedGroup !in availableGroups) {
                    selectedGroup = "Tüm kategoriler"
                    prefs.edit().putString("last_group", selectedGroup).apply()
                }
                isLoadingSource = false
                status.visibility = View.VISIBLE
                status.text = "${items.size}${t("KAYITLI_ICERIK_HAZIR")}"
                sidebarMode = if (selectedType in listOf("Canlı TV", "Filmler", "Diziler")) "context" else "main"
                render()
            } else if (sourceToRestore != null && !isLoadingSource) {
                sidebarMode = if (selectedType in listOf("Canlı TV", "Filmler", "Diziler")) "context" else "main"
                status.visibility = View.VISIBLE
                status.text = if (items.isEmpty()) "${sourceToRestore.name}: kayıtlı kaynak yükleniyor…" else "${sourceToRestore.name}: film ve dizi kayıtları tamamlanıyor…"
                render()
                if (sourceToRestore.mode == "xtream") loadXtream(sourceToRestore, true) else loadM3u(sourceToRestore, true)
            } else {
                render()
            }
        }
    }
    private fun showSourcesDialog() {
        if (savedSources.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Kaynaklar").setMessage("Henüz kayıtlı kaynak yok.")
                .setPositiveButton("Kaynak ekle") { _, _ -> showConnectionDialog() }.setNegativeButton("Kapat", null).show(); return
        }
        val labels = savedSources.map { "${it.name} • ${when (it.mode) { "xtream" -> "Xtream"; "mac" -> "MAC Portal"; else -> "M3U" }}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Kayıtlı kaynaklar").setItems(labels) { _, which ->
            val s = savedSources[which]
            AlertDialog.Builder(this).setTitle(s.name).setItems(arrayOf("Yenile", "Sil")) { _, action ->
                if (action == 0) {
                    when (s.mode) {
                        "xtream" -> loadXtream(s, true)
                        "mac" -> Toast.makeText(this, "MAC Portal kaynak bilgileri kayıtlı; yayın bağlantısı henüz etkin değil.", Toast.LENGTH_LONG).show()
                        else -> loadM3u(s, true)
                    }
                }
                else { savedSources.removeAll { it.id == s.id }; persistSources(); items.removeAll { it.sourceId == s.id }; render() }
            }.setNegativeButton(t("İptal"), null).show()
        }.setPositiveButton("Kaynak ekle") { _, _ -> showConnectionDialog() }.setNegativeButton("Kapat", null).show()
    }

    private fun friendlyLoadError(error: Exception): String {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        val message = causes.joinToString(" ") { it.message.orEmpty() }
        return when {
            causes.any { it is java.net.SocketTimeoutException } -> t("Bağlantı zaman aşımına uğradı. Yeniden deneyin.")
            causes.any { it is java.net.UnknownHostException || it is java.net.ConnectException } -> t("Sunucuya ulaşılamadı. İnternet bağlantınızı kontrol edin.")
            causes.any { it is javax.net.ssl.SSLException } -> t("Güvenli bağlantı kurulamadı.")
            causes.any { it is java.net.SocketException } && message.contains("reset", true) ->
                t("Sunucu bağlantıyı kapattı. Yeniden deneyin.")
            Regex("HTTP\\s+(\\d{3})", RegexOption.IGNORE_CASE).find(message)?.let { it.groupValues[1] } != null ->
                "${t("Sunucu isteği reddetti")} (HTTP ${Regex("HTTP\\s+(\\d{3})", RegexOption.IGNORE_CASE).find(message)!!.groupValues[1]})"
            else -> t("Kaynak yüklenemedi. İnternet bağlantınızı ve kaynak bilgilerini kontrol edin.")
        }
    }

    private fun loadM3u(source: SavedSource, append: Boolean) {
        prefs.edit().putString("last_source_id", source.id).apply()
        isLoadingSource = true
        loadProgress = 0
        status.text = "${source.name}: ${t("TV, filmler ve diziler yükleniyor…")}"
        render()
        thread {
            try {
                val parsed = parseM3u(request(source.address)).map { it.copy(sourceId = source.id) }
                runOnUiThread {
                    items.removeAll { it.sourceId == source.id }
                    items.addAll(parsed)
                    saveCachedItems()
                    selectedGroup = "Tüm kategoriler"
                    status.text = "${source.name}: ${parsed.size} içerik yüklendi. ${items.size} toplam içerik."
                    isLoadingSource = false
                    hasLoadedSource = true
                    sidebarMode = "main"
                    render()
                }
            } catch (e: Exception) { runOnUiThread {
                loadProgress = 0
                isLoadingSource = false
                status.text = "${source.name}: ${friendlyLoadError(e)}"
                render()
            } }
        }
    }

    private fun loadXtream(source: SavedSource, append: Boolean) {
        val base = source.address.trimEnd('/')
        val user = source.username
        val pass = source.password
        if (!(base.startsWith("http://") || base.startsWith("https://")) || user.isBlank() || pass.isBlank()) {
            status.text = "Sunucu, kullanıcı adı ve şifre alanlarını doldur."
            return
        }
        prefs.edit().putString("last_source_id", source.id).apply()
        isLoadingSource = true
        loadProgress = 0
        status.text = "${source.name}: ${t("TV, filmler ve diziler yükleniyor…")}"
        render()
        thread(name = "kaiser-xtream-load") {
            try {
                val u = URLEncoder.encode(user, "UTF-8")
                val p = URLEncoder.encode(pass, "UTF-8")
                val api = "$base/player_api.php?username=$u&password=$p"
                val root = JSONObject(request(api))
                val userInfo = root.optJSONObject("user_info")
                if (userInfo != null && userInfo.optString("auth", "1") == "0") {
                    throw Exception("Giriş bilgileri kabul edilmedi.")
                }

                val loaded = mutableListOf<MediaItem>()
                val failedParts = mutableListOf<String>()

                // Category endpoints are useful for readable names, but a category
                // endpoint failure must not prevent the actual media lists loading.
                fun categoryMap(action: String, label: String): Map<String, String> {
                    return try {
                        val arr = JSONArray(request("$api&action=$action"))
                        buildMap {
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val id = item.optString("category_id")
                                val name = item.optString("category_name")
                                if (id.isNotBlank() && name.isNotBlank()) put(id, name)
                            }
                        }
                    } catch (e: Exception) {
                        failedParts.add("$label kategorileri")
                        android.util.Log.w("KaiserTV", "$label category list failed", e)
                        emptyMap()
                    }
                }

                runOnUiThread {
                    loadProgress = 10
                    status.text = "${source.name}: kategori bilgileri alınıyor…"
                }
                val liveCategories = categoryMap("get_live_categories", "TV")
                val vodCategories = categoryMap("get_vod_categories", "Film")
                val seriesCategories = categoryMap("get_series_categories", "Dizi")
                val actions = listOf(
                    Triple("get_live_streams", "Canlı TV", liveCategories),
                    Triple("get_vod_streams", "Filmler", vodCategories),
                    Triple("get_series", "Diziler", seriesCategories)
                )

                actions.forEachIndexed { actionIndex, (action, type, categories) ->
                    runOnUiThread {
                        loadProgress = 40 + actionIndex * 18
                        status.text = "${source.name}: $type yükleniyor…"
                    }
                    try {
                        val response = request("$api&action=$action")
                        val arr = JSONArray(response)
                        val count = arr.length()
                        for (i in 0 until count) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString(
                                if (action == "get_series") "series_id" else "stream_id",
                                ""
                            ).trim()
                            if (id.isBlank()) continue

                            val name = o.optString("name", "İsimsiz içerik").ifBlank { "İsimsiz içerik" }
                            val categoryId = o.optString("category_id")
                            val group = o.optString("category_name").takeIf { it.isNotBlank() }
                                ?: categories[categoryId]
                                ?: "Diğer"
                            val ext = o.optString(
                                "container_extension",
                                if (type == "Canlı TV") "ts" else "mp4"
                            ).ifBlank { if (type == "Canlı TV") "ts" else "mp4" }
                            val plot = o.optString("plot", o.optString("description", ""))
                            val actors = o.optString("cast", o.optString("actors", ""))

                            // Xtream providers use different artwork keys by content type.
                            // Prefer stream_icon for live/VOD and cover for series, then
                            // fall back through the common provider-specific alternatives.
                            val artworkKeys = when (type) {
                                "Canlı TV" -> listOf("stream_icon", "cover", "cover_big", "movie_image", "logo")
                                "Filmler" -> listOf("stream_icon", "movie_image", "cover_big", "cover", "poster")
                                else -> listOf("cover", "cover_big", "stream_icon", "movie_image", "poster")
                            }
                            val rawArtwork = artworkKeys
                                .asSequence()
                                .map { key -> o.optString(key, "").trim() }
                                .firstOrNull { it.isNotBlank() && it != "null" }
                                .orEmpty()
                            val artwork = when {
                                rawArtwork.startsWith("//") -> "https:$rawArtwork"
                                rawArtwork.startsWith("/") -> "$base$rawArtwork"
                                else -> rawArtwork
                            }

                            val streamUrl = when (type) {
                                "Canlı TV" -> "$base/live/$u/$p/$id.$ext"
                                "Filmler" -> "$base/movie/$u/$p/$id.$ext"
                                else -> ""
                            }
                            loaded.add(
                                MediaItem(
                                    id, name, streamUrl, group, type, artwork,
                                    ext, if (type == "Diziler") id else "",
                                    source.id, plot, actors,
                                    o.optString("rating", o.optString("vote_average", "")),
                                    o.optString("releaseDate", o.optString("year", "")),
                                    o.optString("genre", "")
                                )
                            )
                            if (i % 250 == 0) {
                                val within = if (count == 0) 0 else (i * 16 / count)
                                val progress = (40 + actionIndex * 18 + within).coerceIn(1, 98)
                                runOnUiThread {
                                    loadProgress = progress
                                    status.text = "${source.name}: $type yükleniyor… %$progress"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        failedParts.add(type)
                        android.util.Log.e("KaiserTV", "$type list failed", e)
                    }
                }

                if (loaded.isEmpty()) {
                    throw Exception(
                        if (failedParts.isEmpty()) "Kaynak boş içerik döndürdü."
                        else "İçerik listeleri alınamadı: ${failedParts.distinct().joinToString()}"
                    )
                }

                runOnUiThread {
                    items.removeAll { it.sourceId == source.id }
                    items.addAll(loaded)
                    saveCachedItems()
                    selectedType = "Tümü"
                    selectedGroup = "Tüm kategoriler"
                    expandedCategory = null
                    sidebarMode = "main"
                    xtreamBase = base
                    xtreamUser = user
                    xtreamPass = pass
                    isLoadingSource = false
                    hasLoadedSource = true
                    loadProgress = 100
                    status.visibility = View.VISIBLE
                    status.text = if (failedParts.isEmpty()) {
                        "${source.name}: ${loaded.size} içerik yüklendi."
                    } else {
                        "${source.name}: ${loaded.size} içerik yüklendi. Eksik yanıt: ${failedParts.distinct().joinToString()}."
                    }
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadProgress = 0
                    isLoadingSource = false
                    status.visibility = View.VISIBLE
                    status.text = "${source.name}: ${friendlyLoadError(e)}"
                    render()
                }
            }
        }
    }

    private fun request(address: String): String {
        var lastError: Exception? = null
        // IPTV providers often respond slowly to large VOD/series lists. Retry transient
        // network/server failures instead of silently leaving a partially loaded library.
        for (attempt in 0..2) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "KaiserTV+/2.1")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    useCaches = false
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    if (code == 429 || code >= 500) throw Exception("HTTP $code (yeniden denenecek)")
                    throw Exception("HTTP $code")
                }
                if (body.isBlank()) throw Exception("Sunucu boş yanıt döndürdü")
                return body
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) {
                    try { Thread.sleep(700L * (attempt + 1)) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw Exception("Yükleme iptal edildi")
                    }
                }
            } finally {
                conn?.disconnect()
            }
        }
        throw Exception("Sunucuya ulaşılamadı: ${lastError?.message ?: "bağlantı hatası"}")
    }

    private fun parseM3u(text: String): List<MediaItem> {
        val out = mutableListOf<MediaItem>(); var name = "İçerik"; var group = "Diğer"; var icon = ""
        text.lineSequence().map { it.trim() }.forEach { line ->
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfterLast(',', "İçerik").trim().ifBlank { "İçerik" }
                group = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1) ?: "Diğer"
                icon = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)?.groupValues?.getOrNull(1) ?: ""
            } else if ((line.startsWith("http://") || line.startsWith("https://")) && line.isNotBlank()) {
                val type = classify(name, group)
                out.add(MediaItem(line, name, line, group, type, icon)); name = "İçerik"; group = "Diğer"; icon = ""
            }
        }
        return out
    }
    private fun classify(name: String, group: String): String {
        val s = "$name $group".lowercase()
        return when {
            listOf("series", "dizi", "tv show", "season", "sezon", "episode", "bölüm").any { it in s } -> "Diziler"
            listOf("movie", "film", "cinema", "vod", "4k film").any { it in s } -> "Filmler"
            else -> "Canlı TV"
        }
    }

    private fun cachedItemsFile() = java.io.File(filesDir, "kaisertv_media_cache.json")

    private fun saveCachedItems() {
        // Keep every loaded item in the disk cache; write entries incrementally
        // so large IPTV libraries do not require one giant JSON string in memory.
        val snapshot = try {
            items.toList()
        } catch (_: Exception) { return }
        thread(name = "kaisertv-cache-writer") {
            val temp = java.io.File(filesDir, "kaisertv_media_cache.tmp")
            try {
                temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write("[")
                    snapshot.forEachIndexed { index, x ->
                        if (index > 0) writer.write(",")
                        val obj = JSONObject()
                            .put("id", x.id).put("name", x.name).put("url", x.url).put("group", x.group)
                            .put("type", x.type).put("icon", x.icon).put("extension", x.extension)
                            .put("seriesId", x.seriesId).put("sourceId", x.sourceId).put("plot", x.plot)
                            .put("actors", x.actors).put("rating", x.rating).put("releaseDate", x.releaseDate)
                            .put("genre", x.genre)
                        writer.write(obj.toString())
                        if (index % 250 == 0) writer.flush()
                    }
                    writer.write("]")
                }
                val target = cachedItemsFile()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            } catch (e: Exception) {
                android.util.Log.e("KaiserTV", "Media cache write failed", e)
                try { temp.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun loadCachedItems() {
        try {
            val file = cachedItemsFile()
            // Parsing a large JSON string plus JSONArray duplicates memory usage and
            // can crash low-memory Mi TV Stick devices on the next launch.
            // A valid 1000-item cache can exceed 2 MB due to URLs and metadata.
            // Do not delete it on startup just because it crosses the old tiny limit.
            if (file.exists() && file.length() > 20L * 1024L * 1024L) {
                android.util.Log.w("KaiserTV", "Media cache exceeds safe size; skipping disk cache.")
                return
            }
            val raw = if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8) else "[]"
            val arr = JSONArray(raw)
            val restored = ArrayList<MediaItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                restored.add(MediaItem(o.optString("id"),o.optString("name"),o.optString("url"),o.optString("group"),o.optString("type"),o.optString("icon"),o.optString("extension"),o.optString("seriesId"),o.optString("sourceId"),o.optString("plot"),o.optString("actors"),o.optString("rating"),o.optString("releaseDate"),o.optString("genre")))
            }
            items.clear()
            items.addAll(restored)
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleSafeBack()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Android TV remotes may deliver Back as BACK, ESCAPE, or the B button.
        // Handle on key-up so one press cannot trigger two navigation steps.
        if (event.action == android.view.KeyEvent.ACTION_UP &&
            (event.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
             event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE ||
             event.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_B)) {
            handleSafeBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleSafeBack() {
        // Handle Android system/remote back in one place and keep the activity alive.
        try {
            if (selectedType == "Ayarlar" && sidebarMode == "settings" && settingsPage != "main") {
                settingsPage = "main"
                prefs.edit().putString("last_settings_page", "main").apply()
                rebuildNavigation()
                render()
                return
            }

            if (fullscreenPoster != null) {
                closePosterFullscreen()
                return
            }

            if (detailSeries != null || detailMovie != null) {
                detailSeries = null
                detailMovie = null
                seriesEpisodeRows = emptyList()
                seriesEpisodesLoading = false
                seriesEpisodesError = ""
                render()
                return
            }

            if (expandedCategory != null) {
                expandedCategory = null
                render()
                return
            }

            if (sidebarMode != "main" || selectedGroup != "Tüm kategoriler" || selectedType != "Tümü") {
                detailSeries = null
                detailMovie = null
                seriesEpisodeRows = emptyList()
                seriesEpisodesLoading = false
                seriesEpisodesError = ""
                sidebarMode = "main"
                searchQuery = ""
                selectedType = "Tümü"
                selectedGroup = "Tüm kategoriler"
                prefs.edit().putString("last_section", "Tümü").apply()
                render()
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("KaiserTV", "Back navigation failed", e)
            // Keep the current activity alive even if rebuilding a section fails.
            try {
                detailSeries = null
                detailMovie = null
                sidebarMode = "main"
                searchQuery = ""
                selectedType = "Tümü"
                selectedGroup = "Tüm kategoriler"
                prefs.edit().putString("last_section", "Tümü").apply()
                render()
            } catch (ignored: Exception) {
                android.util.Log.e("KaiserTV", "Fallback navigation failed", ignored)
            }
            return
        }

        val exitPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = roundedDrawable(Color.rgb(13, 20, 33), 18, accent)
        }
        val brand = TextView(this).apply {
            val logo = android.text.SpannableString("▶ KAISER TV+")
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 2, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 9, 11, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 196, 0)), 11, 12, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = logo
            textSize = 28f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        brand.textSize = 21f
        exitPanel.addView(brand, LinearLayout.LayoutParams(-1, dp(42)))
        exitPanel.addView(TextView(this).apply {
            text = t("Uygulamadan çıkmak istiyor musunuz?")
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(12))
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val exitActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val exitDialog = AlertDialog.Builder(this).setView(exitPanel).create()
        fun exitActionButton(label: String, fillColor: Int, textColor: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = t(label)
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(textColor)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = roundedDrawable(fillColor, 12)
                setOnFocusChangeListener { v, focused ->
                    v.background = roundedDrawable(if (focused) accent else fillColor, 12)
                    (v as TextView).setTextColor(if (focused) Color.rgb(8, 13, 22) else textColor)
                }
                setOnClickListener { onClick() }
            }
        val exitButton = exitActionButton("Çıkış Yap", Color.rgb(27, 33, 45), Color.WHITE) {
            exitDialog.dismiss()
            finish()
        }
        // Both actions stay neutral when unfocused; only the focused action is accented.
        val cancelButton = exitActionButton("Vazgeç", Color.rgb(27, 33, 45), Color.WHITE) {
            exitDialog.dismiss()
        }
        exitActions.addView(exitButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            setMargins(0, 0, dp(10), 0)
        })
        exitActions.addView(cancelButton, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        exitPanel.addView(exitActions, LinearLayout.LayoutParams(-1, dp(54)))
        exitDialog.setOnShowListener {
            exitDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            exitDialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            exitDialog.window?.setDimAmount(0.78f)
            exitDialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.56f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            cancelButton.post { cancelButton.requestFocus() }
        }
        exitDialog.show()
    }

    private fun showItemDetails(item: MediaItem) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(18, 18, 18, 18); background = roundedDrawable(panel, 18, Color.rgb(45, 54, 71)) }
        val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = roundedDrawable(Color.rgb(31, 37, 50), 12) }
        body.addView(poster, LinearLayout.LayoutParams(150, 220).apply { setMargins(0, 0, 18, 0) })
        if (item.icon.startsWith("http://") || item.icon.startsWith("https://")) loadPoster(item.icon, poster)
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply { text = item.name; textSize = 22f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE) })
        info.addView(TextView(this).apply { text = item.type + "  •  " + item.group.ifBlank { "Diğer" }; textSize = 13f; setTextColor(accent); setPadding(0, 8, 0, 8) })
        val metaScroll = ScrollView(this)
        val meta = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(label: String, value: String, fallback: String = ""): TextView {
            meta.addView(TextView(this).apply { text = label; textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(accent); setPadding(0, 4, 0, 3) })
            return TextView(this).apply { text = value.ifBlank { fallback }; textSize = 14f; setTextColor(muted); setPadding(0, 0, 0, 8) }.also { meta.addView(it) }
        }
        val ratingView = field("PUAN", item.rating, "Bilgi yükleniyor…")
        val dateView = field("YAYIN TARİHİ", item.releaseDate, "")
        val genreView = field("TÜR", item.genre, "")
        val plotView = field("KONU", item.plot, if (item.sourceId.isNotBlank()) "Bilgiler yükleniyor…" else "Bu kaynak konu bilgisi sağlamıyor.")
        val actorsView = field("OYUNCULAR", item.actors, if (item.sourceId.isNotBlank()) "Bilgiler yükleniyor…" else "Oyuncu bilgisi bulunmuyor.")
        metaScroll.addView(meta)
        info.addView(metaScroll, LinearLayout.LayoutParams(-1, 125))
        info.addView(makeButton("▶  İZLE", true) { (body.tag as? AlertDialog)?.dismiss(); play(item) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44))
        info.addView(makeButton(if (favorites.contains(item.favoriteKey)) "★ Favorilerden çıkar" else "☆ Favorilere ekle", false) { if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey); prefs.edit().putStringSet("favorites", favorites.toSet()).apply() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40).apply { setMargins(0, 8, 0, 0) })
        body.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val dialog = AlertDialog.Builder(this).setView(body).setNegativeButton("Kapat", null).create(); body.tag = dialog
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.78f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)

        if (item.type == "Filmler" || item.type == "Diziler") {
            val source = savedSources.firstOrNull { it.id == item.sourceId && it.mode == "xtream" }
            if (source != null) thread {
                try {
                    val u = URLEncoder.encode(source.username, "UTF-8")
                    val p = URLEncoder.encode(source.password, "UTF-8")
                    val api = "${source.address.trimEnd('/')}/player_api.php?username=$u&password=$p"
                    val action = if (item.type == "Diziler") "get_series_info&series_id=" else "get_vod_info&vod_id="
                    val root = JSONObject(request("$api&action=$action${URLEncoder.encode(item.id, "UTF-8")}"))
                    val detail = root.optJSONObject("info") ?: root.optJSONObject("movie_data") ?: root
                    fun pick(vararg keys: String): String {
                        for (key in keys) {
                            val value = detail.opt(key)
                            if (value != null && value != JSONObject.NULL) {
                                val text = if (value is String) value else value.toString()
                                if (text.isNotBlank() && text != "0" && text != "null") return text
                            }
                        }
                        return ""
                    }
                    val plot = pick("plot", "description", "overview")
                    val actors = pick("cast", "actors")
                    val rating = pick("rating", "rating_5based", "vote_average")
                    val date = pick("releasedate", "releaseDate", "release_date", "year")
                    val genre = pick("genre", "genres")
                    runOnUiThread {
                        plotView.text = plot.ifBlank { "Konu bilgisi kaynakta bulunmuyor." }
                        actorsView.text = actors.ifBlank { "Oyuncu bilgisi kaynakta bulunmuyor." }
                        ratingView.text = rating.ifBlank { "Puan bilgisi bulunmuyor." }
                        dateView.text = date
                        genreView.text = genre
                        val updated = item.copy(plot = plot, actors = actors, rating = rating, releaseDate = date, genre = genre)
                        val index = items.indexOfFirst { it.id == item.id && it.type == item.type && it.sourceId == item.sourceId }
                        if (index >= 0) items[index] = updated
                        saveCachedItems()
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        if (plotView.text.toString() == "Bilgiler yükleniyor…") plotView.text = t("Konu bilgisi alınamadı.")
                        if (actorsView.text.toString() == "Bilgiler yükleniyor…") actorsView.text = t("Oyuncu bilgisi alınamadı.")
                        if (ratingView.text.toString() == "Bilgi yükleniyor…") ratingView.text = t("Puan bilgisi alınamadı.")
                    }
                }
            }
        }
    }

    private fun readSearchHistory(): List<String> {
        return try {
            val array = JSONArray(prefs.getString("search_history", "[]") ?: "[]")
            (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
                .distinct().take(12)
        } catch (_: Exception) { emptyList() }
    }

    private fun submitSearchQuery(rawQuery: String) {
        val term = rawQuery.trim()
        searchQuery = term
        if (term.isNotBlank()) {
            val updated = (listOf(term) + readSearchHistory().filterNot { it.equals(term, ignoreCase = true) }).take(12)
            val array = JSONArray()
            updated.forEach { array.put(it) }
            prefs.edit().putString("search_history", array.toString()).apply()
        }
        detailSeries = null
        detailMovie = null
        sidebarMode = "search"
        selectedType = "Tümü"
        selectedGroup = "Tüm kategoriler"
        expandedCategory = null
        searchInputFocusPending = true
        render()
    }

    private fun requestSectionFocusAfterRender() {
        content.post {
            fun firstFocusable(view: View): View? {
                if (view.visibility != View.VISIBLE || !view.isEnabled) return null
                if (view.isFocusable && view.isShown) return view
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) {
                        val found = firstFocusable(view.getChildAt(index))
                        if (found != null) return found
                    }
                }
                return null
            }
            val firstContent = firstFocusable(content)
            val firstCategory = firstCategoryFocusView?.takeIf { it.isShown && it.isFocusable }
            firstRightContentFocusView = firstContent
            if (firstContent != null && firstContent.id == View.NO_ID) firstContent.id = View.generateViewId()
            selectedSidebarFocusView?.let { navButton ->
                val rightTarget = firstCategory ?: firstContent
                if (rightTarget != null) {
                    navButton.nextFocusRightId = rightTarget.id
                    rightTarget.nextFocusLeftId = navButton.id
                }
            }
            if (firstContent != null) {
                firstCategory?.let { category ->
                    if (category.id == View.NO_ID) category.id = View.generateViewId()
                    category.nextFocusRightId = firstContent.id
                    firstContent.nextFocusLeftId = category.id
                } ?: selectedSidebarFocusView?.let { navButton -> firstContent.nextFocusLeftId = navButton.id }
            }
            if (focusFirstCategoryOnRender && firstCategory != null) {
                focusFirstCategoryOnRender = false
                initialSidebarFocusPending = false
                firstCategory.requestFocus()
            } else if (initialSidebarFocusPending && navBar.visibility == View.VISIBLE && selectedSidebarFocusView != null) {
                initialSidebarFocusPending = false
                selectedSidebarFocusView?.requestFocus()
            } else if (focusFirstContentOnRender) {
                focusFirstContentOnRender = false
                if (firstContent != null) firstContent.requestFocus()
                else selectedSidebarFocusView?.requestFocus()
            }
        }
    }

    private fun render() {
        if (!::content.isInitialized || !::categoryBar.isInitialized) return
        content.removeAllViews()
        if (::mainContentScroll.isInitialized && selectedType in listOf("Filmler", "Diziler")) {
            mainContentScroll.post { mainContentScroll.scrollTo(0, 0) }
        }
        val detailMode = detailSeries != null || detailMovie != null
        // Reapply the selected theme surfaces on every normal tab render.
        // Previously only the Live TV category branch refreshed them, leaving
        // Movies, Series, Favorites and Search with stale near-black panels.
        if (!detailMode && ::rootLayout.isInitialized && ::mainContentScroll.isInitialized) {
            applyThemeSurfaces()
        }
        content.setPadding(
            if (detailMode) 0 else dp(18),
            if (detailMode) 0 else dp(18),
            if (detailMode) 0 else dp(30),
            if (detailMode) 0 else dp(20)
        )
        val firstEntry = items.isEmpty() && !isLoadingSource
        if (::sidebar.isInitialized) sidebar.visibility = if (firstEntry || isLoadingSource || detailMode) View.GONE else View.VISIBLE
        if (::brandView.isInitialized) brandView.visibility = if (firstEntry || isLoadingSource || detailMode) View.GONE else View.VISIBLE
        if (::rootLayout.isInitialized) {
            rootLayout.setPadding(if (detailMode) 0 else dp(18), if (detailMode) 0 else dp(14),
                if (detailMode) 0 else dp(18), if (detailMode) 0 else dp(12))
        }
        // Keep the app immersive after every render/focus-driven UI refresh.
        // Setting SYSTEM_UI_FLAG_VISIBLE here caused system bars to reappear on selection.
        applyKaiserImmersiveMode()
        if (::heroPanel.isInitialized) heroPanel.visibility = View.GONE
        if (::quickAccessScroll.isInitialized) quickAccessScroll.visibility = View.GONE
        if (::navBar.isInitialized) navBar.visibility = if (firstEntry || isLoadingSource) View.GONE else View.VISIBLE
        if (::mainHeader.isInitialized) mainHeader.visibility = View.GONE
        if (::dateClockRow.isInitialized) dateClockRow.visibility =
            if (firstEntry || isLoadingSource || detailMode || selectedType == "Ayarlar") View.GONE else View.VISIBLE
        if (::status.isInitialized) status.visibility = if (firstEntry || isLoadingSource || detailMode || selectedType == "Tümü" || selectedType == "Filmler" || selectedType == "Diziler" || (selectedType == "Canlı TV" && sidebarMode == "context")) View.GONE else View.VISIBLE
        if (::categoryScroll.isInitialized) categoryScroll.visibility = View.GONE
        if (firstEntry) {
            mainContentScroll.visibility = View.VISIBLE
            val welcome = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            val welcomeLogo = TextView(this).apply {
                val logo = android.text.SpannableString("▶ KaiserIPTV+")
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 2, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 8, 12, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 196, 0)), 12, 13, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = logo
                textSize = 38f; gravity = Gravity.CENTER
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(20))
                setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.rgb(0, 80, 190))
            }
            welcome.addView(welcomeLogo, LinearLayout.LayoutParams(-1, dp(110)))
            welcome.addView(TextView(this).apply {
                text = t("İzleme kaynağını ekleyerek başla")
                textSize = 16f; setTextColor(muted); gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(18))
            })
            val inlineForm = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            sourceName = edit(t("Kaynak adı girin"), false)
            sourceName.hint = t("Kaynak adı girin")
            inlineForm.addView(TextView(this).apply {
                text = "Kaynak adı (ör. Ev Xtream)"
                textSize = 13f; setTextColor(muted)
                setPadding(0, dp(4), 0, dp(3))
            })
            sourceName.id = View.generateViewId()
            sourceName.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            inlineForm.addView(sourceName, LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, 0, 0, dp(6))
            })
            sourceMode = RadioGroup(this).apply {
                id = View.generateViewId()
                orientation = RadioGroup.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val inlineM3u = RadioButton(this).apply {
                id = View.generateViewId(); text = "M3U / M3U8"
                setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                isChecked = true
            }
            val inlineXtream = RadioButton(this).apply {
                id = View.generateViewId(); text = "Xtream Codes"
                setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            inlineM3u.id = View.generateViewId()
            inlineXtream.id = View.generateViewId()
            sourceMode.addView(inlineM3u)
            sourceMode.addView(inlineXtream)
            inlineForm.addView(sourceMode, LinearLayout.LayoutParams(-1, dp(42)))
            playlistUrl = edit("URL adresini girin", false).apply {
                id = View.generateViewId()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            server = edit("Sunucu adresi (https://...)", false).apply {
                id = View.generateViewId()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            username = edit("Kullanıcı adı", false).apply {
                id = View.generateViewId()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            }
            password = edit("Şifre", true).apply {
                id = View.generateViewId()
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            }
            val playlistLabel = TextView(this).apply {
                text = "M3U / M3U8 oynatma listesi URL'si"
                textSize = 13f; setTextColor(muted)
                setPadding(0, dp(4), 0, dp(3))
            }
            inlineForm.addView(playlistLabel)
            inlineForm.addView(playlistUrl, LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, 0, 0, dp(6))
            })
            inlineForm.addView(server, LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, 0, 0, dp(6))
            })
            val inlineCredentials = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            inlineCredentials.addView(username, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(0, 0, dp(4), 0)
            })
            inlineCredentials.addView(password, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(4), 0, 0, 0)
            })
            inlineForm.addView(inlineCredentials)
            fun updateInlineFields() {
                val useXtream = sourceMode.checkedRadioButtonId == inlineXtream.id
                playlistLabel.visibility = if (useXtream) View.GONE else View.VISIBLE
                playlistUrl.visibility = if (useXtream) View.GONE else View.VISIBLE
                server.visibility = if (useXtream) View.VISIBLE else View.GONE
                inlineCredentials.visibility = if (useXtream) View.VISIBLE else View.GONE
            }
            sourceMode.setOnCheckedChangeListener { _, _ -> updateInlineFields() }
            updateInlineFields()

            // Keep keyboard Enter and TV-remote Down navigation in the same visual order.
            fun focusNextOnDown(view: View, next: () -> View) {
                view.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN &&
                        event.action == android.view.KeyEvent.ACTION_DOWN) {
                        next().requestFocus()
                        true
                    } else false
                }
            }
            sourceName.setOnEditorActionListener { _, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
                    (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                     event.action == android.view.KeyEvent.ACTION_DOWN)) {
                    (if (sourceMode.checkedRadioButtonId == inlineXtream.id) inlineXtream else inlineM3u).requestFocus()
                    true
                } else false
            }
            focusNextOnDown(sourceName) { if (sourceMode.checkedRadioButtonId == inlineXtream.id) inlineXtream else inlineM3u }
            focusNextOnDown(inlineM3u) { if (sourceMode.checkedRadioButtonId == inlineXtream.id) server else playlistUrl }
            focusNextOnDown(inlineXtream) { if (sourceMode.checkedRadioButtonId == inlineXtream.id) server else playlistUrl }
            focusNextOnDown(playlistUrl) { playlistUrl.focusSearch(View.FOCUS_DOWN) ?: playlistUrl }
            server.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { username.requestFocus(); true } else false
            }
            username.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) { password.requestFocus(); true } else false
            }
            focusNextOnDown(server) { username }
            focusNextOnDown(username) { password }
            inlineForm.addView(makeButton("＋ KAYNAK EKLE", true) {
                val useXtream = sourceMode.checkedRadioButtonId == inlineXtream.id
                if (useXtream) {
                    val serverAddress = server.text.toString().trim()
                    if (!(serverAddress.startsWith("http://") || serverAddress.startsWith("https://")) ||
                        username.text.isNullOrBlank() || password.text.isNullOrBlank()) {
                        Toast.makeText(this, "Sunucu, kullanıcı adı ve şifreyi doldur.", Toast.LENGTH_LONG).show()
                        return@makeButton
                    }
                    val label = sourceName.text.toString().trim().ifBlank { "Xtream ${savedSources.size + 1}" }
                    val src = SavedSource(java.util.UUID.randomUUID().toString(), label, "xtream",
                        serverAddress.trimEnd('/'), username.text.toString().trim(), password.text.toString())
                    saveSource(src)
                    hideKeyboard()
                    loadXtream(src, false)
                } else {
                    val address = playlistUrl.text.toString().trim()
                    if (!address.startsWith("http://") && !address.startsWith("https://")) {
                        Toast.makeText(this, "Geçerli bir M3U/M3U8 URL'si gir.", Toast.LENGTH_LONG).show()
                        return@makeButton
                    }
                    val label = sourceName.text.toString().trim().ifBlank { "M3U ${savedSources.size + 1}" }
                    val src = SavedSource(java.util.UUID.randomUUID().toString(), label, "m3u", address)
                    saveSource(src)
                    hideKeyboard()
                    loadM3u(src, false)
                }
            }, LinearLayout.LayoutParams(dp(280), dp(52)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(18), 0, 0)
            })
            welcome.addView(inlineForm, LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(10), 0, 0)
            })
            content.addView(welcome, LinearLayout.LayoutParams(-1, -1))
            sidebarMenuScroll.visibility = View.GONE
            return
        } else {
            sidebarMenuScroll.visibility = View.VISIBLE
        }
        if (isLoadingSource) {
            val loadingPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            val logoLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            logoLine.addView(TextView(this).apply {
                text = "▶"
                textSize = 30f
                setTextColor(Color.rgb(0, 190, 255))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.rgb(0, 120, 255))
            })
            logoLine.addView(TextView(this).apply {
                text = "Kaiser"
                textSize = 34f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setShadowLayer(dp(5).toFloat(), 0f, dp(1).toFloat(), Color.rgb(35, 80, 145))
            })
            logoLine.addView(TextView(this).apply {
                val brand = android.text.SpannableString("IPTV+")
                brand.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 170, 255)), 0, 4, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                brand.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 196, 0)), 4, 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = brand
                textSize = 34f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setShadowLayer(dp(7).toFloat(), 0f, 0f, Color.rgb(0, 100, 255))
            })
            loadingPanel.addView(logoLine)
            loadingPanel.addView(TextView(this).apply {
                text = status.text.toString().ifBlank { "Kaynak yükleniyor…" }
                textSize = 18f; setTextColor(muted); gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, 0)
            })
            // Tek bir geniş, çerçevesiz görsel alanı; görseller sağdan sola otomatik akar.
            val visualViewport = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(440), dp(145)).apply {
                    setMargins(0, dp(26), 0, 0)
                }
                clipChildren = true
                clipToPadding = true
            }
            // Sadece görseller/karakterler geçiş yapar; metin etiketi gösterilmez.
            val visualItems = listOf(
                "🎬" to Color.rgb(35, 105, 190),
                "📺" to Color.rgb(145, 75, 175),
                "🎞️" to Color.rgb(35, 150, 125),
                "⚽" to Color.rgb(70, 165, 95),
                "🏆" to Color.rgb(215, 155, 35),
                "🦸" to Color.rgb(70, 130, 240),
                "🥷" to Color.rgb(135, 90, 210),
                "🐼" to Color.rgb(65, 175, 135),
                "🐉" to Color.rgb(225, 95, 70),
                "🎮" to Color.rgb(70, 180, 220)
            )
            val slide = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            val visual = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val visualSymbol = TextView(this).apply {
                text = visualItems[0].first
                textSize = 82f
                gravity = Gravity.CENTER
                setShadowLayer(dp(18).toFloat(), 0f, 0f, visualItems[0].second)
            }
            visual.addView(visualSymbol, LinearLayout.LayoutParams(-1, -1))
            slide.addView(visual, FrameLayout.LayoutParams(-1, -1))
            visualViewport.addView(slide)
            loadingPanel.addView(visualViewport)
            var visualIndex = 0
            val slideRunnable = object : Runnable {
                override fun run() {
                    if (!visualViewport.isAttachedToWindow) return
                    val nextIndex = (visualIndex + 1) % visualItems.size
                    val width = visualViewport.width.toFloat().coerceAtLeast(dp(440).toFloat())
                    visual.animate().translationX(-width).alpha(0f).setDuration(420L).withEndAction {
                        visualIndex = nextIndex
                        visualSymbol.text = visualItems[visualIndex].first
                        visualSymbol.setShadowLayer(dp(18).toFloat(), 0f, 0f, visualItems[visualIndex].second)
                        visual.translationX = width
                        visual.alpha = 0f
                        visual.animate().translationX(0f).alpha(1f).setDuration(420L).start()
                    }.start()
                    visualViewport.postDelayed(this, 1900L)
                }
            }
            visualViewport.postDelayed(slideRunnable, 1300L)
            content.addView(loadingPanel, LinearLayout.LayoutParams(-1, -1))
            return
        }
        if (selectedType == "Ayarlar") { renderSettingsScreen(); return }
        if (detailSeries != null) {
            renderSeriesDetail(detailSeries!!)
            return
        }
        if (detailMovie != null) {
            stopChannelPreview()
            renderMovieDetail(detailMovie!!)
            return
        }
        if (sidebarMode == "search") {
            selectedGroup = "Tüm kategoriler"
            val searchPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(14))
            }
            searchPanel.addView(TextView(this).apply {
                text = t("Ara"); textSize = 27f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(12))
            })
            val searchRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val searchField = EditText(this).apply {
                hint = t("Film, dizi veya kanal ara...")
                setSingleLine(true)
                textSize = 17f
                setTextColor(Color.WHITE)
                setHintTextColor(muted)
                setPadding(dp(16), 0, dp(12), 0)
                background = roundedDrawable(panel, 12, accent)
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                setText(searchQuery)
                setSelection(text.length)
                isFocusable = true
                isFocusableInTouchMode = true
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                        actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                        submitSearchQuery(text.toString())
                        true
                    } else false
                }
            }
            searchRow.addView(searchField, LinearLayout.LayoutParams(0, dp(54), 1f))
            searchRow.addView(makeButton(t("ARA"), true) {
                submitSearchQuery(searchField.text.toString())
            }, LinearLayout.LayoutParams(dp(92), dp(54)).apply { setMargins(dp(10), 0, 0, 0) })
            searchPanel.addView(searchRow)
            content.addView(searchPanel, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (searchInputFocusPending) {
                searchInputFocusPending = false
                searchField.post {
                    searchField.requestFocus()
                    searchField.setSelection(searchField.text.length)
                }
            }
            if (searchQuery.isBlank()) {
                val history = readSearchHistory()
                val historyHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                historyHeader.addView(TextView(this).apply {
                    text = t("Arama geçmişi"); textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (history.isNotEmpty()) historyHeader.addView(makeButton(
                    if (selectedSearchHistory.isEmpty()) t("Geçmişi sil") else t("Seçileni sil (") + selectedSearchHistory.size + ")", false
                ) {
                    val remaining = if (selectedSearchHistory.isEmpty()) emptyList()
                        else history.filterNot { selectedSearchHistory.contains(it) }
                    val array = JSONArray()
                    remaining.forEach { array.put(it) }
                    if (remaining.isEmpty()) prefs.edit().remove("search_history").apply()
                    else prefs.edit().putString("search_history", array.toString()).apply()
                    selectedSearchHistory.clear()
                    render()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
                content.addView(historyHeader)
                if (history.isEmpty()) {
                    selectedSearchHistory.clear()
                    content.addView(TextView(this).apply {
                        text = t("Aradığın film, dizi veya kanal adını yaz.")
                        textSize = 14f; setTextColor(muted); setPadding(dp(8), dp(8), dp(8), dp(8))
                    })
                } else {
                    selectedSearchHistory.retainAll(history.toSet())
                    val historyFlow = WrappingRowLayout(this).apply {
                        setPadding(dp(4), dp(2), dp(4), dp(2))
                    }
                    history.forEach { term ->
                        val historyButton = makeButton(
                            (if (selectedSearchHistory.contains(term)) "✓ " else "◷ ") + term,
                            selectedSearchHistory.contains(term)
                        ) {
                            // A normal click reruns this previous search immediately.
                            selectedSearchHistory.clear()
                            submitSearchQuery(term)
                        }.apply {
                            textSize = 12f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            minWidth = 0
                            minimumWidth = 0
                            setPadding(dp(4), 0, dp(4), 0)
                            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                            setTextColor(if (selectedSearchHistory.contains(term)) accentBright else muted)
                            setOnFocusChangeListener { view, hasFocus ->
                                view.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                                (view as TextView).setTextColor(
                                    if (hasFocus || selectedSearchHistory.contains(term)) accentBright else muted
                                )
                                view.scaleX = if (hasFocus) 1.04f else 1f
                                view.scaleY = if (hasFocus) 1.04f else 1f
                            }
                            // Long press keeps the multi-select/delete workflow.
                            setOnLongClickListener {
                                if (!selectedSearchHistory.add(term)) selectedSearchHistory.remove(term)
                                render()
                                true
                            }
                        }
                        historyFlow.addView(historyButton, ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)
                        ).apply { setMargins(0, 0, dp(4), dp(4)) })
                    }
                    content.addView(historyFlow, LinearLayout.LayoutParams(
                        -1, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(dp(4), dp(3), dp(4), dp(3)) })
                }
            }
        }
        val selectedSourceIds = prefs.getStringSet("selected_source_ids", emptySet()) ?: emptySet()
        val activeSourceId = prefs.getString("last_source_id", "") ?: ""
        val activeSourceItemsBase = if (selectedSourceIds.size > 1) items.filter { selectedSourceIds.contains(it.sourceId) }
            else if (selectedSourceIds.size == 1) items.filter { it.sourceId == selectedSourceIds.first() }
            else if (activeSourceId.isBlank()) items.toList()
            else items.filter { it.sourceId == activeSourceId }
        if (selectedCategorySourceId.isBlank() || activeSourceItemsBase.none { it.sourceId == selectedCategorySourceId }) {
            selectedCategorySourceId = activeSourceId.takeIf { id -> activeSourceItemsBase.any { it.sourceId == id } }
                ?: activeSourceItemsBase.firstOrNull()?.sourceId.orEmpty()
        }
        val activeSourceItems = if (selectedCategorySourceId.isNotBlank())
            activeSourceItemsBase.filter { it.sourceId == selectedCategorySourceId }
        else activeSourceItemsBase
        val unfilteredBase = when {
            // An expanded content row must render its captured items directly, independent
            // of the currently selected category filter.
            expandedCategory != null -> expandedCategory!!.second
            sidebarMode == "search" -> activeSourceItems
            selectedType == "Tümü" -> activeSourceItems
            selectedType == "Favoriler" -> activeSourceItems.filter { favorites.contains(it.favoriteKey) }
            selectedType == "Son İzlenenler" -> recent.filter { activeSourceId.isBlank() || it.sourceId == activeSourceId }
            else -> activeSourceItems.filter { it.type == selectedType }
        }
        val query = if (sidebarMode == "search") searchQuery.trim() else ""
        val categoryVisibleBase = if (sidebarMode == "search") unfilteredBase else unfilteredBase.filter { item -> !hiddenCategories.contains(item.type + "|" + item.group.ifBlank { "Diğer" }) }
        val baseVisible = if (query.isBlank()) categoryVisibleBase else categoryVisibleBase.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.group.contains(query, ignoreCase = true) ||
            it.type.contains(query, ignoreCase = true)
        }
        val groupsAvailable = baseVisible.map { it.group.ifBlank { "Diğer" } }.distinct().sorted()
        categoryBar.removeAllViews()
        groupsAvailable.take(40).forEach { group ->
            categoryBar.addView(makeButton(if (group == selectedGroup) "● $group" else group, group == selectedGroup) {
                selectedGroup = group
                render()
            }.apply {
                textSize = 11f; minWidth = 0; minimumWidth = 0
                setPadding(12, 0, 12, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36).apply { setMargins(0, 2, 8, 2) })
        }
        val visible = if (selectedGroup == "Tüm kategoriler" || sidebarMode == "search") baseVisible
            else baseVisible.filter { it.group.ifBlank { it.type } == selectedGroup }

        // Live TV category content: compact logo/name grid; OK opens the channel fullscreen.
        if (selectedType == "Canlı TV" && sidebarMode == "context") {
            content.removeAllViews()
            applyThemeSurfaces()
            content.clipChildren = true
            content.clipToPadding = true
            val tvChannels = visible.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
            val tvHeading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(12))
            }
            tvHeading.addView(TextView(this).apply {
                text = if (selectedGroup == "Tüm kategoriler") t("Tüm Kanallar") else selectedGroup
                textSize = 22f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tvHeading.addView(TextView(this).apply {
                text = "${tvChannels.size}"
                textSize = 14f
                setTextColor(muted)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            content.addView(tvHeading, LinearLayout.LayoutParams(-1, dp(46)))
            val displayedTvChannels = tvChannels.take(liveChannelsLimit)
            if (tvChannels.isEmpty()) {
                content.addView(TextView(this).apply {
                    text = t("Bu kategoride kanal bulunamadı.")
                    textSize = 15f; setTextColor(muted); setPadding(dp(8), dp(12), dp(8), dp(12))
                })
            } else {
                var channelRow: LinearLayout? = null
                val tvGridColumns = 4 // 4 x 2 = eight equally sized channel cards on screen
                displayedTvChannels.forEachIndexed { index, channel ->
                    if (index % tvGridColumns == 0) {
                        channelRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.TOP
                            clipChildren = true
                            clipToPadding = true
                            setPadding(0, dp(8), 0, dp(6))
                        }
                        content.addView(channelRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        setPadding(dp(8), dp(8), dp(8), dp(8))
                        // TV channel tiles stay transparent; only the focused tile gets a theme outline.
                        background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        minimumHeight = dp(126)
                        setOnFocusChangeListener { v, hasFocus ->
                            v.animate().cancel()
                            v.background = if (hasFocus) roundedDrawable(Color.TRANSPARENT, 12, accent)
                                else android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                            v.elevation = 0f
                            v.translationZ = 0f
                            v.scaleX = 1f
                            v.scaleY = 1f
                            v.translationY = 0f
                        }
                        setOnClickListener { play(channel) }
                        contentDescription = channel.name
                    }
                    val logo = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = channel.name
                        setImageDrawable(GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(Color.rgb(31, 39, 54))
                        })
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                    }
                    tile.addView(logo, LinearLayout.LayoutParams(-1, dp(68)))
                    if (channel.icon.startsWith("http://") || channel.icon.startsWith("https://")) {
                        loadPoster(channel.icon, logo)
                    } else {
                        logo.setImageDrawable(GradientDrawable().apply {
                            cornerRadius = 0f
                            setColor(Color.rgb(31, 39, 54))
                        })
                    }
                    tile.addView(TextView(this).apply {
                        text = channel.name
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(5), 0, 0)
                    }, LinearLayout.LayoutParams(-1, dp(36)))
                    channelRow?.addView(tile, LinearLayout.LayoutParams(0, dp(132), 1f).apply {
                        setMargins(dp(5), dp(5), dp(5), dp(10))
                    })
                }
                if (displayedTvChannels.size < tvChannels.size) {
                    content.addView(makeButton("Daha fazla kanal göster  (${displayedTvChannels.size}/${tvChannels.size})", false) {
                        liveChannelsLimit = (liveChannelsLimit + 72).coerceAtMost(tvChannels.size)
                        render()
                    }, LinearLayout.LayoutParams(-1, dp(48)).apply {
                        setMargins(dp(8), dp(10), dp(8), dp(18))
                    })
                }
            }
            rebuildNavigation()
            requestSectionFocusAfterRender()
            return
        }
        if (visible.isEmpty() && !(sidebarMode == "search" && query.isBlank())) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 24, 24, 24); background = roundedDrawable(panel, 18, Color.rgb(42, 49, 64))
            }
            empty.addView(TextView(this).apply {
                text = t(when {
                    sidebarMode == "search" && query.isBlank() -> "Tüm kaynaklarda ara"
                    sidebarMode == "search" -> "Arama sonucu bulunamadı"
                    selectedType == "Favoriler" -> "Henüz favori eklemedin"
                    selectedType == "Son İzlenenler" -> "İzleme geçmişin burada görünecek"
                    else -> "Kütüphanen seni bekliyor"
                })
                textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            })
            empty.addView(TextView(this).apply {
                text = t(when {
                    sidebarMode == "search" && query.isBlank() -> "Film, dizi veya kanal adını yaz. Bütün kayıtlı kaynaklar taranır."
                    sidebarMode == "search" -> "Başka bir kelime deneyebilir veya yazımı kontrol edebilirsin."
                    selectedType == "Favoriler" -> "Bir içerikte yıldız düğmesine basarak favorilerine ekle."
                    selectedType == "Son İzlenenler" -> "Bir içerik oynattığında burada kolayca devam edebilirsin."
                    items.isEmpty() -> "Başlamak için kendi M3U/M3U8 veya Xtream kaynağını ekle."
                    else -> "Bu bölümde şu anda gösterilecek içerik yok."
                })
                textSize = 14f; setTextColor(muted); setPadding(0, 7, 0, 14)
            })
            if (items.isEmpty()) empty.addView(makeButton(t("KAYNAK EKLE  →"), true) { showConnectionDialog() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 44))
            content.addView(empty, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 16) })
            if (selectedType in listOf("Favoriler", "Son İzlenenler")) {
                rebuildNavigation()
                requestSectionFocusAfterRender()
            }
            return
        }
        if (::heroTitle.isInitialized) {
            heroTitle.text = when {
                selectedType == "Canlı TV" -> "Canlı yayın zamanı"
                selectedType == "Filmler" -> "Film gecesi başlasın"
                selectedType == "Diziler" -> "Bir sonraki bölüm seni bekliyor"
                selectedType == "Favoriler" -> "Favorilerin burada"
                selectedType == "Son İzlenenler" -> "Kaldığın yerden devam et"
                else -> "Kütüphaneni keşfet"
            }
            heroSubtitle.text = if (items.isEmpty()) "Kaynağını ekle, içeriklerini keşfet." else "${items.size} içerik kütüphanende hazır."
            if (::heroArt.isInitialized) {
                heroArt.removeAllViews()
                val featured = baseVisible.firstOrNull { it.icon.startsWith("http://") || it.icon.startsWith("https://") }
                if (featured != null) {
                    val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = featured.name }
                    heroArt.addView(image, FrameLayout.LayoutParams(-1, -1))
                    loadPoster(featured.icon, image)
                    heroArt.addView(TextView(this).apply {
                        text = featured.name; textSize = 11f; setTextColor(Color.WHITE)
                        gravity = Gravity.BOTTOM; setPadding(8, 24, 8, 8)
                        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xDD070A11.toInt()))
                    }, FrameLayout.LayoutParams(-1, -1))
                } else {
                    heroArt.addView(TextView(this).apply {
                        text = "K"; textSize = 88f; setTextColor(accent)
                        setTypeface(null, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                }
            }
        }
        rebuildNavigation()
        val groups: List<Pair<String, List<MediaItem>>> = if (expandedCategory != null) {
            listOf(expandedCategory!!)
        } else if (selectedType == "Canlı TV") {
            emptyList()
        } else if (sidebarMode == "search") {
            if (query.isBlank()) emptyList()
            else visible.take(240).groupBy { it.type }.map { (type, entries) -> "$type • Arama sonuçları" to entries }
        } else if (selectedType == "Tümü") {
            // Weekly Turkey streaming chart, 19–26 Oct 2026. Existing playlist items are
            // prioritized by title; titles missing from the user's source are not fabricated.
            val trendingMovies = listOf(
                "gençlik", "organize işler: sazan sarmalı", "harry potter ve ateş kadehi",
                "iyi ki aldatılmışım", "sevimli hayalet casper",
                "harry potter ve ölüm yadigârları", "hizmetçi", "addams ailesi", "aşk hipotezi"
            )
            val trendingSeries = listOf(
                "organize işler: karun hazinesi", "off campus", "game of thrones", "from",
                "mezarlık", "çekiç ve gül", "tulsa king", "prens", "sex education", "konuşanlar"
            )
            fun rankedByTrend(source: List<MediaItem>, terms: List<String>): List<MediaItem> {
                val remaining = source.toMutableList()
                val ranked = mutableListOf<MediaItem>()
                terms.forEach { term ->
                    val match = remaining.firstOrNull { it.name.lowercase().contains(term) }
                    if (match != null) {
                        ranked.add(match)
                        remaining.remove(match)
                    }
                }
                ranked.addAll(remaining)
                return ranked
            }
            listOf(
                "Türkiye Trendleri • Haftanın Filmleri" to rankedByTrend(activeSourceItems.filter { it.type == "Filmler" }, trendingMovies).take(24),
                "Türkiye Trendleri • Haftanın Dizileri" to rankedByTrend(activeSourceItems.filter { it.type == "Diziler" }, trendingSeries).take(24),
            ).filter { it.second.isNotEmpty() }
        } else if (selectedType == "Filmler" || selectedType == "Diziler") {
            // Trend rows are shown on the home screen only; category screens list categories directly.
            val source = visible.filter { it.type == selectedType }
            source.groupBy { it.group.ifBlank { "Diğer" } }.map { it.key to it.value }
                .filter { it.second.isNotEmpty() }
        } else if (selectedType == "Son İzlenenler") {
            listOf("Diziler" to visible.filter { it.type == "Diziler" },
                "Filmler" to visible.filter { it.type == "Filmler" },
                "Canlı TV" to visible.filter { it.type == "Canlı TV" })
                .filter { it.second.isNotEmpty() }
        } else {
            visible.groupBy {
                if (selectedType == "Favoriler") it.type
                else it.group.ifBlank { "Diğer" }
            }.map { it.key to it.value }
        }
        // Xtream sources can contain hundreds of categories. Building poster cards for every
        // category at once can exhaust memory on older Android TV boxes. Keep the first
        // 12 category rows on screen; every category remains accessible from the sidebar
        // and selecting one shows that category's items.
        val groupsToRender = expandedCategory?.let { listOf(it.first to it.second) }
            ?: if (selectedType in listOf("Filmler", "Diziler")) groups.take(1) else groups

        // Home hero preview sits above the weekly trending rows.
        if (selectedType == "Tümü" && sidebarMode != "search" && expandedCategory == null) {
            val heroItem = recent.firstOrNull { it.type in listOf("Filmler", "Diziler") && it.icon.isNotBlank() }
                ?: activeSourceItems.firstOrNull { it.type == "Filmler" && it.icon.isNotBlank() }
                ?: activeSourceItems.firstOrNull { it.type == "Diziler" && it.icon.isNotBlank() }
            if (heroItem != null) {
                val hero = FrameLayout(this).apply {
                    background = roundedDrawable(panel, 18)
                    clipToOutline = true
                    isFocusable = false
                }
                val heroImage = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                hero.addView(heroImage, FrameLayout.LayoutParams(-1, -1))
                loadPoster(heroItem.icon, heroImage)
                hero.addView(View(this).apply {
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(Color.rgb(7, 8, 14), Color.argb(225, 7, 8, 14), Color.TRANSPARENT)
                    )
                }, FrameLayout.LayoutParams(-1, -1))
                val heroCopy = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(24), dp(18), dp(20), dp(18))
                }
                heroCopy.addView(TextView(this).apply {
                    text = heroItem.name
                    textSize = 25f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(dp(440), ViewGroup.LayoutParams.WRAP_CONTENT))
                val heroMeta = listOf(heroItem.type, heroItem.releaseDate, heroItem.rating.takeIf { it.isNotBlank() }?.let { "★ $it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString("   •   ")
                if (heroMeta.isNotBlank()) heroCopy.addView(TextView(this).apply {
                    text = heroMeta
                    textSize = 14f
                    setTextColor(accentBright)
                    setPadding(0, dp(7), 0, dp(5))
                })
                if (heroItem.plot.isNotBlank()) heroCopy.addView(TextView(this).apply {
                    text = heroItem.plot
                    textSize = 14f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.LTGRAY)
                }, LinearLayout.LayoutParams(dp(430), ViewGroup.LayoutParams.WRAP_CONTENT))
                val heroActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                heroActions.addView(makeButton(t("İzle"), true) {
                    if (heroItem.type == "Diziler") showSeriesDetail(heroItem) else showMovieDetail(heroItem)
                }, LinearLayout.LayoutParams(dp(112), dp(42)).apply { setMargins(0, dp(12), dp(10), 0) })
                heroActions.addView(makeButton(t("Detaylar"), false) {
                    if (heroItem.type == "Diziler") showSeriesDetail(heroItem) else showMovieDetail(heroItem)
                }, LinearLayout.LayoutParams(dp(132), dp(42)).apply { setMargins(0, dp(12), 0, 0) })
                heroCopy.addView(heroActions)
                hero.addView(heroCopy, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_VERTICAL))
                content.addView(hero, LinearLayout.LayoutParams(-1, dp(250)).apply {
                    setMargins(dp(4), dp(2), dp(4), dp(18))
                })
            }
        }

        groupsToRender.forEach { (groupName, entries) ->
            val showingExpandedCategory = expandedCategory?.first == groupName
            val posterGridMode = showingExpandedCategory || selectedType == "Filmler" || selectedType == "Diziler" || selectedType == "Canlı TV"
            val fiveColumnMediaGrid = selectedType in listOf("Filmler", "Diziler")
            val liveTvEightColumnGrid = selectedType == "Canlı TV"
            val sectionHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(2, dp(4), 2, dp(6))
            }
            sectionHeader.addView(TextView(this).apply {
                text = groupName; textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val sectionCountView = TextView(this).apply {
                id = View.generateViewId()
                text = t("← Geri dön")
                textSize = 12f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                visibility = View.GONE
                isFocusable = true
                isClickable = true
                setOnClickListener { expandedCategory = null; render() }
            }
            sectionHeader.addView(sectionCountView)
            content.addView(sectionHeader)
            val row = LinearLayout(this).apply {
                orientation = if (posterGridMode) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = if (posterGridMode) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                if (posterGridMode) setPadding(0, dp(14), 0, dp(8))
            }
            val weeklyMovieFeature = selectedType == "Tümü" && (groupName.contains("Haftanın Filmleri") || groupName.contains("Haftanın Dizileri"))
            // Ana ekranda haftanın filmleri tek tek geniş, afiş odaklı kartlar olarak gösterilir.
            // Yatay kaydırma ile bir sonraki tam genişlikte afişe geçilir.
            val featureCardWidth = dp(140) // Kart genişliği afişle aynı
            val displayedEntries = if (posterGridMode || weeklyMovieFeature) entries else entries.take(5)
            var expandedPosterRow: LinearLayout? = null
            var expandedPosterCount = 0
            displayedEntries.forEachIndexed { itemIndex, item ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, 0)
                    background = roundedDrawable(Color.TRANSPARENT, 10)
                    isFocusable = true; isFocusableInTouchMode = true
                    setOnFocusChangeListener { v, hasFocus ->
                        // Focus lifts the card above siblings; no glow or focus border is drawn.
                        v.animate().cancel()
                        v.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        v.elevation = if (hasFocus) dp(22).toFloat() else 0f
                        v.translationZ = if (hasFocus) dp(22).toFloat() else 0f
                        v.cameraDistance = dp(2200).toFloat()
                        v.animate().scaleX(if (hasFocus) 1.15f else 1f)
                            .scaleY(if (hasFocus) 1.15f else 1f)
                            .translationY(if (hasFocus) -dp(10).toFloat() else 0f)
                            .translationZ(if (hasFocus) dp(22).toFloat() else 0f)
                            .setDuration(180).start()
                    }
                    setOnClickListener {
                        // Search results for live channels should start playback immediately.
                        if (sidebarMode == "search" && item.type == "Canlı TV") {
                            play(item)
                        } else {
                            when (item.type) {
                                "Diziler" -> showSeriesDetail(item)
                                "Filmler" -> showMovieDetail(item)
                                else -> showItemDetails(item)
                            }
                        }
                    }
                    setOnLongClickListener {
                        // Long-press a live-channel search result to toggle its favorite state.
                        if (sidebarMode == "search" && item.type == "Canlı TV") {
                            val added = if (favorites.contains(item.favoriteKey)) {
                                favorites.remove(item.favoriteKey)
                                false
                            } else {
                                favorites.add(item.favoriteKey)
                                true
                            }
                            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                            Toast.makeText(this@MainActivity,
                                if (added) "${item.name} favorilere eklendi" else "${item.name} favorilerden çıkarıldı",
                                Toast.LENGTH_SHORT).show()
                            true
                        } else {
                            false
                        }
                    }
                }
                val liveCard = item.type == "Canlı TV"
                val homeCard = selectedType == "Tümü"
                val weeklyPoster = weeklyMovieFeature && !showingExpandedCategory
                val poster = ImageView(this).apply {
                    scaleType = if (liveCard) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    contentDescription = item.name
                    background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(8, 10, 14), Color.rgb(14, 18, 24))))
                    setPadding(if (liveCard) 12 else 0, if (liveCard) 12 else 0, if (liveCard) 12 else 0, if (liveCard) 12 else 0)
                    // Keep the poster's portrait aspect ratio; the card wraps the poster instead of stretching it.
                    layoutParams = LinearLayout.LayoutParams(
                        if (weeklyPoster) dp(140) else -1,
                        if (liveCard) dp(88) else if (weeklyPoster) dp(210) else if (posterGridMode) dp(210) else if (homeCard) 230 else 210
                    )
                }
                card.addView(poster)
                if (focusFirstContentOnRender && item == displayedEntries.firstOrNull()) {
                    card.post { if (card.isAttachedToWindow) card.requestFocus() }
                }
                if (item.icon.startsWith("http://") || item.icon.startsWith("https://")) loadPoster(item.icon, poster)
                else poster.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(8, 10, 14), Color.rgb(14, 18, 24))))
                if (weeklyPoster) {
                    card.addView(TextView(this).apply {
                        text = t(item.type).uppercase()
                        textSize = 10f; setTextColor(accent); letterSpacing = 0.03f
                    })
                } else {
                    card.addView(TextView(this).apply {
                        text = if (item.type == "Canlı TV") "●  ${t("CANLI YAYIN")}" else t(item.type).uppercase()
                        textSize = 10f; setTextColor(accent); setPadding(3, 8, 2, 4); letterSpacing = 0.05f
                    })
                }
                card.addView(TextView(this).apply {
                    text = item.name; textSize = 14f; setTextColor(Color.WHITE); maxLines = 2
                    minHeight = 40; setPadding(if (weeklyPoster) 0 else 3, 0, if (weeklyPoster) 0 else 3, 0)
                })
                if (!showingExpandedCategory && !weeklyPoster && selectedType != "Filmler" && selectedType != "Diziler" &&
                    selectedType != "Favoriler" && selectedType != "Son İzlenenler") {
                    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    if (!homeCard && selectedType != "Filmler" && selectedType != "Diziler") actions.addView(makeButton(t("▶ İzle"), true) { play(item) },
                        LinearLayout.LayoutParams(0, 36, 1f).apply { setMargins(0, 7, 4, 0) })
                    // Arama sonuçlarında kart üzerindeki Favori kısayolunu gösterme.
                    // Favori işlemi film/dizi detay ekranında kullanılmaya devam eder.
                    if (sidebarMode != "search") {
                        actions.addView(makeButton(if (favorites.contains(item.favoriteKey)) "★" else "☆", false) {
                            if (!favorites.add(item.favoriteKey)) favorites.remove(item.favoriteKey)
                            prefs.edit().putStringSet("favorites", favorites.toSet()).apply()
                            render()
                        }, LinearLayout.LayoutParams(43, 36).apply { setMargins(4, 7, 0, 0) })
                    }
                    card.addView(actions)
                }
                if (posterGridMode) {
                    if (itemIndex == displayedEntries.lastIndex) card.nextFocusRightId = sectionCountView.id
                    // Live TV uses a fixed eight-card row, matching the requested TV layout.
                    val gridColumns = if (liveTvEightColumnGrid) 8 else if (fiveColumnMediaGrid) 5 else 4
                    if (expandedPosterCount % gridColumns == 0) {
                        expandedPosterRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.TOP
                            clipChildren = false
                            clipToPadding = false
                        }
                        row.addView(expandedPosterRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    expandedPosterRow?.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), 0, dp(3), dp(12))
                    })
                    expandedPosterCount++
                } else {
                    row.addView(card, LinearLayout.LayoutParams(if (liveCard) dp(112) else if (weeklyMovieFeature) featureCardWidth else 232, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        // Consistent TV-friendly breathing room between poster cards.
                        setMargins(dp(5), 0, if (weeklyMovieFeature) dp(20) else dp(14), dp(10))
                    })
                }
            }
            // Keep the final poster row aligned to the same five-column grid.
            val finalGridColumns = if (liveTvEightColumnGrid) 8 else if (fiveColumnMediaGrid) 5 else 4
            if (posterGridMode && expandedPosterCount > 0 && expandedPosterCount % finalGridColumns != 0) {
                val remainingSlots = finalGridColumns - (expandedPosterCount % finalGridColumns)
                repeat(remainingSlots) {
                    expandedPosterRow?.addView(View(this), LinearLayout.LayoutParams(0, if (liveTvEightColumnGrid) dp(120) else dp(210), 1f).apply {
                        setMargins(dp(6), 0, dp(6), dp(12))
                    })
                }
            }

            if (posterGridMode) {
                content.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            } else {
                content.addView(HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    clipChildren = false
                    clipToPadding = false
                    isFillViewport = true
                    setPadding(dp(18), dp(14), dp(30), dp(12))
                    // Keep poster rows anchored to the left, even when there is only one item.
                    row.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    addView(row)
                }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        requestSectionFocusAfterRender()
    }

    private fun showSeriesDetail(series: MediaItem) {
        detailSeries = series
        seriesEpisodeRows = emptyList()
        seriesEpisodesLoading = true
        seriesEpisodesError = ""
        render()

        val source = savedSources.firstOrNull { it.id == series.sourceId && it.mode == "xtream" }
        if (source == null) {
            seriesEpisodesLoading = false
            seriesEpisodesError = "Bölümleri göstermek için bu dizinin Xtream kaynağına bağlan."
            render()
            return
        }

        thread {
            try {
                val u = URLEncoder.encode(source.username, "UTF-8")
                val p = URLEncoder.encode(source.password, "UTF-8")
                val api = "${source.address.trimEnd('/')}/player_api.php?username=$u&password=$p&action=get_series_info&series_id=${URLEncoder.encode(series.seriesId.ifBlank { series.id }, "UTF-8")}"
                val data = JSONObject(request(api))
                val episodes = data.optJSONObject("episodes") ?: JSONObject()
                val rows = mutableListOf<Pair<String, List<MediaItem>>>()
                val keys = episodes.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = episodes.optJSONArray(key) ?: continue
                    val seasonItems = mutableListOf<MediaItem>()
                    for (i in 0 until arr.length()) {
                        val ep = arr.optJSONObject(i) ?: continue
                        val id = ep.optString("id", ep.optString("episode_id", ""))
                        if (id.isBlank()) continue
                        val ext = ep.optString("container_extension", "mp4")
                        val name = ep.optString("title", ep.optString("name", "Bölüm ${i + 1}"))
                        val stream = "${source.address.trimEnd('/')}/series/$u/$p/$id.$ext"
                        val episodeInfo = ep.optJSONObject("info")
                        val episodeImage = sequenceOf(
                            episodeInfo?.optString("movie_image", "").orEmpty(),
                            episodeInfo?.optString("cover_big", "").orEmpty(),
                            episodeInfo?.optString("cover", "").orEmpty(),
                            ep.optString("movie_image", "")
                        ).firstOrNull { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
                        seasonItems.add(MediaItem(id, name, stream, "${series.name} • Sezon $key", "Bölümler", episodeImage, ext, series.seriesId, source.id))
                    }
                    if (seasonItems.isNotEmpty()) rows.add(Pair(key, seasonItems))
                }
                val sortedRows = rows.sortedWith(compareBy { it.first.toIntOrNull() ?: Int.MAX_VALUE })
                runOnUiThread {
                    if (detailSeries?.favoriteKey != series.favoriteKey) return@runOnUiThread
                    seriesEpisodeRows = sortedRows
                    seriesEpisodesLoading = false
                    if (sortedRows.isEmpty()) seriesEpisodesError = "Bu dizi için bölüm bulunamadı."
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (detailSeries?.favoriteKey != series.favoriteKey) return@runOnUiThread
                    seriesEpisodesLoading = false
                    seriesEpisodesError = "Bölümler yüklenemedi. Kaynağı kontrol edip yeniden dene."
                    render()
                }
            }
        }
    }

    private fun renderChannelPreviewScreen() {
        val title = TextView(this).apply {
            text = "CANLI TV ÖNİZLEME  •  OK: TAM EKRAN"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        content.addView(title)
        val frame = FrameLayout(this).apply {
            background = roundedDrawable(Color.BLACK, 16, Color.rgb(48, 58, 76))
            clipToOutline = true
            isFocusable = true
            isFocusableInTouchMode = true
            contentDescription = "Canlı TV önizlemesi"
            setOnFocusChangeListener { v, hasFocus ->
                v.background = roundedDrawable(
                    Color.BLACK, 16,
                    if (hasFocus) accentBright else Color.rgb(48, 58, 76)
                )
            }
        }
        previewView = PlayerView(this).apply {
            useController = false
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        frame.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        content.addView(frame, LinearLayout.LayoutParams(-1, dp(330)).apply {
            setMargins(dp(8), dp(8), dp(8), dp(8))
        })
        content.addView(TextView(this).apply {
            text = "OK: Tam ekran izle  •  Tam ekranda ↑/↓ kanal değiştir, ← kanal listesi, → son izlenenler"
            textSize = 14f
            setTextColor(muted)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })
        if (previewPlayer != null) previewView?.player = previewPlayer
    }

    private fun startChannelPreview(channel: MediaItem) {
        previewChannel = channel
        if (channel.url.isBlank() || previewUrl == channel.url) return
        previewUrl = channel.url
        try {
            if (previewPlayer == null) {
                previewPlayer = ExoPlayer.Builder(this).build().apply {
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_OFF
                    playWhenReady = true
                }
            }
            previewView?.player = previewPlayer
            previewPlayer?.setMediaItem(PlayerMediaItem.fromUri(channel.url))
            previewPlayer?.prepare()
            previewPlayer?.playWhenReady = true
        } catch (_: Exception) {
            previewUrl = ""
        }
    }

    private fun stopChannelPreview() {
        previewPlayer?.pause()
        previewView?.player = null
        previewView = null
        previewChannel = null
        previewUrl = ""
    }

    private fun showPosterFullscreen(item: MediaItem) {
        fullscreenPoster = item
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = item.name
            setBackgroundColor(Color.BLACK)
        }
        frame.addView(poster, FrameLayout.LayoutParams(-1, -1))
        if (item.icon.startsWith("http://") || item.icon.startsWith("https://")) loadPoster(item.icon, poster)
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), 0x00000000, 0xEE000000.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val backButton = makeButton("‹  GERİ", false) { closePosterFullscreen() }.apply {
            textSize = 15f
            background = roundedDrawable(Color.argb(150, 10, 14, 22), 10, Color.rgb(75, 86, 105))
        }
        frame.addView(backButton, FrameLayout.LayoutParams(dp(112), dp(48), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(24), dp(22), 0, 0)
        })
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(30), dp(24), dp(30), dp(28))
        }
        footer.addView(TextView(this).apply {
            text = item.name
            textSize = 24f
            maxLines = 2
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        footer.addView(makeButton(t("DETAYLAR"), true) {
            closePosterFullscreen()
            if (item.type == "Filmler") showMovieDetail(item) else showSeriesDetail(item)
        }, LinearLayout.LayoutParams(dp(150), dp(46)).apply { setMargins(0, dp(12), 0, 0) })
        frame.addView(footer, FrameLayout.LayoutParams(-1, dp(170), Gravity.BOTTOM))
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        setContentView(frame)
        backButton.requestFocus()
    }

    private fun closePosterFullscreen() {
        if (fullscreenPoster == null) return
        fullscreenPoster = null
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        setContentView(rootLayout)
        render()
    }

    private fun showMovieDetail(movie: MediaItem) {
        detailSeries = null
        detailMovie = movie
        render()
        if (movie.sourceId.isBlank() || (movie.plot.isNotBlank() && movie.rating.isNotBlank())) return
        val source = savedSources.firstOrNull { it.id == movie.sourceId && it.mode == "xtream" } ?: return
        thread {
            try {
                val u = URLEncoder.encode(source.username, "UTF-8")
                val p = URLEncoder.encode(source.password, "UTF-8")
                val api = "${source.address.trimEnd('/')}/player_api.php?username=$u&password=$p&action=get_vod_info&vod_id=${URLEncoder.encode(movie.id, "UTF-8")}"
                val root = JSONObject(request(api))
                val info = root.optJSONObject("info") ?: root.optJSONObject("movie_data") ?: root
                fun pick(vararg keys: String): String {
                    for (key in keys) {
                        val value = info.opt(key)
                        if (value != null && value != JSONObject.NULL) {
                            val valueText = if (value is String) value else value.toString()
                            if (valueText.isNotBlank() && valueText != "0" && valueText != "null") return valueText
                        }
                    }
                    return ""
                }
                val updated = movie.copy(
                    plot = pick("plot", "description", "overview").ifBlank { movie.plot },
                    rating = pick("rating", "vote_average", "rating_5based").ifBlank { movie.rating },
                    releaseDate = pick("releasedate", "release_date", "releaseDate", "year").ifBlank { movie.releaseDate },
                    genre = pick("genre", "genres").ifBlank { movie.genre },
                    actors = pick("cast", "actors").ifBlank { movie.actors }
                )
                runOnUiThread {
                    // Ignore late metadata responses if the user has already returned to the movie list.
                    if (detailMovie?.favoriteKey != movie.favoriteKey) return@runOnUiThread
                    val index = items.indexOfFirst { it.id == movie.id && it.type == movie.type && it.sourceId == movie.sourceId }
                    if (index >= 0) items[index] = updated
                    detailMovie = updated
                    saveCachedItems()
                    render()
                }
            } catch (_: Exception) { }
        }
    }

    private fun renderMovieDetail(movie: MediaItem) {
        // Full-screen movie details. The poster is edge-to-edge on the left;
        // information scrolls independently while the three actions stay pinned below.
        content.setPadding(0, 0, 0, 0)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val posterWidth = (screenWidth * 0.37f).toInt().coerceAtLeast(dp(150))
        val detailHeight = screenHeight.coerceAtLeast(dp(300))

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            clipChildren = true
            clipToPadding = true
        }

        // Frameless, full-height poster: no card, border, or outer margin.
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = movie.name
            setBackgroundColor(Color.BLACK)
        }
        if (movie.icon.startsWith("http://") || movie.icon.startsWith("https://")) {
            loadPoster(movie.icon, poster)
        }
        layout.addView(poster, LinearLayout.LayoutParams(posterWidth, -1))

        val infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(Color.BLACK)
            setPadding(dp(28), dp(22), dp(28), dp(18))
            clipChildren = false
        }

        val infoScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
        }
        info.addView(TextView(this).apply {
            text = t("FİLM")
            textSize = 13f
            letterSpacing = 0.14f
            setTextColor(accentBright)
        })
        info.addView(TextView(this).apply {
            text = movie.name
            textSize = 27f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(7), 0, dp(10))
        })

        val metadata = listOf(
            movie.releaseDate,
            movie.genre,
            movie.rating.takeIf { it.isNotBlank() }?.let { "Puan $it" }
        ).filterNotNull().filter { it.isNotBlank() }.joinToString("   •   ")
        if (metadata.isNotBlank()) info.addView(TextView(this).apply {
            text = metadata
            textSize = 14f
            setTextColor(accentBright)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(18))
        })

        info.addView(TextView(this).apply {
            text = t("KONU")
            textSize = 14f
            letterSpacing = 0.08f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(7))
        })
        info.addView(TextView(this).apply {
            text = movie.plot.ifBlank { t("Bu film için konu bilgisi kaynakta bulunamadı.") }
            textSize = 15f
            setTextColor(Color.rgb(220, 225, 235))
            setPadding(0, 0, 0, dp(18))
        })
        info.addView(TextView(this).apply {
            text = t("OYUNCULAR")
            textSize = 14f
            letterSpacing = 0.08f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(7))
        })
        info.addView(TextView(this).apply {
            text = movie.actors.ifBlank { t("Oyuncu bilgisi kaynakta bulunamadı.") }
            textSize = 14f
            setTextColor(Color.rgb(190, 198, 212))
        })
        infoScroll.addView(info, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        infoColumn.addView(infoScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 3f
            setPadding(0, dp(12), 0, 0)
        }
        val buttonHeight = dp(50)
        val watchButton = makeButton("▶  ${t("FİLMİ İZLE")}", true) { play(movie) }.apply {
            gravity = Gravity.CENTER
            textSize = 13f
            maxLines = 1
            minHeight = buttonHeight
        }
        val trailerButton = makeButton("▷  ${t("FRAGMAN")}", false) {
            val query = java.net.URLEncoder.encode(movie.name + " fragman", "UTF-8")
            val trailerIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=$query"))
            runCatching { startActivity(trailerIntent) }.onFailure {
                Toast.makeText(this@MainActivity, "Fragman açılamadı.", Toast.LENGTH_SHORT).show()
            }
        }.apply {
            gravity = Gravity.CENTER
            textSize = 13f
            maxLines = 1
            minHeight = buttonHeight
        }
        val favoriteButton = makeFavoriteButton(movie).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            maxLines = 1
            minHeight = buttonHeight
        }
        val restartButton = makeButton("↺  ${t("BAŞTAN İZLE")}", false) { play(movie, startOver = true) }.apply {
            gravity = Gravity.CENTER
            textSize = 13f
            maxLines = 1
            minHeight = buttonHeight
        }
        listOf(watchButton, trailerButton, favoriteButton, restartButton).forEachIndexed { index, button ->
            actions.addView(button, LinearLayout.LayoutParams(0, buttonHeight, 1f).apply {
                if (index < 3) setMargins(0, 0, dp(7), 0)
            })
        }
        infoColumn.addView(actions, LinearLayout.LayoutParams(-1, buttonHeight + dp(12)))
        layout.addView(infoColumn, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(layout, LinearLayout.LayoutParams(-1, detailHeight))
        watchButton.post { if (detailMovie?.favoriteKey == movie.favoriteKey) watchButton.requestFocus() }
    }

    private fun showSeriesDropdown(
        panel: LinearLayout,
        options: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        panel.removeAllViews()
        if (options.isEmpty()) {
            panel.visibility = View.GONE
            return
        }
        options.forEachIndexed { index, label ->
            val option = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                setTextColor(Color.WHITE)
                background = roundedDrawable(if (index == selectedIndex) accent else Color.rgb(21, 27, 39), 8)
                setOnFocusChangeListener { view, focused ->
                    view.background = roundedDrawable(if (focused || index == selectedIndex) accent else Color.rgb(21, 27, 39), 8)
                }
                setOnClickListener {
                    panel.visibility = View.GONE
                    onSelected(index)
                }
            }
            panel.addView(option, LinearLayout.LayoutParams(-1, dp(44)).apply {
                if (index > 0) topMargin = dp(4)
            })
        }
        panel.visibility = View.VISIBLE
        panel.post {
            val target = panel.getChildAt(selectedIndex.coerceIn(0, options.lastIndex))
            target?.requestFocus()
        }
    }
    private fun renderSeriesDetail(series: MediaItem) {
        content.setPadding(0, 0, 0, 0)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val posterWidth = (screenWidth * 0.35f).toInt().coerceAtLeast(dp(150))
        val detailHeight = screenHeight.coerceAtLeast(dp(300))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            clipChildren = true
            clipToPadding = true
        }
        val poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = series.name
            setBackgroundColor(Color.BLACK)
        }
        if (series.icon.startsWith("http://") || series.icon.startsWith("https://")) loadPoster(series.icon, poster)
        root.addView(poster, LinearLayout.LayoutParams(posterWidth, -1))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(Color.BLACK)
            setPadding(dp(26), dp(20), dp(24), dp(18))
        }
        val detailsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        details.addView(TextView(this).apply {
            text = t("DİZİ"); textSize = 13f; letterSpacing = 0.12f; setTextColor(accentBright)
        })
        details.addView(TextView(this).apply {
            text = series.name; textSize = 28f; maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(10))
        })
        val seasonCount = seriesEpisodeRows.size
        val metadata = listOf(
            series.releaseDate,
            series.group.takeIf { it.isNotBlank() },
            if (seasonCount > 0) "$seasonCount Sezon" else null,
            series.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }
        ).filterNotNull().filter { it.isNotBlank() }.joinToString("   •   ")
        if (metadata.isNotBlank()) details.addView(TextView(this).apply {
            text = metadata; textSize = 14f; setTextColor(accentBright)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(18))
        })
        details.addView(TextView(this).apply {
            text = t("KONU"); textSize = 14f; letterSpacing = 0.08f
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(7))
        })
        details.addView(TextView(this).apply {
            text = series.plot.ifBlank { "Bu dizi için konu bilgisi kaynakta bulunamadı." }
            textSize = 15f; setTextColor(Color.rgb(220, 225, 235))
            setPadding(0, 0, 0, dp(18))
        })
        details.addView(TextView(this).apply {
            text = t("OYUNCULAR"); textSize = 14f; letterSpacing = 0.08f
            setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(7))
        })
        details.addView(TextView(this).apply {
            text = series.actors.ifBlank { "Oyuncu bilgisi kaynakta bulunamadı." }
            textSize = 14f; setTextColor(Color.rgb(190, 198, 212))
        })
        detailsScroll.addView(details, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        right.addView(detailsScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val allEpisodes = seriesEpisodeRows.flatMap { it.second }
        val seriesKey = series.seriesId.ifBlank { series.id }
        val savedIndex = if (allEpisodes.isNotEmpty()) {
            getSharedPreferences("androtv_playback", MODE_PRIVATE)
                .getInt("last_episode_$seriesKey", 0).coerceIn(0, allEpisodes.lastIndex)
        } else 0

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            weightSum = 3f
            setPadding(0, dp(12), 0, 0)
        }
        val buttonHeight = dp(50)
        lateinit var seasonButton: Button

        val playButton = makeButton("▶  İZLE", true) {
            if (allEpisodes.isEmpty()) {
                Toast.makeText(this@MainActivity, "Bölümler henüz yüklenmedi.", Toast.LENGTH_SHORT).show()
            } else {
                pendingSeriesEpisodes = allEpisodes
                val target = if (selectedEpisodeIndex in allEpisodes.indices) selectedEpisodeIndex else savedIndex
                play(allEpisodes[target])
            }
        }.apply { gravity = Gravity.CENTER; textSize = 14f; maxLines = 1; minHeight = buttonHeight }

        val seasonDropdown = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = roundedDrawable(panel, 10, muted)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val episodeTitle = TextView(this).apply {
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(7))
        }
        val episodeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val episodeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(episodeRow, ViewGroup.LayoutParams(-2, -1))
        }

        fun refreshEpisodeCards() {
            episodeRow.removeAllViews()
            val row = seriesEpisodeRows.firstOrNull { it.first == selectedEpisodeSeason }
                ?: seriesEpisodeRows.firstOrNull()
            if (row == null) return
            val offset = seriesEpisodeRows.takeWhile { it.first != row.first }.sumOf { it.second.size }
            row.second.forEachIndexed { index, episode ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = roundedDrawable(panel, 9, muted)
                    setOnFocusChangeListener { view, focused ->
                        view.background = roundedDrawable(if (focused) accent else panel, 9, if (focused) accent else muted)
                    }
                    setOnClickListener {
                        selectedEpisodeIndex = offset + index
                        pendingSeriesEpisodes = allEpisodes
                        play(episode)
                    }
                }
                val episodePoster = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(8, 10, 14), Color.rgb(14, 18, 24))
                    ).apply { cornerRadius = dp(6).toFloat() }
                }
                card.addView(episodePoster, LinearLayout.LayoutParams(-1, dp(62)))
                if (episode.icon.startsWith("http://") || episode.icon.startsWith("https://")) {
                    loadPoster(episode.icon, episodePoster)
                } else {
                    episodePoster.setImageDrawable(GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(8, 10, 14), Color.rgb(14, 18, 24))
                    ))
                }
                card.addView(TextView(this).apply {
                    text = "${index + 1}. Bölüm"
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(4), 0, dp(1))
                })
                card.addView(TextView(this).apply {
                    text = episode.name
                    textSize = 9f
                    setTextColor(muted)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                episodeRow.addView(card, LinearLayout.LayoutParams(dp(140), dp(108)).apply {
                    if (index > 0) setMargins(dp(6), 0, 0, 0)
                })
            }
        }

        val seasons = seriesEpisodeRows.map { it.first }
        seasonButton = makeButton("▱  ${t("Sezon").uppercase(java.util.Locale.ROOT)} ${selectedEpisodeSeason.ifBlank { seasons.firstOrNull().orEmpty() }}  ▾", false) {
            if (seasons.isNotEmpty()) {
                val current = seasons.indexOf(selectedEpisodeSeason).coerceAtLeast(0)
                if (seasonDropdown.visibility == View.VISIBLE) {
                    seasonDropdown.visibility = View.GONE
                } else {
                    showSeriesDropdown(seasonDropdown, seasons.map { "Sezon $it" }, current) { which ->
                    selectedEpisodeSeason = seasons[which]
                    selectedEpisodeIndex = seriesEpisodeRows.take(which).sumOf { it.second.size }
                    seasonButton.text = "▱  ${t("Sezon").uppercase(java.util.Locale.ROOT)} ${seasons[which]}  ▾"
                    refreshEpisodeCards()
                    }
                }
            }
        }.apply { gravity = Gravity.CENTER; textSize = 14f; maxLines = 1; minHeight = buttonHeight }

        val favoriteButton = makeFavoriteButton(series).apply {
            gravity = Gravity.CENTER; textSize = 14f; maxLines = 1; minHeight = buttonHeight
        }
        listOf(playButton, seasonButton, favoriteButton).forEachIndexed { index, button ->
            actions.addView(button, LinearLayout.LayoutParams(0, buttonHeight, 1f).apply {
                if (index < 2) setMargins(0, 0, dp(8), 0)
            })
        }
        right.addView(actions, LinearLayout.LayoutParams(-1, buttonHeight + dp(12)))
        right.addView(seasonDropdown, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        right.addView(episodeScroll, LinearLayout.LayoutParams(-1, dp(116)))
        refreshEpisodeCards()
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(root, LinearLayout.LayoutParams(-1, detailHeight))

        // On first entry defaults to season 1 / episode 1; later restores last played episode.
        if (allEpisodes.isNotEmpty()) {
            var cursor = 0
            var savedRow = seriesEpisodeRows.first()
            seriesEpisodeRows.forEach { row ->
                if (savedIndex >= cursor && savedIndex < cursor + row.second.size) savedRow = row
                cursor += row.second.size
            }
            selectedEpisodeSeason = savedRow.first
            selectedEpisodeIndex = savedIndex
        } else if (selectedEpisodeSeason.isBlank()) {
            selectedEpisodeSeason = seriesEpisodeRows.firstOrNull()?.first.orEmpty()
        }
        playButton.post { if (detailSeries?.favoriteKey == series.favoriteKey) playButton.requestFocus() }
    }

    private fun renderSettingsScreen() {
        // Settings is a dedicated full-screen workspace: hide the entire left navigation rail.
        if (::sidebar.isInitialized) sidebar.visibility = View.GONE
        mainHeader.visibility = View.GONE
        if (::dateClockRow.isInitialized) dateClockRow.visibility = View.GONE
        status.visibility = View.GONE
        categoryScroll.visibility = View.GONE
        mainContentScroll.visibility = View.VISIBLE
        content.removeAllViews()
        mainContentScroll.scrollTo(0, 0)
        fun heading(title: String, subtitle: String) {
            content.addView(TextView(this).apply {
                text = t(title); textSize = 27f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE); setPadding(dp(8), dp(16), dp(8), dp(4))
            })
            content.addView(TextView(this).apply {
                text = t(subtitle); textSize = 14f; setTextColor(muted); setPadding(dp(8), 0, dp(8), dp(16))
            })
        }
        var settingsGridRow: LinearLayout? = null
        var settingsGridCount = 0
        fun option(title: String, detail: String, action: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = roundedDrawable(panel, 16)
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = roundedDrawable(if (hasFocus) accent else panel, 16)
                    v.elevation = 0f
                    val cardRow = v as LinearLayout
                    val iconView = cardRow.getChildAt(0) as? TextView
                    val textColumn = cardRow.getChildAt(1) as? LinearLayout
                    iconView?.setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else accentBright)
                    textColumn?.let { column ->
                        (column.getChildAt(0) as? TextView)?.setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
                        (column.getChildAt(1) as? TextView)?.setTextColor(if (hasFocus) Color.rgb(25, 38, 54) else muted)
                    }
                }
                setOnClickListener { action() }
            }
            val contentIcon = when (title) {
                "Kaynak Yönetimi" -> "▤"
                "Dil / Language" -> "文"
                "İçerik Ayarları" -> "▦"
                "Tema ve Görünüm" -> "◈"
                "İzleme Geçmişi" -> "◷"
                else -> "◆"
            }
            card.addView(TextView(this).apply {
                text = contentIcon
                textSize = 34f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(accentBright)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, 0, dp(8)) })
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            textColumn.addView(TextView(this).apply {
                text = t(title); textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
            })
            textColumn.addView(TextView(this).apply {
                text = t(detail); textSize = 14f; setTextColor(muted); setPadding(0, dp(8), 0, 0)
            })
            card.addView(textColumn)
            if (settingsPage == "main") {
                if (settingsGridRow == null || settingsGridCount % 3 == 0) {
                    settingsGridRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.TOP
                    }
                    content.addView(settingsGridRow, LinearLayout.LayoutParams(-1, dp(142)).apply {
                        setMargins(dp(6), dp(7), dp(6), dp(7))
                    })
                }
                settingsGridRow?.addView(card, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    setMargins(dp(5), 0, dp(5), 0)
                })
                settingsGridCount++
            } else {
                content.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(6), dp(5), dp(6), dp(7))
                })
            }
        }
        when (settingsPage) {
            "main" -> {
                heading("Ayarlar", "Bir bölümü seç; ayrı ekranda açılacak.")
                option("Kaynak Yönetimi", "M3U / Xtream kaynak ekle, düzenle") { settingsPage = "sources"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("İçerik Ayarları", "Kategoriler, gizleme ve sıralama") { settingsPage = "categories"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("Tema ve Görünüm", "Renk, arka plan ve arayüz stili") { settingsPage = "themes"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("İzleme Geçmişi", "Son izlenen içerikleri görüntüle") { settingsPage = "history"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("Hakkında", "Uygulama sürümü ve teknik bilgiler") { settingsPage = "about"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
                option("Dil / Language", "Uygulama dilini seç") { settingsPage = "language"; prefs.edit().putString("last_settings_page", settingsPage).apply(); render() }
            }
            "language" -> {
                val currentLanguage = prefs.getString("app_language", "system") ?: "system"
                val languageOptions = listOf(
                    Triple("system", t("Cihaz dili"), "🌐"),
                    Triple("tr", "Türkçe", "🇹🇷"),
                    Triple("en", "English", "🇬🇧"),
                    Triple("de", "Deutsch", "🇩🇪"),
                    Triple("fr", "Français", "🇫🇷"),
                    Triple("it", "Italiano", "🇮🇹"),
                    Triple("es", "Español", "🇪🇸"),
                    Triple("ru", "Русский", "🇷🇺"),
                    Triple("ar", "العربية", "🇸🇦")
                )
                heading(t("Dil"), t("Uygulama dili") + ": " + (languageOptions.firstOrNull { it.first == currentLanguage }?.second ?: "Cihaz dili"))
                languageOptions.chunked(3).forEach { languageRow ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    languageRow.forEach { entry ->
                        val selected = entry.first == currentLanguage
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(dp(5), dp(13), dp(5), dp(13))
                            background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                cornerRadius = dp(12).toFloat()
                                setColor(panel)
                                setStroke(dp(if (selected) 2 else 1), if (selected) accent else 0x553B4658)
                            }
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isClickable = true
                            setOnFocusChangeListener { v, focused ->
                                v.background = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                    cornerRadius = dp(12).toFloat()
                                    setColor(panel)
                                    setStroke(dp(if (focused || selected) 2 else 1), if (focused || selected) accent else 0x553B4658)
                                }
                                v.scaleX = if (focused) 1.035f else 1f
                                v.scaleY = if (focused) 1.035f else 1f
                            }
                            setOnClickListener {
                                prefs.edit().putString("app_language", entry.first).apply()
                                val locale = if (entry.first == "system") java.util.Locale.getDefault()
                                    else java.util.Locale.forLanguageTag(entry.first)
                                java.util.Locale.setDefault(locale)
                                recreate()
                            }
                        }
                        card.addView(TextView(this@MainActivity).apply {
                            text = entry.third
                            textSize = 27f
                            gravity = Gravity.CENTER
                            setPadding(0, 0, 0, dp(7))
                        })
                        card.addView(TextView(this@MainActivity).apply {
                            text = entry.second
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            maxLines = 2
                        })
                        if (selected) card.addView(TextView(this@MainActivity).apply {
                            text = "✓"
                            textSize = 12f
                            gravity = Gravity.CENTER
                            setTextColor(accentBright)
                            setPadding(0, dp(4), 0, 0)
                        })
                        row.addView(card, LinearLayout.LayoutParams(0, dp(116), 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(5))
                        })
                    }
                    content.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
            "sources" -> {
                // The source form supplies the page title and subtitle; avoid a duplicate heading.
                // Keep the source-entry form fixed inside the Sources page; do not show a duplicate launch card.
                showConnectionDialog()
                if (savedSources.isEmpty()) {
                    content.addView(TextView(this).apply {
                        text = t("Henüz kayıtlı kaynak yok.")
                        textSize = 14f
                        setTextColor(muted)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    })
                } else {
                    savedSources.toList().forEach { source ->
                        val sourceRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(6), dp(8), dp(6))
                            // Transparent source container with a visible outline.
                            background = roundedDrawable(
                                Color.TRANSPARENT,
                                0,
                                Color.rgb(48, 57, 73)
                            )
                        }
                        val sourceInfo = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        sourceInfo.addView(TextView(this).apply {
                            text = source.name
                            textSize = 16f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.WHITE)
                        })
                        sourceInfo.addView(TextView(this).apply {
                            text = "${if (source.mode == "xtream") "Xtream Codes" else "M3U/M3U8"} • ${source.address}"
                            textSize = 11f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setTextColor(muted)
                            setPadding(0, dp(2), 0, 0)
                        })
                        sourceRow.addView(sourceInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val selectedIds = (prefs.getStringSet("selected_source_ids", emptySet()) ?: emptySet()).toMutableSet()
                        if (selectedIds.isEmpty()) {
                            prefs.getString("last_source_id", "")?.takeIf { id -> savedSources.any { it.id == id } }?.let { selectedIds.add(it) }
                        }
                        val sourceSelect = Switch(this).apply {
                            showText = false
                            text = ""
                            contentDescription = "${source.name} kaynağını etkinleştir"
                            isFocusable = true
                            isChecked = selectedIds.contains(source.id)
                            thumbTintList = android.content.res.ColorStateList(
                                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                intArrayOf(accentBright, Color.rgb(145, 153, 168))
                            )
                            trackTintList = android.content.res.ColorStateList(
                                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                intArrayOf(accent, Color.rgb(65, 72, 84))
                            )
                            setOnCheckedChangeListener { _, checked ->
                                // Only one source can be active at a time. Selecting this
                                // source immediately deselects every other saved source.
                                val activeIds = if (checked) setOf(source.id) else emptySet()
                                prefs.edit()
                                    .putStringSet("selected_source_ids", activeIds)
                                    .putString("last_source_id", if (checked) source.id else "")
                                    .apply()
                                if (checked) {
                                    selectedCategorySourceId = source.id
                                    selectedGroup = "Tüm kategoriler"
                                    expandedCategory = null
                                    focusedCategoryName = null
                                    if (source.mode == "xtream") loadXtream(source, false)
                                    else if (source.mode == "mac") {
                                        Toast.makeText(this@MainActivity, "MAC Portal yayın bağlantısı henüz etkin değil.", Toast.LENGTH_LONG).show()
                                        render()
                                    } else loadM3u(source, false)
                                } else {
                                    selectedCategorySourceId = ""
                                    items.clear()
                                    saveCachedItems()
                                    render()
                                }
                            }
                        }
                        sourceRow.addView(sourceSelect, LinearLayout.LayoutParams(dp(54), dp(42)))
                        sourceRow.addView(makeButton(t("Yenile"), false) {
                            if (source.mode == "xtream") loadXtream(source, true) else loadM3u(source, true)
                        }, LinearLayout.LayoutParams(dp(84), dp(42)).apply {
                            setMargins(dp(2), 0, dp(4), 0)
                        })
                        sourceRow.addView(makeButton(t("Düzenle"), false) {
                            showConnectionDialog(source)
                        }, LinearLayout.LayoutParams(dp(92), dp(42)).apply {
                            setMargins(dp(4), 0, dp(4), 0)
                        })
                        sourceRow.addView(makeButton(t("Sil"), false) {
                            val deletePanel = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(26), dp(22), dp(26), dp(22))
                                background = roundedDrawable(Color.rgb(9, 15, 25), 18, accent)
                            }
                            val deleteTop = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            }
                            val deleteIcon = TextView(this).apply {
                                text = "▤"
                                textSize = 38f
                                gravity = Gravity.CENTER
                                setTextColor(Color.WHITE)
                                background = roundedDrawable(Color.rgb(14, 31, 50), 14, accent)
                            }
                            deleteTop.addView(deleteIcon, LinearLayout.LayoutParams(dp(76), dp(76)))
                            val deleteDivider = View(this).apply { setBackgroundColor(Color.argb(150, 125, 151, 181)) }
                            deleteTop.addView(deleteDivider, LinearLayout.LayoutParams(dp(2), dp(82)).apply {
                                setMargins(dp(20), 0, dp(20), 0)
                            })
                            val deleteCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                            deleteCopy.addView(TextView(this).apply {
                                text = t("Kaynağı sil")
                                textSize = 25f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(Color.WHITE)
                            })
                            deleteCopy.addView(TextView(this).apply {
                                text = t("Kaynak ve içerikleri silinsin mi?")
                                textSize = 17f
                                setTextColor(muted)
                                setPadding(0, dp(9), 0, dp(5))
                            })
                            deleteCopy.addView(TextView(this).apply {
                                text = source.name
                                textSize = 19f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(accentBright)
                            })
                            deleteTop.addView(deleteCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            deletePanel.addView(deleteTop, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                            val deleteActions = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                            }
                            val deleteDialog = AlertDialog.Builder(this).setView(deletePanel).create()
                            fun deleteAction(label: String, primary: Boolean, action: () -> Unit): TextView =
                                TextView(this).apply {
                                    text = t(label)
                                    textSize = 18f
                                    gravity = Gravity.CENTER
                                    setTypeface(null, android.graphics.Typeface.BOLD)
                                    setTextColor(if (primary) Color.WHITE else Color.WHITE)
                                    isFocusable = true
                                    isFocusableInTouchMode = true
                                    isClickable = true
                                    background = roundedDrawable(if (primary) accent else Color.rgb(19, 29, 43), 12, if (primary) accentBright else Color.rgb(53, 68, 88))
                                    setOnFocusChangeListener { v, focused ->
                                        v.background = roundedDrawable(if (focused || primary) accent else Color.rgb(19, 29, 43), 12, if (focused || primary) accentBright else Color.rgb(53, 68, 88))
                                        (v as TextView).setTextColor(Color.WHITE)
                                    }
                                    setOnClickListener { action() }
                                }
                            val cancelDelete = deleteAction("İptal", false) { deleteDialog.dismiss() }
                            val confirmDelete = deleteAction("Sil", true) {
                                deleteDialog.dismiss()
                                savedSources.removeAll { it.id == source.id }
                                persistSources()
                                items.removeAll { it.sourceId == source.id }
                                render()
                            }
                            deleteActions.addView(cancelDelete, LinearLayout.LayoutParams(dp(150), dp(54)).apply { setMargins(0, 0, dp(12), 0) })
                            deleteActions.addView(confirmDelete, LinearLayout.LayoutParams(dp(150), dp(54)))
                            deletePanel.addView(deleteActions, LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(24), 0, 0) })
                            deleteDialog.setOnShowListener {
                                deleteDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                                deleteDialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                                deleteDialog.window?.setDimAmount(0.78f)
                                deleteDialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.68f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                                cancelDelete.post { cancelDelete.requestFocus() }
                            }
                            deleteDialog.show()
                        }, LinearLayout.LayoutParams(dp(68), dp(42)).apply {
                            setMargins(dp(4), 0, 0, 0)
                        })
                        content.addView(sourceRow, LinearLayout.LayoutParams(-1, dp(70)).apply {
                            setMargins(dp(6), dp(3), dp(6), dp(3))
                        })
                    }
                }
            }
            "categories" -> {
                heading("Kategoriler", "TV, film ve dizi kategorilerini ayrı ayrı yönet")

                // Toplu işlemler tüm üç içerik türüne uygulanır.
                val sectionTypes = listOf(
                    Triple("Canlı TV", "▣", "TV"),
                    Triple("Filmler", "▤", "Film"),
                    Triple("Diziler", "▱", "Dizi")
                )
                val categoryActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(10))
                }
                val actionItems = listOf("Hepsini gizle", "Hepsini göster", "Seçili dilin kategorilerini göster")
                actionItems.forEach { actionLabel ->
                    val actionButton = TextView(this).apply {
                        text = t(actionLabel)
                        textSize = 12f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setPadding(dp(5), dp(11), dp(5), dp(11))
                        background = roundedDrawable(panel, 10)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        setOnFocusChangeListener { v, hasFocus ->
                            v.background = roundedDrawable(if (hasFocus) accent else panel, 10)
                            (v as TextView).setTextColor(if (hasFocus) Color.rgb(8, 13, 22) else Color.WHITE)
                        }
                        setOnClickListener {
                            when (actionLabel) {
                                "Hepsini gizle" -> sectionTypes.forEach { section ->
                                    items.filter { it.type == section.first }.map { it.group.ifBlank { "Diğer" } }.distinct()
                                        .forEach { hiddenCategories.add(section.first + "|" + it) }
                                }
                                "Hepsini göster" -> sectionTypes.forEach { section ->
                                    items.filter { it.type == section.first }.map { it.group.ifBlank { "Diğer" } }.distinct()
                                        .forEach { hiddenCategories.remove(section.first + "|" + it) }
                                }
                                "Seçili dilin kategorilerini göster" -> {
                                    val selectedLanguage = prefs.getString("app_language", "system") ?: "system"
                                    val language = if (selectedLanguage == "system") java.util.Locale.getDefault().language else selectedLanguage
                                    val languagePrefix = when (language.lowercase(java.util.Locale.ROOT)) {
                                        "tr" -> "TR"; "en" -> "EN"; "de" -> "DE"; "fr" -> "FR"
                                        "it" -> "IT"; "es" -> "ES"; "ru" -> "RU"; "ar" -> "AR"
                                        else -> language.uppercase(java.util.Locale.ROOT)
                                    }
                                    sectionTypes.forEach { section ->
                                        items.filter { it.type == section.first }.map { it.group.ifBlank { "Diğer" } }.distinct()
                                            .forEach { group ->
                                                val normalized = group.trim()
                                                val matches = normalized.startsWith(languagePrefix, ignoreCase = true) &&
                                                    (normalized.length == languagePrefix.length ||
                                                     normalized[languagePrefix.length] in charArrayOf(' ', '|', '-', '_'))
                                                val key = section.first + "|" + group
                                                if (matches) hiddenCategories.remove(key) else hiddenCategories.add(key)
                                            }
                                    }
                                }
                            }
                            prefs.edit().putStringSet("hidden_categories", hiddenCategories.toSet()).apply()
                            render()
                        }
                    }
                    categoryActions.addView(actionButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    })
                }
                content.addView(categoryActions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

                // TV, Film ve Dizi kategorileri aynı satırda üç sütun olarak gösterilir.
                val categoryColumns = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                sectionTypes.forEach { section ->
                    val sectionType = section.first
                    val column = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(5), dp(8), dp(5), dp(8))
                        background = roundedDrawable(panel, 12, Color.rgb(65, 92, 120))
                    }
                    column.addView(TextView(this).apply {
                        text = "${section.second}  ${t(section.third)}"
                        textSize = 16f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(accentBright)
                        gravity = Gravity.CENTER
                        setPadding(dp(2), dp(8), dp(2), dp(12))
                    })
                    val groups = items.filter { it.type == sectionType }
                        .map { it.group.ifBlank { "Diğer" } }.distinct().sorted()
                    if (groups.isEmpty()) {
                        column.addView(TextView(this).apply {
                            text = "Kategori yok"
                            textSize = 12f
                            setTextColor(muted)
                            gravity = Gravity.CENTER
                            setPadding(dp(2), dp(8), dp(2), dp(8))
                        })
                    } else {
                        groups.forEach { group ->
                            val hiddenKey = sectionType + "|" + group
                            val isHidden = hiddenCategories.contains(hiddenKey)
                            val card = LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(10), dp(8), dp(10), dp(8))
                                background = roundedDrawable(if (isHidden) Color.rgb(
                                    (Color.red(panel) - 4).coerceAtLeast(0),
                                    (Color.green(panel) - 4).coerceAtLeast(0),
                                    (Color.blue(panel) - 4).coerceAtLeast(0)
                                ) else panel, 8, Color.rgb(65, 92, 120))
                                isFocusable = true
                                isFocusableInTouchMode = true
                                isClickable = true
                                setOnFocusChangeListener { v, focused ->
                                    v.background = roundedDrawable(
                                        if (focused) accent else panel,
                                        8,
                                        if (focused) accentBright else Color.rgb(65, 92, 120)
                                    )
                                    val fg = if (focused) Color.rgb(8, 13, 22) else Color.WHITE
                                    (v as ViewGroup).let { box ->
                                        (box.getChildAt(0) as? TextView)?.setTextColor(fg)
                                        (box.getChildAt(1) as? TextView)?.let { toggle ->
                                            toggle.background = roundedDrawable(
                                                if (focused) Color.rgb(8, 13, 22) else if (isHidden) Color.rgb(55, 62, 74) else accent,
                                                20
                                            )
                                            toggle.setTextColor(if (focused) accentBright else Color.WHITE)
                                        }
                                    }
                                }
                                setOnClickListener {
                                    if (hiddenCategories.contains(hiddenKey)) hiddenCategories.remove(hiddenKey)
                                    else hiddenCategories.add(hiddenKey)
                                    categoryFocusRestoreKey = hiddenKey
                                    prefs.edit().putStringSet("hidden_categories", hiddenCategories.toSet()).apply()
                                    render()
                                }
                            }
                            card.addView(TextView(this@MainActivity).apply {
                                text = group
                                textSize = 12f
                                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                                setTextColor(Color.WHITE)
                                maxLines = 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                            }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                                setMargins(0, 0, dp(6), 0)
                            })
                            val categorySwitch = Switch(this@MainActivity).apply {
                                showText = false
                                text = ""
                                contentDescription = "$group kategorisini ${if (isHidden) "göster" else "gizle"}"
                                isFocusable = false
                                isClickable = false
                                isChecked = !isHidden
                                thumbTintList = android.content.res.ColorStateList(
                                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                    intArrayOf(accentBright, Color.rgb(145, 153, 168))
                                )
                                trackTintList = android.content.res.ColorStateList(
                                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                    intArrayOf(accent, Color.rgb(65, 72, 84))
                                )
                            }
                            card.addView(categorySwitch, LinearLayout.LayoutParams(dp(54), dp(34)))
                            column.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                setMargins(0, dp(3), 0, dp(4))
                            })
                            if (categoryFocusRestoreKey == hiddenKey) {
                                card.post { card.requestFocus(); categoryFocusRestoreKey = null }
                            }
                        }
                    }
                    categoryColumns.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    })
                }
                content.addView(categoryColumns, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            "themes" -> {
                heading("Temalar", "Renk seçimi anında uygulanır.")
                val themes = listOf(
                    Triple("Antrasit • Mavi", Color.rgb(17, 20, 25), Color.rgb(0, 166, 255)),
                    Triple("Gece • Altın", Color.rgb(15, 16, 20), Color.rgb(224, 184, 95)),
                    Triple("Gece • Buzul", Color.rgb(16, 24, 32), Color.rgb(84, 198, 217)),
                    Triple("Orman • Zümrüt", Color.rgb(16, 27, 24), Color.rgb(32, 201, 151)),
                    Triple("Gece • Yakut", Color.rgb(25, 20, 23), Color.rgb(240, 100, 121)),
                    Triple("Gece • Lavanta", Color.rgb(23, 21, 29), Color.rgb(167, 139, 250))
                )
                val selectedTheme = prefs.getString("theme_name", "Antrasit • Mavi")
                themes.chunked(3).forEach { themeRow ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    themeRow.forEach { theme ->
                        val isSelected = selectedTheme == theme.first
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(12), dp(10), dp(12))
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isClickable = true
                            background = GradientDrawable().apply {
                                cornerRadius = dp(if (ovalButtons) 28 else 12).toFloat()
                                setColor(theme.second)
                                setStroke(dp(if (isSelected) 3 else 1), if (isSelected) theme.third else 0x553B4658)
                            }
                            setOnFocusChangeListener { v, focused ->
                                v.background = GradientDrawable().apply {
                                    cornerRadius = dp(if (ovalButtons) 28 else 12).toFloat()
                                    setColor(theme.second)
                                    setStroke(dp(if (focused || isSelected) 3 else 1), if (focused || isSelected) theme.third else 0x553B4658)
                                }
                                v.scaleX = if (focused) 1.02f else 1f
                                v.scaleY = if (focused) 1.02f else 1f
                            }
                            setOnClickListener {
                                bg = theme.second
                                panel = Color.rgb(8, 10, 14)
                                accent = theme.third
                                accentBright = theme.third
                                prefs.edit().putString("theme_name", theme.first).apply()
                                applyThemeSurfaces()
                                settingsPage = "themes"
                                render()
                            }
                        }
                        card.addView(TextView(this).apply {
                            text = "◆"
                            textSize = 25f
                            setTextColor(theme.third)
                            gravity = Gravity.CENTER
                            setPadding(0, 0, 0, dp(8))
                        })
                        card.addView(TextView(this).apply {
                            text = t(theme.first)
                            textSize = 16f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(Color.WHITE)
                            maxLines = 2
                        })
                        card.addView(TextView(this).apply {
                            text = if (isSelected) "✓  ${t("Seçili tema")}" else t("Zemin ve vurgu rengi")
                            textSize = 12f
                            setTextColor(theme.third)
                            setPadding(0, dp(5), 0, 0)
                        })
                        row.addView(card, LinearLayout.LayoutParams(0, dp(142), 1f).apply {
                            setMargins(dp(4), dp(5), dp(4), dp(7))
                        })
                    }
                    content.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
                }

                val settingsRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                fun settingCard(
                    title: String,
                    subtitle: String,
                    first: String,
                    second: String,
                    firstSelected: Boolean,
                    onFirst: () -> Unit,
                    onSecond: () -> Unit
                ): LinearLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isClickable = true
                    val outline = Color.rgb(48, 57, 73)
                    background = roundedDrawable(panel, 12, outline)
                    addView(TextView(this@MainActivity).apply {
                        text = title
                        textSize = 17f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                    })
                    val detailRow = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(7), 0, 0)
                    }
                    detailRow.addView(TextView(this@MainActivity).apply {
                        text = subtitle
                        textSize = 12f
                        setTextColor(muted)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    val state = TextView(this@MainActivity).apply {
                        text = if (firstSelected) first else second
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setPadding(dp(12), dp(7), dp(12), dp(7))
                        setTextColor(Color.rgb(8, 13, 22))
                        background = roundedDrawable(accent, if (ovalButtons) 24 else 0)
                    }
                    detailRow.addView(state, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
                    addView(detailRow)
                    fun updateFocus(focused: Boolean) {
                        background = roundedDrawable(panel, 12, if (focused) accentBright else outline)
                        state.background = roundedDrawable(
                            if (focused || firstSelected) accent else Color.rgb(35, 42, 54),
                            if (ovalButtons) 24 else 0,
                            if (focused || firstSelected) accent else outline
                        )
                        state.setTextColor(if (focused || firstSelected) Color.rgb(8, 13, 22) else Color.WHITE)
                        state.text = if (firstSelected) first else second
                    }
                    setOnFocusChangeListener { _, focused -> updateFocus(focused) }
                    setOnClickListener { if (firstSelected) onSecond() else onFirst() }
                    updateFocus(false)
                }
                settingsRow.addView(settingCard(
                    t("Menü"), t("Şeffaf Menü"), "Açık", "Kapalı", transparentMenu,
                    {
                        transparentMenu = true
                        prefs.edit().putBoolean("transparent_menu", true).apply()
                        sidebar.setBackgroundColor(Color.TRANSPARENT)
                        render()
                    },
                    {
                        transparentMenu = false
                        prefs.edit().putBoolean("transparent_menu", false).apply()
                        sidebar.setBackgroundColor(panel)
                        render()
                    }
                ), LinearLayout.LayoutParams(0, dp(142), 1f).apply { setMargins(dp(4), dp(10), dp(4), dp(6)) })
                settingsRow.addView(settingCard(
                    t("Buton"), t("Köşe biçimi"), "Köşeli", "Oval", !ovalButtons,
                    {
                        ovalButtons = false
                        prefs.edit().putBoolean("oval_buttons", false).apply()
                        render()
                    },
                    {
                        ovalButtons = true
                        prefs.edit().putBoolean("oval_buttons", true).apply()
                        render()
                    }
                ), LinearLayout.LayoutParams(0, dp(142), 1f).apply { setMargins(dp(4), dp(10), dp(4), dp(6)) })
                settingsRow.addView(settingCard(
                    t("Arkaplan"), t("Zemin görünümü"), "Düz", "Gradyan", !gradientBackground,
                    {
                        gradientBackground = false
                        prefs.edit().putBoolean("gradient_background", false).apply()
                        applyThemeSurfaces()
                        render()
                    },
                    {
                        gradientBackground = true
                        prefs.edit().putBoolean("gradient_background", true).apply()
                        applyThemeSurfaces()
                        render()
                    }
                ), LinearLayout.LayoutParams(0, dp(142), 1f).apply { setMargins(dp(4), dp(10), dp(4), dp(6)) })
                content.addView(settingsRow, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            "about" -> {
                content.setBackgroundColor(Color.rgb(10, 13, 19))
                val aboutPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(28), dp(24), dp(28), dp(24))
                    background = roundedDrawable(Color.rgb(10, 13, 19), 18)
                }
                val logo = android.text.SpannableString("▶ KAISER TV+")
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 2, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 174, 255)), 9, 11, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.RelativeSizeSpan(0.72f), 11, 12, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                logo.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 196, 0)), 11, 12, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                aboutPanel.addView(TextView(this).apply {
                    text = logo
                    textSize = 34f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(34))
                })
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                val version = packageInfo.versionName ?: "Bilinmiyor"
                val buildDate = try {
                    java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("tr", "TR")).format(java.util.Date(packageInfo.lastUpdateTime))
                } catch (_: Exception) { "Bilinmiyor" }
                val appSize = try {
                    val bytes = java.io.File(applicationInfo.sourceDir).length()
                    String.format(java.util.Locale("tr", "TR"), "%.1f MB", bytes / (1024.0 * 1024.0))
                } catch (_: Exception) { "Bilinmiyor" }
                listOf(t("Sürüm") to version, t("Derleme tarihi") to buildDate, t("Uygulama boyutu") to appSize).forEach { item ->
                    val infoRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                    }
                    infoRow.addView(TextView(this).apply {
                        text = item.first
                        textSize = 17f
                        setTextColor(muted)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    infoRow.addView(TextView(this).apply {
                        text = item.second
                        textSize = 17f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.END
                    })
                    aboutPanel.addView(infoRow)
                }
                content.addView(aboutPanel, LinearLayout.LayoutParams(-1, -1).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                })
            }
            "history" -> {
                heading("Geçmiş", t("Daha önce izlediğin içerikler"))
                val selectedHistoryKeys = mutableSetOf<String>()
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(10))
                }
                val selectDelete = makeButton(t("Seçileni Sil"), true) {
                    val keys = selectedHistoryKeys.toSet()
                    if (keys.isEmpty()) {
                        Toast.makeText(this, t("Önce içerik seç."), Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this)
                            .setMessage("${keys.size} içerik silinsin mi?")
                            .setNegativeButton(t("İptal"), null)
                            .setPositiveButton(t("Sil")) { _, _ ->
                                recent.removeAll { keys.contains(it.favoriteKey) }
                                persistRecentHistory()
                                render()
                            }.show()
                    }
                }
                val clearAll = makeButton(t("Geçmişi Sil"), false) {
                    if (recent.isEmpty()) {
                        Toast.makeText(this, t("Geçmiş zaten boş."), Toast.LENGTH_SHORT).show()
                    } else {
                        AlertDialog.Builder(this)
                            .setMessage(t("Tüm izleme geçmişi silinsin mi?"))
                            .setNegativeButton(t("İptal"), null)
                            .setPositiveButton(t("Geçmişi Sil")) { _, _ ->
                                recent.clear()
                                persistRecentHistory()
                                render()
                            }.show()
                    }
                }
                actions.addView(selectDelete, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { setMargins(dp(4), 0, dp(6), 0) })
                actions.addView(clearAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { setMargins(dp(4), 0, dp(4), 0) })
                content.addView(actions, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

                val historyColumns = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                val historySections = listOf(
                    Triple("Canlı TV", "▣", "TV"),
                    Triple("Filmler", "▰", "Filmler"),
                    Triple("Diziler", "▤", "Diziler")
                )
                historySections.forEach { section ->
                    val sectionType = section.first
                    val entries = recent.filter { it.type == sectionType }
                    val column = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                        background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    }
                    column.addView(TextView(this).apply {
                        text = "${section.second}  ${t(section.third)}"
                        textSize = 21f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(accentBright)
                        gravity = Gravity.CENTER
                        setPadding(dp(2), dp(12), dp(2), dp(12))
                    }, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(5) })
                    if (entries.isEmpty()) {
                        column.addView(TextView(this).apply {
                            text = t("Henüz içerik yok")
                            textSize = 13f
                            setTextColor(muted)
                            gravity = Gravity.CENTER
                            setPadding(dp(3), dp(10), dp(3), dp(10))
                        })
                    }
                    entries.forEach { item ->
                        val row = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(7), dp(5), dp(7), dp(5))
                            isFocusable = true
                            isFocusableInTouchMode = true
                            isClickable = true
                        }
                        val check = CheckBox(this).apply {
                            isClickable = false
                            isFocusable = false
                            buttonTintList = android.content.res.ColorStateList(
                                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                                intArrayOf(accentBright, muted)
                            )
                            isChecked = false
                        }
                        row.addView(check, LinearLayout.LayoutParams(dp(34), dp(38)))
                        row.addView(TextView(this).apply {
                            text = item.name
                            textSize = 14f
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER_VERTICAL
                            setTextColor(Color.WHITE)
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        fun updateSelection(focused: Boolean = row.hasFocus()) {
                            val selected = selectedHistoryKeys.contains(item.favoriteKey)
                            check.isChecked = selected
                            row.background = roundedDrawable(
                                if (selected) panel else if (focused) panel else Color.TRANSPARENT,
                                if (ovalButtons) 12 else 5,
                                if (selected || focused) accent else Color.rgb(48, 57, 73)
                            )
                        }
                        row.setOnFocusChangeListener { _, focused -> updateSelection(focused) }
                        row.setOnClickListener {
                            if (!selectedHistoryKeys.add(item.favoriteKey)) selectedHistoryKeys.remove(item.favoriteKey)
                            updateSelection()
                        }
                        row.setOnLongClickListener {
                            if (item.type == "Diziler" && item.url.isBlank()) showSeriesDetail(item) else play(item)
                            true
                        }
                        column.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            setMargins(0, 0, 0, dp(5))
                        })
                    }
                    historyColumns.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), 0, dp(3), 0)
                    })
                }
                content.addView(historyColumns, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }

    private fun posterFile(address: String): java.io.File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(address.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val directory = java.io.File(filesDir, "poster_cache")
        if (!directory.exists()) directory.mkdirs()
        return java.io.File(directory, "$digest.img")
    }

    private fun decodePoster(bytes: ByteArray): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > 360 || bounds.outHeight / sampleSize > 540) sampleSize *= 2
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun trimPosterDiskCache(maxBytes: Long = 300L * 1024L * 1024L) {
        try {
            val directory = java.io.File(filesDir, "poster_cache")
            val files = directory.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total > maxBytes) {
                files.sortedBy { it.lastModified() }.forEach { file ->
                    if (total > maxBytes) {
                        val size = file.length()
                        if (file.delete()) total -= size
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("KaiserTV", "Poster cache cleanup failed", e)
        }
    }

    private fun loadPoster(address: String, target: ImageView) {
        if (address.isBlank()) return
        target.tag = address
        posterCache.get(address)?.let {
            target.setImageBitmap(it)
            return
        }

        val waiting = CopyOnWriteArrayList<WeakReference<ImageView>>()
        waiting.add(WeakReference(target))
        val existing = posterRequests.putIfAbsent(address, waiting)
        if (existing != null) {
            existing.add(WeakReference(target))
            return
        }

        posterExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                val diskFile = posterFile(address)
                if (diskFile.isFile && diskFile.length() > 0L) {
                    try {
                        bitmap = decodePoster(diskFile.readBytes())
                        if (bitmap == null) diskFile.delete()
                        else diskFile.setLastModified(System.currentTimeMillis())
                    } catch (e: Exception) {
                        android.util.Log.w("KaiserTV", "Cached poster unreadable; redownloading", e)
                        diskFile.delete()
                    }
                }

                if (bitmap == null) {
                    val conn = (URL(address).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12000
                        readTimeout = 12000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                        setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                        setRequestProperty("Connection", "keep-alive")
                        useCaches = true
                    }
                    val bytes = try {
                        conn.inputStream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            var total = 0
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > 8 * 1024 * 1024) throw java.io.IOException("Poster image too large")
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    } finally {
                        conn.disconnect()
                    }

                    bitmap = decodePoster(bytes)
                    if (bitmap != null) {
                        try {
                            val tempFile = java.io.File(diskFile.parentFile, diskFile.name + ".tmp")
                            tempFile.writeBytes(bytes)
                            if (!tempFile.renameTo(diskFile)) {
                                diskFile.writeBytes(bytes)
                                tempFile.delete()
                            }
                            trimPosterDiskCache()
                        } catch (e: Exception) {
                            android.util.Log.w("KaiserTV", "Could not persist poster cache", e)
                        }
                    }
                }

                if (bitmap != null) posterCache.put(address, bitmap)
            } catch (e: Exception) {
                android.util.Log.w("KaiserTV", "Poster load failed: $address", e)
            }

            val targets = posterRequests.remove(address) ?: return@execute
            val result = bitmap
            if (result != null) runOnUiThread {
                targets.forEach { ref ->
                    val image = ref.get()
                    if (image != null && image.tag == address) image.setImageBitmap(result)
                }
            }
        }
    }

    private fun applyKaiserImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyKaiserImmersiveMode()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        previewPlayer?.release()
        previewPlayer = null
        posterExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun persistRecentHistory() {
        val arr = JSONArray()
        recent.forEach { r -> arr.put(JSONObject().put("id", r.id).put("name", r.name).put("url", r.url).put("group", r.group).put("type", r.type).put("icon", r.icon).put("extension", r.extension).put("seriesId", r.seriesId).put("sourceId", r.sourceId)) }
        getSharedPreferences("androtv", MODE_PRIVATE).edit().putString("recent", arr.toString()).apply()
    }

    private fun saveRecent(item: MediaItem) {
        recent.removeAll { it.favoriteKey == item.favoriteKey }
        recent.add(0, item)
        while (recent.size > 20) recent.removeAt(recent.lastIndex)
        persistRecentHistory()
    }

    private var pendingSeriesEpisodes: List<MediaItem> = emptyList()

    private var openingPlayer = false
    private var fullscreenPoster: MediaItem? = null

    private fun play(item: MediaItem, startOver: Boolean = false) {
        if (openingPlayer) return
        if (item.type == "Diziler" && item.url.isBlank()) { loadSeriesEpisodes(item); return }
        if (item.url.isBlank()) {
            android.widget.Toast.makeText(this, "Bu içerik için yayın adresi bulunamadı.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        openingPlayer = true
        try {
            // Önizleme oynatıcısını kapat; tam ekran için yeni ExoPlayer açılacak.
            previewView?.player = null
            previewPlayer?.runCatching { stop() }
            previewPlayer?.release()
            previewPlayer = null
            previewView = null
            previewChannel = null
            previewUrl = ""

            // Episode playback should appear in the series history as its parent series,
            // rather than being stored under the separate "Bölümler" type.
            val historyItem = if (item.type == "Bölümler") {
                items.firstOrNull { it.type == "Diziler" && it.seriesId == item.seriesId }
                    ?: item.copy(type = "Diziler")
            } else item
            saveRecent(historyItem)
            val intent = Intent(this, PlayerActivity::class.java)
                .putExtra("name", item.name)
                .putExtra("url", item.url)
                .putExtra("isLive", item.type == "Canlı TV")
                .putExtra("bufferMode", prefs.getString("player_buffer", "normal"))
                .putExtra("aspectMode", prefs.getString("player_aspect", "fit"))
                .putExtra("resumeEnabled", prefs.getBoolean("player_resume", true))
                .putExtra("startOver", startOver)
            if (item.type == "Bölümler") {
                // Keep episode navigation metadata in process memory, not Intent extras.
                val episodeList = pendingSeriesEpisodes
                PlayerActivity.pendingEpisodeNames = episodeList.map { it.name }
                PlayerActivity.pendingEpisodeUrls = episodeList.map { it.url }
                PlayerActivity.pendingEpisodeIndex = episodeList.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
                PlayerActivity.pendingSeriesTitle = historyItem.name
                PlayerActivity.pendingSeriesKey = item.seriesId.ifBlank { historyItem.seriesId.ifBlank { historyItem.id } }
            } else {
                PlayerActivity.pendingEpisodeNames = emptyList()
                PlayerActivity.pendingEpisodeUrls = emptyList()
                PlayerActivity.pendingEpisodeIndex = 0
                PlayerActivity.pendingSeriesTitle = ""
                PlayerActivity.pendingSeriesKey = ""
            }
            if (item.type == "Canlı TV") {
                val channels = items.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
                val recentChannels = recent.filter { it.type == "Canlı TV" && it.url.isNotBlank() }
                    .distinctBy { it.url }.take(20)
                // Keep large channel lists out of Intent extras to avoid Binder transaction crashes.
                PlayerActivity.pendingChannelNames = channels.map { it.name }
                PlayerActivity.pendingChannelUrls = channels.map { it.url }
                PlayerActivity.pendingChannelIndex = channels.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
                PlayerActivity.pendingRecentChannelNames = recentChannels.map { it.name }
                PlayerActivity.pendingRecentChannelUrls = recentChannels.map { it.url }
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                openingPlayer = false
                android.widget.Toast.makeText(this, "Tam ekran oynatıcı açılamadı. Tekrar deneyin.", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            openingPlayer = false
            android.widget.Toast.makeText(this, "Tam ekran oynatıcı açılamadı. Tekrar deneyin.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        openingPlayer = false
        if (openTvTabOnResume) {
            openTvTabOnResume = false
            detailSeries = null
            detailMovie = null
            seriesEpisodeRows = emptyList()
            selectedType = "Canlı TV"
            selectedGroup = "Tüm kategoriler"
            sidebarMode = "main"
            searchQuery = ""
            prefs.edit().putString("last_section", "Canlı TV").apply()
            render()
        }
    }

    override fun onPause() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }

    private fun loadSeriesEpisodes(series: MediaItem) {
        if (xtreamBase.isBlank() || xtreamUser.isBlank() || xtreamPass.isBlank()) {
            AlertDialog.Builder(this).setTitle(series.name).setMessage("Bu dizi Xtream Codes bağlantısından yüklenmiş olmalı. Lütfen Xtream bağlantını yeniden yükle.").setPositiveButton("Tamam", null).show(); return
        }
        status.text = "${series.name}: sezonlar yükleniyor…"
        thread {
            try {
                val u = URLEncoder.encode(xtreamUser, "UTF-8"); val p = URLEncoder.encode(xtreamPass, "UTF-8")
                val url = "$xtreamBase/player_api.php?username=$u&password=$p&action=get_series_info&series_id=${URLEncoder.encode(series.seriesId.ifBlank { series.id }, "UTF-8")}"
                val data = JSONObject(request(url))
                val episodes = data.optJSONObject("episodes") ?: JSONObject()
                val episodeRows = mutableListOf<Pair<String, List<MediaItem>>>()
                val keys = episodes.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = episodes.optJSONArray(key) ?: continue
                    val rows = mutableListOf<MediaItem>()
                    for (i in 0 until arr.length()) {
                        val ep = arr.optJSONObject(i) ?: continue
                        val id = ep.optString("id", ep.optString("episode_id", "")); if (id.isBlank()) continue
                        val ext = ep.optString("container_extension", "mp4")
                        val epName = ep.optString("title", ep.optString("name", "Bölüm ${i + 1}"))
                        val stream = "$xtreamBase/series/$u/$p/$id.$ext"
                        rows.add(MediaItem(id, epName, stream, "${series.name} • Sezon $key", "Bölümler", ep.optString("info").takeIf { it.startsWith("http") } ?: "", ext))
                    }
                    episodeRows.add(Pair(key, rows))
                }
                runOnUiThread {
                    status.text = "Dizi bölümleri hazır."
                    if (episodeRows.isEmpty()) { AlertDialog.Builder(this).setTitle(series.name).setMessage("Bu dizi için bölüm bulunamadı.").setPositiveButton("Tamam", null).show(); return@runOnUiThread }
                    val seasonLabels = episodeRows.map { pair -> "Sezon ${pair.first}" }
                    AlertDialog.Builder(this).setTitle(series.name).setItems(seasonLabels.toTypedArray()) { _, which ->
                        val chosen = episodeRows[which].second
                        if (chosen.isEmpty()) { Toast.makeText(this, "Bu sezonda bölüm bulunamadı.", Toast.LENGTH_SHORT).show(); return@setItems }
                        AlertDialog.Builder(this).setTitle(seasonLabels[which]).setItems(chosen.map { it.name }.toTypedArray()) { _, epIndex ->
                            pendingSeriesEpisodes = chosen
                            play(chosen[epIndex])
                        }.setNegativeButton("Geri", null).show()
                    }.setNegativeButton("Kapat", null).show()
                }
            } catch (e: Exception) { runOnUiThread { status.text = "${t("Bölümler yüklenemedi")}: ${friendlyLoadError(e)}" } }
        }
    }

}
