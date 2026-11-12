package com.example.androtviptv

// Stream Xtream catalogues in bounded batches; keep the original UI unchanged.

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Bitmap
import android.util.LruCache
import android.util.JsonReader
import android.util.JsonToken
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
import java.io.InputStreamReader
import kotlin.concurrent.thread
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.lang.ref.WeakReference

private data class SavedSource(val id: String, val name: String, val mode: String, val address: String, val username: String = "", val password: String = "")

private data class MediaItem(
    val id: String, val name: String, val url: String, val group: String,
    val type: String, val icon: String = "", val extension: String = "", val seriesId: String = "", val sourceId: String = "", val plot: String = "", val actors: String = "", val rating: String = "", val releaseDate: String = "", val genre: String = "", val requestHeaders: String = ""
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
        const val EXTRA_TV_HOME_ITEM_KEY = "kaisertv_tv_home_item_key"
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
            "de" -> mapOf("KaiserTV+ uygulama bilgileri" to "KaiserTV+ App-Informationen", "Sürüm" to "Version", "Derleme tarihi" to "Build-Datum", "Uygulama boyutu" to "App-Größe", "Ayarlar" to "Einstellungen", "Bir bölümü seç; ayrı ekranda açılacak." to "Wähle einen Bereich aus.", "Kaynak Yönetimi" to "Quellenverwaltung", "M3U / Xtream kaynak ekle, düzenle" to "M3U-/Xtream-Quellen hinzufügen oder bearbeiten", "İçerik Ayarları" to "Inhaltseinstellungen", "Kategoriler, gizleme ve sıralama" to "Kategorien, Ausblenden und Sortierung", "Tema ve Görünüm" to "Design & Darstellung", "Renk, arka plan ve arayüz stili" to "Farben, Hintergrund und Oberflächenstil", "İzleme Geçmişi" to "Wiedergabeverlauf", "Son izlenen içerikleri görüntüle" to "Zuletzt angesehene Inhalte", "Hakkında" to "Über", "Uygulama sürümü ve teknik bilgiler" to "App-Version und technische Informationen", "Dil / Language" to "Sprache", "Uygulama dilini seç" to "App-Sprache auswählen", "Dil" to "Sprache", "Cihaz dili" to "Gerätesprache", "Uygulama dili" to "App-Sprache")
            "fr" -> mapOf("KaiserTV+ uygulama bilgileri" to "Informations sur l’application KaiserTV+", "Sürüm" to "Version", "Derleme tarihi" to "Date de compilation", "Uygulama boyutu" to "Taille de l’application", "Ayarlar" to "Paramètres", "Bir bölümü seç; ayrı ekranda açılacak." to "Choisissez une rubrique.", "Kaynak Yönetimi" to "Gestion des sources", "M3U / Xtream kaynak ekle, düzenle" to "Ajouter ou modifier des sources M3U / Xtream", "İçerik Ayarları" to "Paramètres du contenu", "Kategoriler, gizleme ve sıralama" to "Catégories, masquage et tri", "Tema ve Görünüm" to "Thème et apparence", "Renk, arka plan ve arayüz stili" to "Couleurs, arrière-plan et style", "İzleme Geçmişi" to "Historique", "Son izlenen içerikleri görüntüle" to "Afficher les contenus récemment regardés", "Hakkında" to "À propos", "Uygulama sürümü ve teknik bilgiler" to "Version et informations techniques", "Dil / Language" to "Langue", "Uygulama dilini seç" to "Choisir la langue de l’application", "Dil" to "Langue", "Cihaz dili" to "Langue de l’appareil", "Uygulama dili" to "Langue de l’application")
            "it" -> mapOf("KaiserTV+ uygulama bilgileri" to "Informazioni sull’app KaiserTV+", "Sürüm" to "Versione", "Derleme tarihi" to "Data di compilazione", "Uygulama boyutu" to "Dimensione app", "Ayarlar" to "Impostazioni", "Bir bölümü seç; ayrı ekranda açılacak." to "Scegli una sezione.", "Kaynak Yönetimi" to "Gestione sorgenti", "M3U / Xtream kaynak ekle, düzenle" to "Aggiungi o modifica sorgenti M3U / Xtream", "İçerik Ayarları" to "Impostazioni contenuti", "Kategoriler, gizleme ve sıralama" to "Categorie, visibilità e ordinamento", "Tema ve Görünüm" to "Tema e aspetto", "Renk, arka plan ve arayüz stili" to "Colori, sfondo e stile dell’interfaccia", "İzleme Geçmişi" to "Cronologia", "Son izlenen içerikleri görüntüle" to "Visualizza i contenuti guardati di recente", "Hakkında" to "Informazioni", "Uygulama sürümü ve teknik bilgiler" to "Versione e informazioni tecniche", "Dil / Language" to "Lingua", "Uygulama dilini seç" to "Scegli la lingua dell’app", "Dil" to "Lingua", "Cihaz dili" to "Lingua del dispositivo", "Uygulama dili" to "Lingua dell’app")
            "es" -> mapOf("KaiserTV+ uygulama bilgileri" to "Información de la aplicación KaiserTV+", "Sürüm" to "Versión", "Derleme tarihi" to "Fecha de compilación", "Uygulama boyutu" to "Tamaño de la aplicación", "Ayarlar" to "Ajustes", "Bir bölümü seç; ayrı ekranda açılacak." to "Elige una sección.", "Kaynak Yönetimi" to "Gestión de fuentes", "M3U / Xtream kaynak ekle, düzenle" to "Añadir o editar fuentes M3U / Xtream", "İçerik Ayarları" to "Ajustes de contenido", "Kategoriler, gizleme ve sıralama" to "Categorías, visibilidad y orden", "Tema ve Görünüm" to "Tema y apariencia", "Renk, arka plan ve arayüz stili" to "Colores, fondo y estilo de interfaz", "İzleme Geçmişi" to "Historial", "Son izlenen içerikleri görüntüle" to "Ver contenido visto recientemente", "Hakkında" to "Acerca de", "Uygulama sürümü ve teknik bilgiler" to "Versión e información técnica", "Dil / Language" to "Idioma", "Uygulama dilini seç" to "Elegir el idioma de la aplicación", "Dil" to "Idioma", "Cihaz dili" to "Idioma del dispositivo", "Uygulama dili" to "Idioma de la aplicación")
            "ru" -> mapOf("KaiserTV+ uygulama bilgileri" to "Информация о приложении KaiserTV+", "Sürüm" to "Версия", "Derleme tarihi" to "Дата сборки", "Uygulama boyutu" to "Размер приложения", "Ayarlar" to "Настройки", "Bir bölümü seç; ayrı ekranda açılacak." to "Выберите раздел.", "Kaynak Yönetimi" to "Управление источниками", "M3U / Xtream kaynak ekle, düzenle" to "Добавить или изменить источники M3U / Xtream", "İçerik Ayarları" to "Настройки контента", "Kategoriler, gizleme ve sıralama" to "Категории, скрытие и сортировка", "Tema ve Görünüm" to "Тема и оформление", "Renk, arka plan ve arayüz stili" to "Цвета, фон и стиль интерфейса", "İzleme Geçmişi" to "История просмотров", "Son izlenen içerikleri görüntüle" to "Недавно просмотренный контент", "Hakkında" to "О приложении", "Uygulama sürümü ve teknik bilgiler" to "Версия и техническая информация", "Dil / Language" to "Язык", "Uygulama dilini seç" to "Выберите язык приложения", "Dil" to "Язык", "Cihaz dili" to "Язык устройства", "Uygulama dili" to "Язык приложения")
            "ar" -> mapOf("KaiserTV+ uygulama bilgileri" to "معلومات تطبيق KaiserTV+", "Sürüm" to "الإصدار", "Derleme tarihi" to "تاريخ الإصدار", "Uygulama boyutu" to "حجم التطبيق", "Ayarlar" to "الإعدادات", "Bir bölümü seç; ayrı ekranda açılacak." to "اختر قسماً لفتحه.", "Kaynak Yönetimi" to "إدارة المصادر", "M3U / Xtream kaynak ekle, düzenle" to "إضافة أو تعديل مصادر M3U / Xtream", "İçerik Ayarları" to "إعدادات المحتوى", "Kategoriler, gizleme ve sıralama" to "الفئات والإخفاء والترتيب", "Tema ve Görünüm" to "المظهر والتصميم", "Renk, arka plan ve arayüz stili" to "الألوان والخلفية ونمط الواجهة", "İzleme Geçmişi" to "سجل المشاهدة", "Son izlenen içerikleri görüntüle" to "عرض المحتوى الذي تمت مشاهدته مؤخراً", "Hakkında" to "حول التطبيق", "Uygulama sürümü ve teknik bilgiler" to "إصدار التطبيق والمعلومات التقنية", "Dil / Language" to "اللغة", "Uygulama dilini seç" to "اختر لغة التطبيق", "Dil" to "اللغة", "Cihaz dili" to "لغة الجهاز", "Uygulama dili" to "لغة التطبيق")
            else -> emptyMap()
        }
        val common = mapOf(
            "en" to mapOf("Kaynak ekle ve kayıtlı bağlantılarını buradan yönet." to "Add and manage your saved connections here.",
                "Henüz içerik yok" to "No content yet",
                "Cihaz dili" to "Device language",
                "Geri" to "Back", "KaiserTV+ uygulama bilgileri" to "KaiserTV+ app information",
                "Sürüm" to "Version", "Derleme tarihi" to "Build date", "Uygulama boyutu" to "App size",
                "içerik" to "items", "İzlenen TV kanalları" to "Watched TV channels",
                "İzlenen filmler" to "Watched movies", "İzlenen diziler" to "Watched series",
                "Canlı TV" to "Live TV", "Kaynaklar" to "Sources", "Kaynak adı (ör. Ev Xtream)" to "Source name (e.g. Home Xtream)",
                "Yasal kullanım hakkına sahip olduğun yayın adreslerini ekle yönet." to "Add and manage streaming sources you are authorized to use.",
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
        val dynamicLabels = when (language) {
            "en" -> mapOf("Haftanın Filmleri" to "Movies of the Week", "Haftanın Dizileri" to "Series of the Week", "Çıkış Yap" to "Exit", "Vazgeç" to "Cancel")
            "de" -> mapOf("Haftanın Filmleri" to "Filme der Woche", "Haftanın Dizileri" to "Serien der Woche", "Çıkış Yap" to "Beenden", "Vazgeç" to "Abbrechen")
            "fr" -> mapOf("Haftanın Filmleri" to "Films de la semaine", "Haftanın Dizileri" to "Séries de la semaine", "Çıkış Yap" to "Quitter", "Vazgeç" to "Annuler")
            "it" -> mapOf("Haftanın Filmleri" to "Film della settimana", "Haftanın Dizileri" to "Serie della settimana", "Çıkış Yap" to "Esci", "Vazgeç" to "Annulla")
            "es" -> mapOf("Haftanın Filmleri" to "Películas de la semana", "Haftanın Dizileri" to "Series de la semana", "Çıkış Yap" to "Salir", "Vazgeç" to "Cancelar")
            "ru" -> mapOf("Haftanın Filmleri" to "Фильмы недели", "Haftanın Dizileri" to "Сериалы недели", "Çıkış Yap" to "Выйти", "Vazgeç" to "Отмена")
            "ar" -> mapOf("Haftanın Filmleri" to "أفلام الأسبوع", "Haftanın Dizileri" to "مسلسلات الأسبوع", "Çıkış Yap" to "خروج", "Vazgeç" to "إلغاء")
            else -> emptyMap()
        }
        return dynamicLabels[value] ?: translations[value] ?: common[language]?.get(value) ?: extraTranslations[language]?.get(value) ?: runtimeTranslations[language]?.get(value) ?: sharedUiTranslations[value] ?: value
    }
    // Android TV cihazlarında aynı anda daha az görsel işi yürüt; RAM'e göre güvenli bitmap bütçesi kullan.
    // Bound background poster work: an unbounded queue can exhaust small TV boxes.
    // Mi TV Stick Full HD has very limited memory: serialize poster decoding
    // and keep only a small bounded queue so tab changes cannot flood the process.
    private val posterExecutor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(12),
        ThreadPoolExecutor.AbortPolicy()
    )
    // A single coalescing writer prevents concurrent cache writes and duplicate snapshots.
    private val cacheExecutor = Executors.newSingleThreadExecutor()
    private val cacheWriteLock = Any()
    private var pendingCacheSnapshot: List<MediaItem>? = null
    private var cacheWriterRunning = false
    private val posterCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 32L).coerceAtMost(3L * 1024L * 1024L).coerceAtLeast(1024L * 1024L).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val items = mutableListOf<MediaItem>()
    // Provider category names are kept independently from loaded media so every
    // category can be shown immediately, before its first content page arrives.
    private val providerCategoryNames = mutableMapOf<String, List<String>>()
    private var pendingTvHomeItemKey: String? = null
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
    private var liveChannelsLimit = 24
    // Limit poster-card creation per render; large M3U categories can contain thousands of entries.
    private var mediaItemsLimit = 12
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
    private var loadingMessageView: TextView? = null

    private fun updateLoadingMessage(message: String) {
        status.text = message
        loadingMessageView?.text = message
    }
    private lateinit var playlistUrl: EditText
    private lateinit var server: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var sourceMode: RadioGroup
    private var selectedType = "Tümü"
    private var selectedHistoryType = "Canlı TV"
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
    private var customBackgroundUri: String? = null
    private var selectedCategorySourceId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyKaiserImmersiveMode()
        pendingTvHomeItemKey = intent?.getStringExtra(EXTRA_TV_HOME_ITEM_KEY)
        installCrashLogger()
        val diagnostics = getSharedPreferences("androtv_diagnostics", MODE_PRIVATE)
        if (diagnostics.getBoolean("session_active", false)) {
            diagnostics.edit()
                .putString("last_unexpected_state", diagnostics.getString("last_ui_state", "Son arayüz durumu yok"))
                .apply()
        }
        diagnostics.edit().putBoolean("session_active", true).apply()
        transparentMenu = prefs.getBoolean("transparent_menu", true)
        if (!prefs.getBoolean("global_oval_style_applied", false)) {
            prefs.edit().putBoolean("oval_buttons", true).putBoolean("global_oval_style_applied", true).apply()
        }
        ovalButtons = prefs.getBoolean("oval_buttons", true)
        gradientBackground = prefs.getBoolean("gradient_background", prefs.getBoolean("glass_panel_background", true))
        customBackgroundUri = prefs.getString("custom_background_uri", null)
        if (!prefs.contains("gradient_background")) prefs.edit().putBoolean("gradient_background", gradientBackground).apply()
        // Restore the selected KaiserTV Global theme before constructing any views.
        if (!prefs.getBoolean("global_themes_initialized", false)) {
            prefs.edit().putString("theme_name", "Antrasit • Mavi").putBoolean("global_themes_initialized", true).apply()
        }
        when (prefs.getString("theme_name", "Antrasit • Mavi")) {
            "Gece • Altın" -> { bg = Color.rgb(15, 16, 20); accent = Color.rgb(224, 184, 95); accentBright = accent }
            "Gece • Buzul" -> { bg = Color.rgb(0, 0, 0); accent = Color.rgb(84, 198, 217); accentBright = accent }
            "Orman • Zümrüt" -> { bg = Color.rgb(16, 27, 24); accent = Color.rgb(32, 201, 151); accentBright = accent }
            "Gece • Yakut" -> { bg = Color.rgb(25, 20, 23); accent = Color.rgb(240, 100, 121); accentBright = accent }
            "Gece • Lavanta" -> { bg = Color.rgb(23, 21, 29); accent = Color.rgb(167, 139, 250); accentBright = accent }
            "Lacivert • Turkuaz" -> { bg = Color.rgb(16, 24, 32); accent = Color.rgb(84, 198, 217); accentBright = accent }
            "Koyu • Mor" -> { bg = Color.rgb(23, 21, 29); accent = Color.rgb(167, 139, 250); accentBright = accent }
            else -> { bg = Color.rgb(17, 20, 25); accent = Color.rgb(0, 166, 255); accentBright = accent }
        }
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
        // Show the app's own loading UI immediately. Disk cache parsing can be expensive
        // on low-memory Android TV devices, so never perform it on the main thread.
        isLoadingSource = true
        // Start on Home every time; the user's last section remains saved for history only.
        selectedType = "Tümü"
        selectedGroup = "Tüm kategoriler"
        buildUi()
        // status is a lateinit view created by buildUi(); update it only after initialization.
        status.text = "KaiserTV+ hazırlanıyor…"
        applyThemeSurfaces()
        showPreviousCrashIfAny()
        thread(name = "kaiser-startup-cache") {
            loadCachedItems()
            runOnUiThread {
                isLoadingSource = false
                loadSavedSources()
                publishHomeChannel()
                tryOpenPendingTvHomeItem()
                if (savedSources.isEmpty()) render()
            }
        }
    }

    private var lastDiagnosticWriteAt = 0L
    private var lastDiagnosticKey = ""

    private fun recordUiDiagnostics() {
        val now = android.os.SystemClock.elapsedRealtime()
        val key = selectedType + "|" + selectedGroup + "|" + sidebarMode
        if (key == lastDiagnosticKey && now - lastDiagnosticWriteAt < 2500L) return
        lastDiagnosticKey = key
        lastDiagnosticWriteAt = now
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMb = runtime.maxMemory() / (1024L * 1024L)
        val queueSize = posterExecutor.queue.size
        val state = "Sekme=" + selectedType + "; kategori=" + selectedGroup + "; menü=" + sidebarMode + "; öğe=" + items.size + "; heap=" + usedMb + "/" + maxMb + "MB; afişKuyruğu=" + queueSize
        android.util.Log.i("KaiserTV", "UI_STATE $state")
        getSharedPreferences("androtv_diagnostics", MODE_PRIVATE).edit()
            .putString("last_ui_state", state)
            .putLong("last_ui_state_time", System.currentTimeMillis())
            .apply()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val writer = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(writer))
                val diagnostics = getSharedPreferences("androtv_diagnostics", MODE_PRIVATE)
                val lastState = diagnostics.getString("last_ui_state", "UI durumu kaydedilemedi")
                diagnostics.edit()
                    .putString("last_crash", writer.toString().take(10000) + "\n\nSon arayüz durumu: " + lastState)
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .commit()
            } catch (_: Exception) { }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun showPreviousCrashIfAny() {
        val diagnostics = getSharedPreferences("androtv_diagnostics", MODE_PRIVATE)
        val trace = diagnostics.getString("last_crash", null)
        val unexpectedState = diagnostics.getString("last_unexpected_state", null)
        if (trace == null && unexpectedState == null) return
        diagnostics.edit().remove("last_crash").remove("last_unexpected_state").apply()
        val message = trace ?: ("Uygulama önceki oturumda beklenmedik şekilde kapanmış olabilir.\n\nSon durum: " + unexpectedState)
        android.app.AlertDialog.Builder(this)
            .setTitle(if (trace != null) "Önceki uygulama hatası" else "Önceki oturum bilgisi")
            .setMessage(message.take(3500))
            .setPositiveButton("Kopyala") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("KaiserTV tanılama kaydı", message))
                android.widget.Toast.makeText(this, "Kayıt kopyalandı; buraya yapıştır.", android.widget.Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private lateinit var heroTitle: TextView
    private lateinit var heroSubtitle: TextView
    private lateinit var heroArt: FrameLayout
    private lateinit var navBar: LinearLayout
    private lateinit var searchBar: LinearLayout
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
    private val isTelevisionDevice: Boolean
        get() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    private val isMobileLayout: Boolean
        get() = !isTelevisionDevice
    private var settingsPage = "main"
    private var historyType = "Canlı TV"
    private val hiddenCategories = mutableSetOf<String>()
    private var searchQuery = ""
    private val selectedSearchHistory = mutableSetOf<String>()
    private var searchInputFocusPending = false
    private var focusFirstContentOnRender = false
    private var focusFirstCategoryOnRender = false
    private var contextNavigationStage = 0 // 0=main menu, 1=categories, 2=content
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
                // A channel click can rebuild the sidebar while Android is still moving
                // focus from the old button. Never pass a detached view to ViewGroup.focusSearch.
                if (!focused.isAttachedToWindow || !isDescendantView(this, focused)) {
                    fun firstFocusable(group: ViewGroup): View? {
                        for (index in 0 until group.childCount) {
                            val child = group.getChildAt(index)
                            if (!child.isAttachedToWindow || !child.isShown || !child.isEnabled) continue
                            if (child.isFocusable) return child
                            if (child is ViewGroup) firstFocusable(child)?.let { return it }
                        }
                        return null
                    }
                    return firstFocusable(this) ?: focused
                }
                if (sidebarMode == "context" && selectedType in listOf("Canlı TV", "Filmler", "Diziler")) {
                    if (direction == View.FOCUS_RIGHT && contextNavigationStage == 0 &&
                        ::sidebar.isInitialized && isDescendantView(sidebar, focused) &&
                        sidebar.findViewWithTag<View>("primary-navigation")?.let { it is ViewGroup && isDescendantView(it, focused) } == true) {
                        val activeTabLabel = when (selectedType) {
                            "Canlı TV" -> t("TV")
                            "Filmler" -> t("Filmler")
                            "Diziler" -> t("Diziler")
                            else -> ""
                        }
                        // Only the currently selected TV/Movies/Series tab opens its category list.
                        // Right from Home or another main-menu item goes straight to the content area.
                        if (activeTabLabel.isNotBlank() && focused.contentDescription?.toString() == activeTabLabel) {
                            showContextNavigationStage(1)
                            firstCategoryFocusView?.post { firstCategoryFocusView?.requestFocus() }
                            return firstCategoryFocusView ?: focused
                        }
                    }
                    if (direction == View.FOCUS_LEFT && contextNavigationStage == 2 &&
                        ::mainContentScroll.isInitialized && isDescendantView(mainContentScroll, focused)) {
                        showContextNavigationStage(1)
                        firstCategoryFocusView?.post { firstCategoryFocusView?.requestFocus() }
                        return firstCategoryFocusView ?: focused
                    }
                    if (direction == View.FOCUS_RIGHT && contextNavigationStage == 1 &&
                        ::categoryListScroll.isInitialized && isDescendantView(categoryListScroll, focused)) {
                        showContextNavigationStage(2)
                        firstRightContentFocusView?.post { firstRightContentFocusView?.requestFocus() }
                        return firstRightContentFocusView ?: focused
                    }
                }
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
                    // Keep focus in its current area; avoid a full view-tree geometry scan on every D-pad press.
                    return focused
                }
                if (candidate != null && candidate.isAttachedToWindow && candidate.isShown &&
                    candidate.isEnabled && candidate.isFocusable &&
                    (!crossesBoundary || crossingAllowed)) return candidate

                // Android's native focus search is sufficient for ordinary navigation.
                // A recursive scan with getLocationOnScreen() on every key press made TV menus lag.
                return focused
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
        val initialSidebarWidth = if (isMobileLayout) 142 else 150
        root.addView(sidebar, LinearLayout.LayoutParams(dp(initialSidebarWidth), -1).apply { setMargins(0, 0, dp(if (isMobileLayout) 3 else 8), 0) })

        val primaryMenuColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(if (isMobileLayout) 128 else 140), -1)
        }
        sidebar.addView(primaryMenuColumn)

        searchBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        primaryMenuColumn.addView(searchBar, LinearLayout.LayoutParams(-1, dp(44)))

        brandView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
            clipChildren = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        brandView.addView(makeKaiserLogoView(23f), LinearLayout.LayoutParams(-1, -1))
        brandView.contentDescription = "KAISER TV+"
        primaryMenuColumn.addView(brandView, LinearLayout.LayoutParams(-1, dp(if (isMobileLayout) 58 else 58)))
        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
        sidebarMenuScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(navBar, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        primaryMenuColumn.addView(sidebarMenuScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        settingsBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        primaryMenuColumn.addView(settingsBar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        primaryMenuColumn.tag = "primary-navigation"
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
        categoryScroll.visibility = View.GONE
        // Kategori şeridi ana içerikten kaldırıldı; kategori listesi yalnızca sol yan sütunda gösterilir.
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

    private fun showContextNavigationStage(stage: Int) {
        contextNavigationStage = stage.coerceIn(0, 2)
        if (!::sidebar.isInitialized) return
        val primary = sidebar.findViewWithTag<View>("primary-navigation")
        val hasContext = sidebarMode == "context" && selectedType in listOf("Canlı TV", "Filmler", "Diziler")
        primary?.visibility = if (!hasContext || contextNavigationStage == 0) View.VISIBLE else View.GONE
        if (::categoryListScroll.isInitialized) {
            categoryListScroll.visibility = if (hasContext && contextNavigationStage == 1) View.VISIBLE else View.GONE
        }
        sidebar.visibility = if (hasContext && contextNavigationStage == 2) View.GONE else View.VISIBLE
        val width = when {
            hasContext && contextNavigationStage == 1 -> dp(150)
            isMobileLayout -> dp(142)
            else -> dp(150)
        }
        sidebar.layoutParams = sidebar.layoutParams.apply { this.width = width }
    }

    private fun rebuildNavigation() {
        if (!::navBar.isInitialized) return
        // Keep the main navigation icon-only, but expand the sidebar when a
        // category list is open so full category names remain readable.
        if (::sidebar.isInitialized) {
            val detailMode = detailSeries != null || detailMovie != null
            val hasContext = !detailMode && sidebarMode == "context" && selectedType in listOf("Canlı TV", "Filmler", "Diziler")
            val desiredWidth = dp(if (isMobileLayout) 142 else 150)
            val params = sidebar.layoutParams
            if (params != null && params.width != desiredWidth) {
                params.width = desiredWidth
                sidebar.layoutParams = params
            }
            sidebar.findViewWithTag<View>("primary-navigation")?.visibility =
                if (!hasContext || contextNavigationStage == 0) View.VISIBLE else View.GONE
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
        if (::searchBar.isInitialized) searchBar.removeAllViews()
        if (::settingsBar.isInitialized) settingsBar.removeAllViews()
        // Re-enable descendant focus immediately after old children are detached.
        // Leaving FOCUS_BLOCK_DESCENDANTS active prevents the remote from entering the sidebar.
        navBar.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        firstCategoryFocusView = null
        if (::categoryPanel.isInitialized) {
            val detailMode = detailSeries != null || detailMovie != null
            categoryListScroll.visibility = if (!detailMode && sidebarMode == "context" && selectedType in listOf("Canlı TV", "Filmler", "Diziler") && contextNavigationStage == 1) View.VISIBLE else View.GONE
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
                iconDrawable?.setBounds(0, 0, dp(if (isMobileLayout) 20 else 29), dp(if (isMobileLayout) 20 else 29))
                // Restore the full accent selection tile for the focused navigation item.
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
                textSize = if (isMobileLayout) 12f else 15f
                text = when {
                    isMobileLayout -> t(when {
                        label == "Ara" -> "Ara"
                        label.contains("Ana Sayfa") -> "Ana Sayfa"
                        label.contains("TV") -> "TV"
                        label.contains("Filmler") -> "Filmler"
                        label.contains("Diziler") -> "Diziler"
                        label.contains("Favoriler") -> "Favoriler"
                        label.contains("İzlediklerim") || label.contains("Geçmiş") -> "İzlediklerim"
                        label.contains("Ayarlar") -> "Ayarlar"
                        else -> label
                    })
                    else -> t(when {
                        label == "Ara" -> "Ara"
                        label.contains("Ana Sayfa") -> "Ana Sayfa"
                        label.contains("TV") -> "TV"
                        label.contains("Filmler") -> "Filmler"
                        label.contains("Diziler") -> "Diziler"
                        label.contains("Favoriler") -> "Favoriler"
                        label.contains("İzlediklerim") || label.contains("Geçmiş") -> "İzlediklerim"
                        label.contains("Ayarlar") -> "Ayarlar"
                        else -> label
                    })
                }
                compoundDrawablePadding = dp(if (isMobileLayout) 5 else 7)
                setCompoundDrawables(iconDrawable, null, null, null)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = text.toString()
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                setPadding(if (isMobileLayout) dp(6) else dp(10), 0, dp(4), 0)
                minHeight = dp(if (isMobileLayout) 38 else 40)
                id = View.generateViewId()
            }
            target.addView(sidebarButton, LinearLayout.LayoutParams(-1, dp(if (isMobileLayout) 38 else 40)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(0, dp(1), 0, dp(1))
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

        // Keep Search anchored at the upper-left; navigation tabs are bottom-aligned.
        if (sidebarMode in listOf("main", "settings", "search", "context")) {
            addSidebarButton("Ara", sidebarMode == "search", searchBar) {
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
        }

        // Ana menü normal ekranlarda kalır; TV/Film/Dizi seçilince sol menü kategori listesine dönüşür.
        if (sidebarMode in listOf("main", "settings", "search")) {
            listOf("⌂  Ana Sayfa", "TV  TV", "▧  Filmler", "▤  Diziler", "♡  Favoriler", "◷  İzlediklerim", "⚙  Ayarlar").forEach { tab ->
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
                        contextNavigationStage = if (key in listOf("Canlı TV", "Filmler", "Diziler")) 1 else 0
                        prefs.edit().putString("last_section", key).putString("last_group", "Tüm kategoriler").apply()
                        rebuildNavigation()
                        render()
                        // Home weekly rows always start from the same vertical origin.
                        // The shared ScrollView otherwise preserves the previous tab's offset.
                        if (key == "Tümü") mainContentScroll.post { mainContentScroll.scrollTo(0, 0) }
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
                Triple("⌂", "Ana Sayfa", "Tümü"),
                Triple("▣", "TV", "Canlı TV"),
                Triple("▧", "Filmler", "Filmler"),
                Triple("▤", "Diziler", "Diziler"),
                Triple("♡", "Favoriler", "Favoriler"),
                Triple("◷", "İzlediklerim", "Son İzlenenler"),
                Triple("⚙", "Ayarlar", "Ayarlar")
            )
            compactTabs.forEach { (_, label, key) ->
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
                            contextNavigationStage = if (key in listOf("Canlı TV", "Filmler", "Diziler")) 1 else 0
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
                    text = "←  ${t("Geri")}"
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(muted)
                    setPadding(dp(8), 0, 0, dp(10))
                    isFocusable = true
                    isClickable = true
                    background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    setOnFocusChangeListener { view, hasFocus ->
                        (view as TextView).setTextColor(if (hasFocus) accentBright else muted)
                    }
                    setOnClickListener {
                        detailSeries = null
                        detailMovie = null
                        seriesEpisodeRows = emptyList()
                        seriesEpisodesLoading = false
                        seriesEpisodesError = ""
                        expandedCategory = null
                        focusedCategoryName = null
                        selectedType = "Tümü"
                        selectedGroup = "Tüm kategoriler"
                        sidebarMode = "main"
                        searchQuery = ""
                        prefs.edit().putString("last_section", "Tümü")
                            .putString("last_group", "Tüm kategoriler").apply()
                        rebuildNavigation()
                        render()
                        selectedSidebarFocusView?.post { selectedSidebarFocusView?.requestFocus() }
                    }
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
                            if (hasFocus) {
                                focusedCategoryName = name
                                showContextNavigationStage(1)
                            }
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
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                                keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) {
                                showContextNavigationStage(0)
                                selectedSidebarFocusView?.post { selectedSidebarFocusView?.requestFocus() }
                                true
                            } else if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                                keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                                showContextNavigationStage(2)
                                firstRightContentFocusView?.post { firstRightContentFocusView?.requestFocus() }
                                true
                            } else false
                        }
                        setOnClickListener {
                            selectedGroup = name
                            expandedCategory = name to entries
                            mediaItemsLimit = 12
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
            setPadding(0, 0, 0, 0)
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        val sourceHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        val sourceHeadingText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sourceHeadingText.addView(TextView(this).apply {
            text = t("Kaynaklar")
            textSize = 27f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        sourceHeadingText.addView(TextView(this).apply {
            text = t("Yasal kullanım hakkına sahip olduğun yayın adreslerini ekle yönet.")
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })
        sourceHeader.addView(sourceHeadingText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sourceHeader.addView(makeButton("←  ${t("Geri")}", false) {
            if (settingsPage == "sources") {
                settingsPage = "main"
                prefs.edit().putString("last_settings_page", settingsPage).apply()
            }
            render()
        }.apply { minWidth = 0; textSize = 12f }, LinearLayout.LayoutParams(dp(118), dp(42)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        form.addView(sourceHeader, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        val xtreamName = edit(t("Kaynak adı (ör. Ev Xtream)"), false).apply { setText(if (editSource?.mode == "xtream") editSource.name else "") }
        val m3uName = edit(t("Kaynak adı (ör. Ev TV)"), false).apply { setText(if (editSource?.mode == "m3u") editSource.name else "") }
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
            setPadding(dp(6), 0, dp(6), 0)
            input.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            input.setPadding(dp(4), 0, dp(4), 0)
            input.textSize = 12.5f
            addView(input, LinearLayout.LayoutParams(-1, dp(38)))
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
        val mac = RadioButton(this).apply { id = View.generateViewId(); text = "Stalker Portal" }
        sourceMode.addView(xtream)
        sourceMode.addView(m3u)
        sourceMode.addView(mac)
        sourceMode.check(when (editSource?.mode) {
            "xtream" -> xtream.id
            "mac" -> mac.id
            else -> m3u.id
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancelButton = makeButton(t("İptal"), false) { render() }.apply {
            minWidth = 0
            textSize = 13f
        }
        val connectButton = makeButton(t(if (editSource == null) "Bağlan" else "Kaydet"), true) {
            sourceName = when (sourceMode.checkedRadioButtonId) {
                m3u.id -> m3uName
                mac.id -> macName
                else -> xtreamName
            }
            if (sourceMode.checkedRadioButtonId == mac.id) {
                val portal = macPortal.text.toString().trim().trimEnd('/')
                val macValue = macAddress.text.toString().trim().uppercase(java.util.Locale.ROOT)
                val validMac = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(macValue)
                if (!(portal.startsWith("http://") || portal.startsWith("https://")) || !validMac) {
                    Toast.makeText(this, t("Portal URL ve geçerli MAC adresini girin (00:1A:79:XX:XX:XX)."), Toast.LENGTH_LONG).show()
                    return@makeButton
                }
                val label = sourceName.text.toString().trim().ifBlank { "MAC Portal ${savedSources.size + 1}" }
                val src = SavedSource(editSource?.id ?: java.util.UUID.randomUUID().toString(), label, "mac", portal, macValue)
                saveSource(src)
                hideKeyboard()
                if (editSource == null) {
                    settingsPage = "main"; sidebarMode = "main"; selectedType = "Tümü"
                    selectedGroup = "Tüm kategoriler"; render()
                }
                loadStalker(src, editSource != null)
                return@makeButton
            }
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
                saveSource(src); hideKeyboard()
                if (editSource == null) {
                    settingsPage = "main"; sidebarMode = "main"; selectedType = "Tümü"
                    selectedGroup = "Tüm kategoriler"
                }
                render(); loadXtream(src, editSource != null)
            } else {
                val address = playlistUrl.text.toString().trim()
                if (!address.startsWith("http://") && !address.startsWith("https://")) {
                    Toast.makeText(this, t("Geçerli bir M3U/M3U8 URL'si gir."), Toast.LENGTH_LONG).show()
                    return@makeButton
                }
                val label = sourceName.text.toString().trim().ifBlank { "M3U ${savedSources.size + 1}" }
                val src = SavedSource(editSource?.id ?: java.util.UUID.randomUUID().toString(), label, "m3u", address)
                saveSource(src); hideKeyboard()
                if (editSource == null) {
                    settingsPage = "main"; sidebarMode = "main"; selectedType = "Tümü"
                    selectedGroup = "Tüm kategoriler"
                }
                render(); loadM3u(src, editSource != null)
            }
        }.apply { minWidth = 0; textSize = 13f }

        val m3uNameFrame = inputFrame(m3uName)
        val xtreamNameFrame = inputFrame(xtreamName)
        val m3uFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(m3uNameFrame, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            addView(playlistFrame, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val xtreamFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(xtreamNameFrame, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            addView(serverFrame, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            addView(usernameFrame, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            addView(passwordFrame, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val macFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(inputFrame(macName), LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            addView(inputFrame(macPortal), LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            addView(inputFrame(macAddress), LinearLayout.LayoutParams(-1, dp(48)))
        }
        val modeCards = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sourceFormPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedDrawable(panel, 24, outline)
        }
        fun selectSourceMode(modeId: Int) {
            sourceMode.check(modeId)
            sourceName = when (modeId) { m3u.id -> m3uName; mac.id -> macName; else -> xtreamName }
            m3uFields.visibility = if (modeId == m3u.id) View.VISIBLE else View.GONE
            xtreamFields.visibility = if (modeId == xtream.id) View.VISIBLE else View.GONE
            macFields.visibility = if (modeId == mac.id) View.VISIBLE else View.GONE
            for (index in 0 until modeCards.childCount) {
                val card = modeCards.getChildAt(index) as? LinearLayout ?: continue
                val active = card.tag == modeId
                card.background = roundedDrawable(if (active) Color.rgb(3, 25, 43) else panel, 26,
                    if (active) accent else outline)
                card.elevation = if (active) dp(5).toFloat() else 0f
                (card.getChildAt(1) as? LinearLayout)?.let { labels ->
                    (labels.getChildAt(0) as? TextView)?.setTextColor(if (active) accentBright else Color.WHITE)
                    (labels.getChildAt(1) as? TextView)?.setTextColor(muted)
                }
            }
        }
        fun addModeCard(modeId: Int, title: String, subtitle: String, symbol: String) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(7), dp(8))
                tag = modeId
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = roundedDrawable(panel, 26, outline)
                setOnClickListener { selectSourceMode(modeId) }
                setOnFocusChangeListener { view, focused ->
                    if (focused) selectSourceMode(modeId)
                    view.scaleX = if (focused) 1.025f else 1f
                    view.scaleY = if (focused) 1.025f else 1f
                }
            }
            card.addView(TextView(this).apply {
                text = symbol; textSize = 25f; gravity = Gravity.CENTER; setTextColor(accentBright)
            }, LinearLayout.LayoutParams(dp(38), dp(48)))
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            labels.addView(TextView(this).apply {
                text = title; textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE); maxLines = 1
            })
            labels.addView(TextView(this).apply {
                text = subtitle; textSize = 10f; setTextColor(muted); maxLines = 2
                setPadding(0, dp(3), 0, 0)
            })
            card.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            modeCards.addView(card, LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(8))
            })
        }
        addModeCard(xtream.id, "Xtream Codes", t("Kullanıcı adı ve şifre ile bağlan"), "▤")
        addModeCard(m3u.id, "M3U / M3U8", t("Playlist bağlantısı ekle"), "▧")
        addModeCard(mac.id, "Stalker Portal", t("Portal adresi ile bağlan"), "▣")
        sourceFormPanel.addView(xtreamFields)
        sourceFormPanel.addView(m3uFields)
        sourceFormPanel.addView(macFields)
        sourceFormPanel.addView(connectButton, LinearLayout.LayoutParams(-1, dp(54)).apply {
            topMargin = dp(12)
        })
        form.addView(modeCards, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(sourceFormPanel, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(3), dp(2), dp(3), dp(8))
        })
        form.addView(sourceMode)
        selectSourceMode(when (editSource?.mode) {
            "m3u" -> m3u.id
            "mac" -> mac.id
            else -> xtream.id
        })

        // On the Settings > Sources page, render the form inline. Other entry
        // points (first launch / add / edit) keep the same form in a dialog.
        if (selectedType == "Ayarlar" && settingsPage == "sources" && editSource == null) {
            content.addView(form, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val dialog = AlertDialog.Builder(this).setView(form).create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                dialog.window?.setDimAmount(0.72f)
                dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.72f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            dialog.show()
        }
        form.post { sourceName.requestFocus() }
    }

    private fun openBackgroundPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, 7314)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7314 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val takeFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: Exception) {
            // Some document providers grant a temporary read permission only.
        }
        customBackgroundUri = uri.toString()
        prefs.edit().putString("custom_background_uri", customBackgroundUri).apply()
        applyThemeSurfaces()
        if (selectedType == "Ayarlar") {
            settingsPage = "themes"
            render()
        }
    }

    private fun loadCustomBackground(): android.graphics.drawable.Drawable? {
        val value = customBackgroundUri ?: return null
        return try {
            val uri = Uri.parse(value)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val targetW = (resources.displayMetrics.widthPixels * 1.5f).toInt().coerceAtLeast(1)
            val targetH = (resources.displayMetrics.heightPixels * 1.5f).toInt().coerceAtLeast(1)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null
            BitmapDrawable(resources, bitmap).apply { alpha = 118 }
        } catch (e: Exception) {
            android.util.Log.w("KaiserTV", "Custom background could not be loaded", e)
            null
        }
    }

    private fun clearCustomBackground() {
        customBackgroundUri = null
        prefs.edit().remove("custom_background_uri").apply()
        applyThemeSurfaces()
        settingsPage = "themes"
        render()
    }

    private fun applyThemeSurfaces() {
        val base = bg
        val isAnthraciteBlue = prefs.getString("theme_name", "Antrasit • Mavi") == "Antrasit • Mavi"
        val blend = if (isAnthraciteBlue) {
            Color.rgb(20, 22, 26)
        } else {
            Color.rgb(
                ((Color.red(base) * 0.72f) + (Color.red(accent) * 0.28f)).toInt().coerceIn(0, 255),
                ((Color.green(base) * 0.72f) + (Color.green(accent) * 0.28f)).toInt().coerceIn(0, 255),
                ((Color.blue(base) * 0.72f) + (Color.blue(accent) * 0.28f)).toInt().coerceIn(0, 255)
            )
        }
        fun shade(color: Int, factor: Float): Int = Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
        val rightBase = shade(blend, 0.76f)
        val themedSurface = if (gradientBackground) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(base, blend, rightBase))
        } else {
            GradientDrawable().apply { setColor(base) }
        }
        val layers = mutableListOf<android.graphics.drawable.Drawable>()
        layers.add(themedSurface)
        loadCustomBackground()?.let { layers.add(it) }
        rootLayout.background = android.graphics.drawable.LayerDrawable(layers.toTypedArray())
        mainContentScroll.setBackgroundColor(Color.TRANSPARENT)
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
            cornerRadius = dp(if (ovalButtons) 28 else radiusDp).toFloat()
            if (strokeColor != null) {
                setStroke((2 * resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
            }
        }

    private fun edit(hintText: String, secret: Boolean): EditText = EditText(this).apply {
        hint = hintText; textSize = 14f; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(muted)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        minHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        background = roundedDrawable(panel, 24, Color.rgb(48, 57, 73))
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
                when (sourceToRestore.mode) { "xtream" -> loadXtream(sourceToRestore, true); "mac" -> loadStalker(sourceToRestore, true); else -> loadM3u(sourceToRestore, true) }
            } else {
                render()
            }
        }
    }
    private fun activateSourceFromCache(source: SavedSource) {
        prefs.edit().putString("last_source_id", source.id)
            .putStringSet("selected_source_ids", setOf(source.id)).apply()
        selectedCategorySourceId = source.id
        selectedType = "Tümü"
        selectedGroup = "Tüm kategoriler"
        expandedCategory = null
        focusedCategoryName = null
        sidebarMode = "main"

        fun showCached(cached: List<MediaItem>) {
            if (cached.isEmpty()) {
                when (source.mode) {
                    "xtream" -> loadXtream(source, true)
                    "mac" -> loadStalker(source, true)
                    else -> loadM3u(source, true)
                }
                return
            }
            // Keep other sources in memory. The visible catalogue is filtered by
            // last_source_id, so switching back never needs to download it again.
            val knownIds = items.asSequence().filter { it.sourceId == source.id }
                .map { it.id }.toHashSet()
            items.addAll(cached.filter { it.id !in knownIds })
            isLoadingSource = false
            status.visibility = View.VISIBLE
            status.text = "${source.name}: kayıtlı içerik açıldı."
            render()
        }

        val memory = items.filter { it.sourceId == source.id }
        if (memory.isNotEmpty()) {
            showCached(memory)
        } else {
            status.visibility = View.VISIBLE
            status.text = "${source.name}: kayıtlı içerik açılıyor…"
            thread(name = "kaiser-source-cache-restore") {
                val disk = try { readCachedItems(source.id) } catch (e: Exception) {
                    android.util.Log.w("KaiserTV", "Source cache restore failed for ${source.name}", e)
                    emptyList()
                }
                runOnUiThread { showCached(disk) }
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
            AlertDialog.Builder(this).setTitle(s.name).setItems(arrayOf("Aç (önbellekten)", "Yenile", "Sil")) { _, action ->
                when (action) {
                    0 -> {
                        fun openCachedSource(cachedItems: List<MediaItem>) {
                            if (cachedItems.isNotEmpty()) {
                                items.removeAll { it.sourceId == s.id }
                                items.addAll(cachedItems)
                                prefs.edit().putString("last_source_id", s.id).remove("selected_source_ids").apply()
                                selectedCategorySourceId = s.id
                                selectedType = "Tümü"
                                selectedGroup = "Tüm kategoriler"
                                sidebarMode = "main"
                                isLoadingSource = false
                                status.visibility = View.VISIBLE
                                status.text = "${s.name}: kayıtlı içerik açıldı."
                                render()
                            } else {
                                when (s.mode) {
                                    "xtream" -> loadXtream(s, true)
                                    "mac" -> loadStalker(s, true)
                                    else -> loadM3u(s, true)
                                }
                            }
                        }
                        val inMemory = items.filter { it.sourceId == s.id }
                        if (inMemory.isNotEmpty()) {
                            openCachedSource(inMemory)
                        } else {
                            status.visibility = View.VISIBLE
                            status.text = "${s.name}: kayıtlı içerik aranıyor…"
                            thread(name = "kaiser-source-cache-restore") {
                                val diskCache = try { readCachedItems(s.id) } catch (e: Exception) {
                                    android.util.Log.w("KaiserTV", "Source cache restore failed for ${s.name}", e)
                                    emptyList()
                                }
                                runOnUiThread { openCachedSource(diskCache) }
                            }
                        }
                    }
                    1 -> {
                        prefs.edit().putString("last_source_id", s.id).remove("selected_source_ids").apply()
                        when (s.mode) {
                            "xtream" -> loadXtream(s, true)
                            "mac" -> loadStalker(s, true)
                            else -> loadM3u(s, true)
                        }
                    }
                    2 -> { savedSources.removeAll { it.id == s.id }; persistSources(); items.removeAll { it.sourceId == s.id }; render() }
                }
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

    private fun showConnectionError(sourceName: String, message: String) {
        val dialog = AlertDialog.Builder(this).create()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = roundedDrawable(panel, 18, accent)
        }
        panel.addView(TextView(this).apply {
            text = "⚠  ${t("Bağlantı hatası")}"
            textSize = 21f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(this).apply {
            text = sourceName
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(accentBright)
            setPadding(0, dp(12), 0, dp(5))
        })
        panel.addView(TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(muted)
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, 0)
        }
        actions.addView(makeButton(t("Kapat"), false) { dialog.dismiss() },
            LinearLayout.LayoutParams(dp(112), dp(44)).apply { setMargins(dp(6), 0, 0, 0) })
        actions.addView(makeButton(t("Tekrar dene"), true) {
            dialog.dismiss()
            val source = savedSources.firstOrNull { it.name == sourceName }
            if (source != null) {
                when (source.mode) {
                    "xtream" -> loadXtream(source, false)
                    "mac" -> loadStalker(source, false)
                    else -> loadM3u(source, false)
                }
            }
        }, LinearLayout.LayoutParams(dp(154), dp(44)).apply { setMargins(dp(6), 0, 0, 0) })
        panel.addView(actions)
        dialog.setView(panel)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun normalizePosterMatchName(value: String): String {
        val normalized = java.text.Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\[[^]]*]|\\([^)]*\\b(?:19|20)\\d{2}\\b[^)]*\\)"), " ")
            .replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        return normalized
    }

    // Optional poster enrichment for M3U sources that use the same Xtream
    // account. Credentials are read from the user's saved M3U URL at runtime;
    // they are never embedded in source code or sent to a third party.
    private fun enrichM3uMetadata(source: SavedSource) {
        // The M3U playlist remains the playback source. Xtream API is used only
        // as optional metadata for the same account (posters and VOD categories).
        thread(name = "kaiser-m3u-metadata") {
            try {
                val uri = android.net.Uri.parse(source.address)
                val user = uri.getQueryParameter("username").orEmpty()
                val pass = uri.getQueryParameter("password").orEmpty()
                val scheme = uri.scheme?.takeIf { it == "http" || it == "https" } ?: return@thread
                val authority = uri.encodedAuthority?.takeIf { it.isNotBlank() } ?: return@thread
                if (user.isBlank() || pass.isBlank()) return@thread
                val base = "$scheme://$authority"
                val u = URLEncoder.encode(user, "UTF-8")
                val p = URLEncoder.encode(pass, "UTF-8")
                val api = "$base/player_api.php?username=$u&password=$p"

                fun categoryMap(action: String): Map<String, String> {
                    val result = LinkedHashMap<String, String>()
                    forEachXtreamJsonObject("$api&action=$action") { item ->
                        val id = item.optString("category_id").trim()
                        val title = item.optString("category_name").trim()
                        if (id.isNotBlank() && title.isNotBlank()) result[id] = title
                    }
                    return result
                }

                val moviePosters = HashMap<String, Pair<String, String>>(2048)
                forEachXtreamJsonObject("$api&action=get_vod_streams") { item ->
                    val title = item.optString("name").ifBlank { item.optString("title") }
                    val poster = item.optString("movie_image").ifBlank {
                        item.optString("cover_big").ifBlank { item.optString("cover") }
                    }.ifBlank { item.optString("stream_icon") }
                    val key = normalizePosterMatchName(title)
                    if (key.length >= 3 && poster.startsWith("http", true)) {
                        val category = item.optString("category_name").ifBlank { item.optString("category_id") }
                        moviePosters.putIfAbsent(key, poster to category)
                    }
                }

                val seriesCategories = try { categoryMap("get_series_categories") } catch (e: Exception) {
                    android.util.Log.w("KaiserTV", "Optional M3U series categories unavailable.", e)
                    emptyMap()
                }
                val seriesMetadata = HashMap<String, Pair<String, String>>(1024)
                try {
                    forEachXtreamJsonObject("$api&action=get_series") { item ->
                        val title = item.optString("name").ifBlank { item.optString("title") }
                        val poster = item.optString("cover").ifBlank {
                            item.optString("cover_big").ifBlank { item.optString("movie_image") }
                        }
                        val key = normalizePosterMatchName(title)
                        if (key.length >= 3) {
                            val category = item.optString("category_name").ifBlank {
                                seriesCategories[item.optString("category_id")].orEmpty()
                            }
                            val validPoster = if (poster.startsWith("//")) "https:$poster" else poster
                            seriesMetadata.putIfAbsent(key, validPoster to category)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("KaiserTV", "Optional M3U series metadata unavailable.", e)
                }

                var changed = false
                synchronized(items) {
                    for (index in items.indices) {
                        val item = items[index]
                        if (item.sourceId != source.id) continue
                        val key = normalizePosterMatchName(item.name)
                        val metadata = when (item.type) {
                            "Filmler" -> moviePosters[key]
                            "Diziler" -> if (item.id.startsWith("m3u-series:")) seriesMetadata[key] else null
                            else -> null
                        } ?: continue
                        val poster = metadata.first
                        val category = metadata.second
                        val updatedIcon = if (poster.startsWith("http", true)) poster else item.icon
                        val updatedGroup = category.takeIf { it.isNotBlank() && !it.all(Char::isDigit) } ?: item.group
                        if (updatedIcon != item.icon || updatedGroup != item.group) {
                            items[index] = item.copy(icon = updatedIcon, group = updatedGroup)
                            changed = true
                        }
                    }
                }
                if (changed) {
                    saveCachedItems()
                    runOnUiThread {
                        if (prefs.getString("last_source_id", "") == source.id) render()
                    }
                    android.util.Log.i("KaiserTV", "Optional M3U metadata enrichment updated matching VOD artwork/categories.")
                }
            } catch (e: Exception) {
                android.util.Log.w("KaiserTV", "Optional M3U metadata enrichment unavailable; playlist remains usable.", e)
            }
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
                // Stream large M3U playlists line-by-line to avoid allocating one huge response String.
                val parsed = requestM3uWithAutoPlus(source.address).map { it.copy(sourceId = source.id) }
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
                    publishHomeChannel()
                    tryOpenPendingTvHomeItem()
                    // Artwork lookup runs after the playlist is already usable so
                    // it cannot delay TV/Movies/Series startup.
                    enrichM3uMetadata(source)
                }
            } catch (e: Exception) { runOnUiThread {
                loadProgress = 0
                isLoadingSource = false
                status.text = "${source.name}: ${friendlyLoadError(e)}"
                render()
                showConnectionError(source.name, friendlyLoadError(e))
            } }
        }
    }

    private fun stalkerApiUrl(source: SavedSource, params: Map<String, String>): String {
        // Accept either a portal root (/stalker_portal or /c) or a full
        // server/load.php URL. Do not accidentally request /server/load.php/server/load.php.
        val entered = source.address.trim().trimEnd('/')
        val base = when {
            entered.endsWith("/server/load.php", ignoreCase = true) ->
                entered.substringBeforeLast("/server/load.php")
            entered.endsWith("/c", ignoreCase = true) ->
                entered.dropLast(2).trimEnd('/')
            else -> entered
        }
        val endpoint = if (entered.endsWith("/server/load.php", ignoreCase = true)) entered
            else "$base/server/load.php"
        val allParams = LinkedHashMap(params)
        if (!allParams.containsKey("JsHttpRequest")) allParams["JsHttpRequest"] = "1-xml"
        val query = allParams.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        return "$endpoint?$query"
    }

    private fun stalkerRequest(source: SavedSource, token: String, params: Map<String, String>): JSONObject {
        val address = stalkerApiUrl(source, params)
        // Keep portal session cookies isolated per saved source. Some Stalker servers
        // issue a fresh PHP/session cookie during handshake and require it on every page.
        val cookieKey = "stalker_session_cookies_${source.id}"
        val cookieValues = LinkedHashMap<String, String>()
        cookieValues["mac"] = source.username.trim()
        cookieValues["stb_lang"] = "en"
        cookieValues["timezone"] = "Europe%2FIstanbul"
        prefs.getString(cookieKey, "").orEmpty().split(";").forEach { part ->
            val split = part.trim().split("=", limit = 2)
            if (split.size == 2 && split[0].isNotBlank()) cookieValues[split[0]] = split[1]
        }
        cookieValues["mac"] = source.username.trim()
        val cookieHeader = cookieValues.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val conn = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG250")
            setRequestProperty("X-User-Agent", "Model: MAG250; Link: WiFi; MAC: ${source.username.trim()}")
            setRequestProperty("Accept", "application/json, text/javascript, */*")
            setRequestProperty("Referer", source.address.trimEnd('/') + "/")
            setRequestProperty("Cookie", cookieHeader)
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            useCaches = false
        }
        try {
            val code = conn.responseCode
            // Merge Set-Cookie name/value pairs for the next request; ignore cookie attributes.
            val updatedCookies = LinkedHashMap(cookieValues)
            conn.headerFields?.filterKeys { it != null && it.equals("Set-Cookie", ignoreCase = true) }
                ?.values?.flatten()?.forEach { header ->
                    val pair = header.substringBefore(";").trim().split("=", limit = 2)
                    if (pair.size == 2 && pair[0].isNotBlank()) updatedCookies[pair[0]] = pair[1]
                }
            if (updatedCookies.size > 3) {
                prefs.edit().putString(cookieKey, updatedCookies.entries
                    .filterNot { it.key.equals("mac", true) || it.key.equals("stb_lang", true) || it.key.equals("timezone", true) }
                    .joinToString("; ") { "${it.key}=${it.value}" }).apply()
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw Exception("Portal HTTP $code: ${raw.take(180)}")
            if (raw.isBlank()) throw Exception("Portal boş yanıt döndürdü.")
            val clean = raw.trim().removePrefix("/*").removeSuffix("*/").trim()
            val root = try { JSONObject(clean) } catch (_: Exception) {
                val first = clean.indexOf('{')
                val last = clean.lastIndexOf('}')
                if (first >= 0 && last > first) JSONObject(clean.substring(first, last + 1))
                else throw Exception("Portal JSON yerine beklenmeyen yanıt döndürdü.")
            }
            val js = root.opt("js")
            return when (js) {
                is JSONObject -> js
                is JSONArray -> JSONObject().put("data", js)
                is String -> try {
                    val parsed = js.trim()
                    when {
                        parsed.startsWith("[") -> JSONObject().put("data", JSONArray(parsed))
                        parsed.startsWith("{") -> JSONObject(parsed)
                        else -> root
                    }
                } catch (_: Exception) { root }
                else -> root
            }
        } finally { conn.disconnect() }
    }

    private fun stalkerHandshake(source: SavedSource): String {
        val result = stalkerRequest(source, "", mapOf(
            "type" to "stb", "action" to "handshake", "token" to ""
        ))
        val token = result.optString("token").ifBlank { result.optString("access_token") }
        if (token.isBlank()) throw Exception("Portal oturum anahtarı döndürmedi. MAC adresinin portalda yetkili olduğunu kontrol edin.")
        return token
    }

    private fun stalkerArray(response: JSONObject, vararg keys: String): JSONArray {
        for (key in keys) {
            response.optJSONArray(key)?.let { return it }
        }
        val dataValue = response.opt("data")
        if (dataValue is JSONArray) return dataValue
        if (dataValue is String) {
            val text = dataValue.trim()
            if (text.startsWith("[")) {
                try { return JSONArray(text) } catch (_: Exception) { }
            }
            if (text.startsWith("{")) {
                try {
                    val nested = JSONObject(text)
                    for (key in keys) nested.optJSONArray(key)?.let { return it }
                    nested.optJSONArray("data")?.let { return it }
                } catch (_: Exception) { }
            }
        }
        val data = response.optJSONObject("data")
        if (data != null) {
            for (key in keys) data.optJSONArray(key)?.let { return it }
            data.optJSONArray("data")?.let { return it }
        }
        return JSONArray()
    }

    private fun appendStalkerSeriesEndpointDiagnostic(entry: String) {
        synchronized(this) {
            val previous = prefs.getString("last_stalker_series_endpoint_diagnostics", "").orEmpty()
            prefs.edit().putString(
                "last_stalker_series_endpoint_diagnostics",
                (previous + "\n" + entry).trim().takeLast(8000)
            ).apply()
        }
    }

    private fun showStalkerSeriesEndpointDiagnostics() {
        val report = prefs.getString("last_stalker_series_endpoint_diagnostics", "").orEmpty()
        val body = TextView(this).apply {
            text = report.ifBlank { "Henüz bölüm API tanılaması yok. Stalker kaynağında bir dizi açıp tekrar deneyin." }
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Stalker bölüm API tanılaması")
            .setView(scroll)
            .setNeutralButton("Kopyala", null)
            .setPositiveButton("Kapat", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Stalker bölüm API tanılaması", report))
                android.widget.Toast.makeText(this, "Bölüm API tanılaması kopyalandı.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun showStalkerDiagnostics() {
        val catalogReport = prefs.getString("last_stalker_catalog_diagnostics", "").orEmpty()
        val endpointReport = prefs.getString("last_stalker_series_endpoint_diagnostics", "").orEmpty()
        val report = buildString {
            if (catalogReport.isNotBlank()) append("STALKER KATALOG TANILAMASI\n").append(catalogReport)
            if (endpointReport.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("STALKER DİZİ/BÖLÜM API TANILAMASI\\n").append(endpointReport)
            }
        }
        if (report.isBlank()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Stalker tanılama")
                .setMessage("Henüz tanılama kaydı yok. MAC kaynağını yeniden yükleyin.")
                .setPositiveButton("Tamam", null).show()
            return
        }
        val body = TextView(this).apply {
            text = report
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Stalker tanılama")
            .setView(scroll)
            .setNeutralButton("Kopyala", null)
            .setPositiveButton("Kapat", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Stalker tanılama", report))
                android.widget.Toast.makeText(this, "Tanılama kopyalandı.", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun loadStalker(source: SavedSource, append: Boolean) {
        // Pin every screen/filter to the newly activated source before loading starts.
        // Keep other sources cached in memory, but never let their categories leak into this view.
        prefs.edit()
            .putString("last_source_id", source.id)
            .putStringSet("selected_source_ids", setOf(source.id))
            .apply()
        selectedCategorySourceId = source.id
        selectedType = "Tümü"
        selectedGroup = "Tüm kategoriler"
        expandedCategory = null
        focusedCategoryName = null
        isLoadingSource = true
        loadProgress = 0
        updateLoadingMessage( "${source.name}: Stalker bağlantısı kuruluyor…")
        render()
        thread(name = "kaiser-stalker-sections") {
            val failed = java.util.Collections.synchronizedList(ArrayList<String>())
            val sectionDiagnostics = java.util.Collections.synchronizedList(ArrayList<String>())
            var anySectionLoaded = false
            var vodSeriesCount = 0
            // Release the full-screen loader as soon as the first usable category page arrives.
            // Remaining Stalker pages continue downloading in the background.
            val catalogueUiReleased = java.util.concurrent.atomic.AtomicBoolean(false)
            try {
                runOnUiThread { updateLoadingMessage( "${source.name}: Portal yanıtı bekleniyor…") }
                val token = stalkerHandshake(source)
                android.util.Log.i("KaiserTV", "Stalker handshake completed for ${source.name}")
                runOnUiThread { updateLoadingMessage( "${source.name}: Oturum açıldı, TV kategorileri alınıyor…") }

                fun extractArray(response: JSONObject): JSONArray {
                    val direct = stalkerArray(response, "data", "categories", "genres", "channels", "items", "results")
                    if (direct.length() > 0) return direct
                    val js = response.optJSONObject("js")
                    if (js != null) {
                        val nested = stalkerArray(js, "data", "categories", "genres", "channels", "items", "results")
                        if (nested.length() > 0) return nested
                    }
                    return direct
                }

                fun responseSummary(response: JSONObject, array: JSONArray): String {
                    val keys = response.keys().asSequence().joinToString(",")
                    val first = array.optJSONObject(0)
                    val firstKeys = first?.keys()?.asSequence()?.joinToString(",") ?: "yok"
                    val dataType = response.opt("data")?.javaClass?.simpleName ?: "yok"
                    val jsType = response.opt("js")?.javaClass?.simpleName ?: "yok"
                    val pageMeta = listOf("total_items", "max_page_items", "selected_item", "cur_page")
                        .joinToString(",") { key -> "$key=${response.optString(key, "-")}" }
                    val sampleIds = (0 until minOf(array.length(), 3)).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        item.optString("id").ifBlank { item.optString("ch_id") }
                            .ifBlank { item.optString("movie_id") }.ifBlank { item.optString("series_id") }
                    }.joinToString(",")
                    return "keys=[$keys], data=$dataType, js=$jsType, adet=${array.length()}, meta=[$pageMeta], sampleIds=[$sampleIds], ilkKayitAlanlari=[$firstKeys]"
                }

                fun readCategories(type: String): List<Pair<String, String>> {
                    val response = stalkerRequest(source, token, mapOf("type" to type, "action" to "get_categories"))
                    val array = extractArray(response)
                    val result = ArrayList<Pair<String, String>>()
                    sectionDiagnostics.add("$type get_categories: ${responseSummary(response, array)}")
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val id = item.optString("id").ifBlank { item.optString("category_id") }
                            .ifBlank { item.optString("genre_id") }
                        val name = item.optString("title").ifBlank { item.optString("category_name") }
                            .ifBlank { item.optString("name") }
                        if (id.isNotBlank()) result.add(id to name.ifBlank { "Diğer" })
                    }
                    val categorySamples = (0 until minOf(array.length(), 3)).mapNotNull { i ->
                        val item = array.optJSONObject(i) ?: return@mapNotNull null
                        "id=${item.optString("id")},category_id=${item.optString("category_id")},genre_id=${item.optString("genre_id")},title=${item.optString("title")},name=${item.optString("name")}"
                    }.joinToString(" || ")
                    sectionDiagnostics.add("$type categories parsed=${result.size}/${array.length()} sample=[$categorySamples]")
                    return result
                }

                fun loadSection(
                    type: String,
                    label: String,
                    categoryAction: String,
                    splitVodSeries: Boolean = false,
                    preloadedCategories: List<Pair<String, String>>? = null
                ) {
                    try {
                        runOnUiThread { updateLoadingMessage( "${source.name}: $label kategorileri alınıyor…") }
                        val categories = preloadedCategories ?: if (type == "itv") {
                            val response = stalkerRequest(source, token, mapOf("type" to "itv", "action" to "get_genres"))
                            val arr = extractArray(response)
                            sectionDiagnostics.add("itv get_genres: ${responseSummary(response, arr)}")
                            (0 until arr.length()).mapNotNull { i ->
                                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                                val id = o.optString("id").ifBlank { o.optString("genre_id") }
                                if (id.isBlank()) null else id to o.optString("title").ifBlank { o.optString("name") }.ifBlank { "Diğer" }
                            }
                        } else readCategories(type)
                        val uiCategoryType = when (type) {
                            "itv" -> "Canlı TV"
                            "vod" -> "Filmler"
                            else -> "Diziler"
                        }
                        synchronized(providerCategoryNames) {
                            providerCategoryNames[uiCategoryType] = categories.map { it.second }.distinct()
                        }
                        runOnUiThread { render() }

                        runOnUiThread { updateLoadingMessage( "${source.name}: $label yükleniyor…") }
                        val collected = ConcurrentHashMap<String, MediaItem>()
                        val categoryOrder = categories.mapIndexed { index, pair -> pair.second to index }.toMap()
                        fun orderedSnapshot(): List<MediaItem> = collected.values.sortedWith(
                            compareBy<MediaItem> { categoryOrder[it.group] ?: Int.MAX_VALUE }
                                .thenBy { it.name.lowercase(java.util.Locale.ROOT) }
                        )
                        val responseShape = java.util.concurrent.atomic.AtomicReference("liste isteği henüz yapılmadı")
                        // Avoid rewriting the entire growing catalogue after every few pages.
                        // Persist the first partial batch, then checkpoint only after 1,000 new items.
                        val lastCheckpointCount = java.util.concurrent.atomic.AtomicInteger(0)
                        val lastProgressUpdateAt = java.util.concurrent.atomic.AtomicLong(0L)

                        fun requestList(categoryId: String?, page: Int, onPageMeta: ((Int, Int) -> Unit)? = null): JSONArray {
                            val filterKey = if (type == "itv") "genre" else "category"
                            val alternateKey = if (type == "itv") "category" else "genre"
                            val base = linkedMapOf(
                                "type" to type,
                                "action" to "get_ordered_list",
                                "p" to page.toString(),
                                "fav" to "0"
                            )
                            // Number sorting is intended for live channels. Some portals reject it
                            // for VOD/series and return an empty catalogue without an HTTP error.
                            if (type == "itv") {
                                base["sortby"] = "number"
                            } else {
                                // Ministra-compatible VOD hierarchy parameters. Several portals
                                // return an empty list unless the hierarchy selectors are explicit.
                                base["movie_id"] = "0"
                                base["season_id"] = "0"
                                base["episode_id"] = "0"
                                base["sortby"] = "added"
                                base["hd"] = "0"
                                base["ended"] = "0"
                                base["search"] = ""
                            }
                            base[filterKey] = categoryId ?: "*"

                            val attempts = if (type == "itv") {
                                // Restore the exact request variants from the known-working TV build.
                                mutableListOf(
                                    LinkedHashMap(base),
                                    LinkedHashMap(base).apply {
                                        remove(filterKey)
                                        put(alternateKey, categoryId ?: "*")
                                    },
                                    LinkedHashMap(base).apply { remove("sortby") },
                                    linkedMapOf(
                                        "type" to type,
                                        "action" to "get_ordered_list",
                                        "p" to page.toString(),
                                        filterKey to (categoryId ?: "*")
                                    )
                                )
                            } else mutableListOf(
                                LinkedHashMap(base),
                                LinkedHashMap(base).apply {
                                    remove(filterKey)
                                    put(alternateKey, categoryId ?: "*")
                                },
                                LinkedHashMap(base).apply { remove("sortby") },
                                linkedMapOf(
                                    "type" to type,
                                    "action" to "get_ordered_list",
                                    "p" to page.toString(),
                                    filterKey to (categoryId ?: "*")
                                )
                            )
                            if (type != "itv" && page == 1) {
                                // Some VOD/series portals are zero-based and return an empty
                                // response for p=1. Retry p=0 only after the normal request
                                // variants have failed; this does not alter working TV loading.
                                attempts.add(LinkedHashMap(base).apply { put("p", "0") })
                                attempts.add(LinkedHashMap(base).apply {
                                    put("p", "0")
                                    remove(filterKey)
                                    put(alternateKey, categoryId ?: "*")
                                })
                            }
                            for ((index, params) in attempts.withIndex()) {
                                val response = stalkerRequest(source, token, params)
                                val arr = extractArray(response)
                                if (arr.length() > 0) {
                                    onPageMeta?.invoke(response.optInt("total_items", -1), response.optInt("max_page_items", -1))
                                }
                                responseShape.set(responseSummary(response, arr))
                                // Keep diagnostics compact: Live TV can span hundreds of pages
                                // and must not push the VOD/series results out of the dialog.
                                if (sectionDiagnostics.size < 100 &&
                                    ((type != "itv" && page <= 1 && categoryId == categories.firstOrNull()?.first) ||
                                     (type == "itv" && page == 1))
                                ) {
                                    sectionDiagnostics.add("$type get_ordered_list deneme=${index + 1} kategori=${categoryId ?: "*"} sayfa=$page: ${responseShape.get()}")
                                }
                                android.util.Log.d(
                                    "KaiserTV",
                                    "Stalker $type list attempt=${index + 1} category=${categoryId ?: "*"} page=$page count=${arr.length()} keys=${params.keys}"
                                )
                                if (arr.length() > 0) return arr
                            }
                            return JSONArray()
                        }

                        // This Stalker portal's confirmed working Live TV path is the
                        // single wildcard catalogue request. Do not fan out ITV requests by
                        // genre: this portal can return empty/incomplete data for those filters.
                        // VOD and Series continue loading every explicit provider category.
                        val targets = if (type == "itv" || categories.isEmpty()) {
                            listOf("*" to "Diğer")
                        } else {
                            categories.filterNot { it.first == "*" }
                        }
                        fun pageKeys(array: JSONArray): Set<String> {
                            return (0 until array.length()).mapNotNull { index ->
                                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                                val id = entry.optString("id").ifBlank { entry.optString("ch_id") }
                                    .ifBlank { entry.optString("movie_id") }.ifBlank { entry.optString("series_id") }
                                if (id.isNotBlank()) id else entry.toString()
                            }.toSet()
                        }

                        fun publishPartialSection(force: Boolean = false) {
                            val currentCount = collected.size
                            val previousCheckpoint = lastCheckpointCount.get()
                            if (!force && currentCount - previousCheckpoint < 1000) return
                            if (!lastCheckpointCount.compareAndSet(previousCheckpoint, currentCount)) return
                            val snapshot = orderedSnapshot()
                            if (snapshot.isEmpty()) return
                            val publishTypes = if (splitVodSeries) setOf("Filmler", "Diziler") else setOf(label)
                            val publishItems = snapshot.filter { it.type in publishTypes }
                            if (publishItems.isEmpty()) return
                            runOnUiThread {
                                if (catalogueUiReleased.compareAndSet(false, true)) {
                                    isLoadingSource = false
                                    status.visibility = View.VISIBLE
                                    status.text = "${source.name}: içerikler açıldı, kalan katalog arka planda yükleniyor…"
                                }
                                if (splitVodSeries) {
                                    // VOD and dedicated Series now load concurrently. A VOD
                                    // partial update must never erase Series already published
                                    // by the dedicated endpoint.
                                    val currentSeries = items.filter { it.sourceId == source.id && it.type == "Diziler" }
                                    val mergedSeries = (currentSeries + publishItems.filter { it.type == "Diziler" })
                                        .distinctBy { it.id.ifBlank { it.url } }
                                    items.removeAll { it.sourceId == source.id && it.type == "Filmler" }
                                    items.addAll(publishItems.filter { it.type == "Filmler" })
                                    items.removeAll { it.sourceId == source.id && it.type == "Diziler" }
                                    items.addAll(mergedSeries)
                                } else {
                                    items.removeAll { it.sourceId == source.id && it.type in publishTypes }
                                    items.addAll(publishItems)
                                }
                                saveCachedItems()
                                anySectionLoaded = true
                                if (selectedType in publishTypes || selectedType == "Tümü") render()
                                updateLoadingMessage("${source.name}: ${label} yükleniyor… (${publishItems.size} içerik hazır)")
                            }
                        }

                        fun consumePage(arr: JSONArray, categoryName: String) {
                            if (type == "vod" && synchronized(sectionDiagnostics) { sectionDiagnostics.none { it.startsWith("vod sample fields:") } }) {
                                val samples = (0 until minOf(arr.length(), 3)).mapNotNull { index ->
                                    val entry = arr.optJSONObject(index) ?: return@mapNotNull null
                                    "id=${entry.optString("id")},video_id=${entry.optString("video_id")},movie_id=${entry.optString("movie_id")},series_id=${entry.optString("series_id")},name=${entry.optString("name").take(90)}," +
                                        "is_series=${entry.opt("is_series")},is_movie=${entry.opt("is_movie")}," +
                                        "series=${entry.opt("series")},category_id=${entry.opt("category_id")}," +
                                        "genre_id=${entry.opt("genre_id")}"
                                }.joinToString(" || ")
                                sectionDiagnostics.add("vod sample fields: $samples")
                            }
                            for (i in 0 until arr.length()) {
                                val entry = arr.optJSONObject(i) ?: continue
                                val id = entry.optString("id").ifBlank { entry.optString("ch_id") }
                                    .ifBlank { entry.optString("movie_id") }.ifBlank { entry.optString("series_id") }
                                if (id.isBlank()) continue
                                val isSeriesFlag = entry.optString("is_series").trim().lowercase()
                                val isMovieFlag = entry.optString("is_movie").trim().lowercase()
                                val seriesField = entry.opt("series")
                                // An empty JSON array ("[]") is a common value for movie records.
                                // It must not be treated as proof that the item is a series.
                                val hasSeriesEntries = when (seriesField) {
                                    is JSONArray -> seriesField.length() > 0
                                    is JSONObject -> seriesField.length() > 0
                                    null, JSONObject.NULL -> false
                                    else -> seriesField.toString().trim().let {
                                        it.isNotEmpty() && it != "0" && !it.equals("false", true) &&
                                            it != "[]" && it != "{}" && !it.equals("null", true)
                                    }
                                }
                                val explicitSeries = isSeriesFlag in setOf("1", "true", "yes") ||
                                    entry.optBoolean("is_series", false) ||
                                    entry.optString("type").equals("series", ignoreCase = true) ||
                                    hasSeriesEntries
                                val explicitMovie = isMovieFlag in setOf("1", "true", "yes") ||
                                    entry.optBoolean("is_movie", false)
                                val explicitNotMovie = isMovieFlag in setOf("0", "false", "no")
                                val isSeriesEntry = splitVodSeries && (
                                    explicitSeries || (!explicitMovie && explicitNotMovie)
                                )
                                val itemType = if (isSeriesEntry) "Diziler" else label
                                val name = entry.optString("name").ifBlank { entry.optString("title") }.ifBlank { "$itemType $id" }
                                // Language-prefixed entries (TR:, FR:, EN:, etc.) are linear channel labels,
                                // not VOD titles. Keep them out of Stalker movie/series catalogues.
                                if (type == "vod" && isLanguagePrefixedChannel(name)) continue
                                val catId = entry.optString("tv_genre_id").ifBlank { entry.optString("category_id") }
                                    .ifBlank { entry.optString("genre_id") }
                                val catName = categories.firstOrNull { it.first == catId }?.second
                                    ?: entry.optString("category_name").takeIf { it.isNotBlank() }
                                    ?: entry.optString("genre").takeIf { it.isNotBlank() }
                                    ?: categoryName
                                // Stalker VOD catalogues commonly expose movie artwork in "pic".
                                // Keep existing fallbacks for portals using alternate field names.
                                val image = entry.optString("pic").ifBlank { entry.optString("screenshot_uri") }
                                    .ifBlank { entry.optString("logo") }.ifBlank { entry.optString("cover") }
                                    .ifBlank { entry.optString("picture") }
                                val artwork = when {
                                    image.startsWith("//") -> "https:$image"
                                    image.startsWith("/") -> source.address.trimEnd('/') + image
                                    else -> image
                                }
                                val rawCommand = entry.optString("cmd")
                                val encodedCommand = URLEncoder.encode(rawCommand, "UTF-8")
                                // On some Stalker series catalogues, "id" is the catalogue row
                                // while "video_id" is the parent movie/series ID expected by
                                // the season lookup (movie_id). Prefer the explicit parent ID.
                                val portalSeriesId = if (isSeriesEntry) {
                                    entry.optString("video_id").ifBlank { entry.optString("movie_id") }.ifBlank { id }
                                } else id
                                val itemUrl = when {
                                    // Keep the portal-provided playback command. Stalker live TV
                                    // create_link must receive the original cmd, not a fabricated /ch/{id} URL.
                                    type == "itv" -> "stalker://$id?cmd=$encodedCommand"
                                    type == "vod" && isSeriesEntry -> "stalker-series://$portalSeriesId?cmd=$encodedCommand&category=${URLEncoder.encode(catId, "UTF-8")}&row_id=$id"
                                    type == "vod" -> "stalker-vod://$id?cmd=$encodedCommand&category=${URLEncoder.encode(catId, "UTF-8")}"
                                    else -> "stalker-series://$portalSeriesId?cmd=$encodedCommand&category=${URLEncoder.encode(catId, "UTF-8")}"
                                }
                                val seriesId = entry.optString("series_id").ifBlank { portalSeriesId }
                                collected["$itemType:$id"] = MediaItem(
                                    "stalker-$type:$id", name, itemUrl, catName, itemType, artwork,
                                    entry.optString("cmd").substringAfterLast('.').takeIf { it.length in 2..5 } ?: "mp4",
                                    if (itemType == "Diziler") seriesId else "", source.id,
                                    entry.optString("description").ifBlank { entry.optString("plot") },
                                    entry.optString("director"), entry.optString("rating"), entry.optString("year"),
                                    entry.optString("genre")
                                )
                            }
                        }

                        // Two category workers per VOD/series section; with three sections this caps concurrent portal requests at about six.
                        // Keep Stalker session traffic conservative; four concurrent
                        // category workers can overload portals and cause missing categories.
                        val categoryExecutor = Executors.newFixedThreadPool(if (type == "itv") 1 else 3)
                        try {
                            val categoryTasks = ArrayList<java.util.concurrent.Future<*>>()
                            for ((categoryId, categoryName) in targets) {
                                categoryTasks.add(categoryExecutor.submit {
                            if (type == "itv") {
                                // The portal reports 12,997 live channels at 14 per page.
                                // The old 100-page ceiling silently truncated the catalogue
                                // at exactly 1,400 channels (usually only the first country).
                                var reportedTotalItems = -1
                                var reportedPageSize = -1
                                val captureTvMeta: (Int, Int) -> Unit = { total, pageSize ->
                                    if (total > 0) reportedTotalItems = total
                                    if (pageSize > 0) reportedPageSize = pageSize
                                }
                                // Fetch page 1 first to learn the portal's real catalogue size.
                                // Then download the remaining pages in small parallel batches.
                                val firstPage = requestList(categoryId, 1, captureTvMeta)
                                if (firstPage.length() > 0) {
                                    consumePage(firstPage, categoryName)
                                    val lastPage = if (reportedTotalItems > 0 && reportedPageSize > 0) {
                                        ((reportedTotalItems + reportedPageSize - 1) / reportedPageSize).coerceIn(1, 10000)
                                    } else 10000
                                    sectionDiagnostics.add("itv sayfalama: toplam=$reportedTotalItems, sayfaBoyutu=$reportedPageSize, sonSayfa=$lastPage, paralel=3")
                                    publishPartialSection(force = true)

                                    if (lastPage > 1) {
                                        val pageExecutor = Executors.newFixedThreadPool(3)
                                        try {
                                            val nextPage = java.util.concurrent.atomic.AtomicInteger(2)
                                            val completedPages = java.util.concurrent.atomic.AtomicInteger(1)
                                            val workers = (0 until 3).map {
                                                pageExecutor.submit {
                                                    while (true) {
                                                        val page = nextPage.getAndIncrement()
                                                        if (page > lastPage) break
                                                        val arr = requestList(categoryId, page)
                                                        if (arr.length() > 0) consumePage(arr, categoryName)
                                                        val completed = completedPages.incrementAndGet()
                                                        if (completed % 10 == 0 || completed == lastPage) {
                                                            runOnUiThread {
                                                                updateLoadingMessage("${source.name}: $label yükleniyor… (${collected.size} kanal, $completed/$lastPage sayfa)")
                                                            }
                                                            publishPartialSection()
                                                        }
                                                    }
                                                }
                                            }
                                            workers.forEach { it.get() }
                                        } finally {
                                            pageExecutor.shutdownNow()
                                        }
                                    }
                                }
                            } else {
                                // This portal's diagnostics confirmed p=0 and p=1 return
                                // the same first page. Use p=1 directly to avoid doubling
                                // every category request during startup.
                                var reportedTotalItems = -1
                                var reportedPageSize = -1
                                val capturePageMeta: (Int, Int) -> Unit = { total, pageSize ->
                                    if (total > 0) reportedTotalItems = total
                                    if (pageSize > 0) reportedPageSize = pageSize
                                }
                                val firstPage = requestList(categoryId, 1, capturePageMeta)
                                if (firstPage.length() == 0) return@submit
                                val firstPageKey = pageKeys(firstPage)
                                var nextPage = 2
                                var lastPageNumber = 100
                                val firstPageKeys = mutableSetOf<Set<String>>()
                                consumePage(firstPage, categoryName)
                                if (firstPageKey.isNotEmpty()) firstPageKeys.add(firstPageKey)
                                // Show the first available VOD/series items immediately;
                                // continue collecting the remaining pages in the background.
                                publishPartialSection(force = true)

                                val totalPages = if (reportedTotalItems > 0 && reportedPageSize > 0) {
                                    ((reportedTotalItems + reportedPageSize - 1) / reportedPageSize).coerceAtLeast(1)
                                } else -1
                                if (totalPages > 0) {
                                    lastPageNumber = totalPages.coerceAtLeast(1)
                                    sectionDiagnostics.add(
                                        "$type sayfalama hedefi kategori=$categoryId: toplam=$reportedTotalItems, " +
                                            "sayfaBoyutu=$reportedPageSize, sonSayfa=$lastPageNumber"
                                    )
                                }

                                val seenPageKeys = firstPageKeys.toMutableSet()
                                // Stalker often returns a GLOBAL total_items value for every
                                // category. It is not a reliable per-category page count and can
                                // truncate smaller categories when used as a stopping boundary.
                                // Scan each category until an empty/repeated page. Keep a high safety ceiling so large provider categories are not silently truncated.
                                val effectiveLastPage = 2000
                                var page = nextPage
                                while (page <= effectiveLastPage) {
                                    val arr = requestList(categoryId, page, capturePageMeta)
                                    if (arr.length() == 0) break
                                    val currentPageKeys = pageKeys(arr)
                                    if (currentPageKeys.isNotEmpty() && !seenPageKeys.add(currentPageKeys)) {
                                        sectionDiagnostics.add("$type sayfalama durduruldu: kategori=$categoryId sayfa=$page daha önce alınan sayfayı tekrarladı")
                                        break
                                    }
                                    if (page <= 4 && sectionDiagnostics.count { it.startsWith("$type page fingerprint") } < 8) {
                                        sectionDiagnostics.add(
                                            "$type page fingerprint kategori=$categoryId sayfa=$page: " +
                                                "adet=${arr.length()}, ids=${currentPageKeys.take(5).joinToString(",")}"
                                        )
                                    }
                                    consumePage(arr, categoryName)
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    val lastUi = lastProgressUpdateAt.get()
                                    if (now - lastUi >= 1500L && lastProgressUpdateAt.compareAndSet(lastUi, now)) {
                                        runOnUiThread {
                                            updateLoadingMessage("${source.name}: $label yükleniyor… (${collected.size} içerik hazır)")
                                        }
                                    }
                                    // Do not stop on a short page: some portals return fewer
                                    // than ten records while more pages still remain. Empty or
                                    // repeated-page detection above safely ends the category scan.
                                    page++
                                    if (page % 5 == 0) {
                                        publishPartialSection()
                                    }
                                }
                            }
                                })
                            }
                            categoryTasks.forEach { it.get() }
                        } finally {
                            categoryExecutor.shutdownNow()
                        }

                        val sectionItems = orderedSnapshot()
                        if (splitVodSeries) {
                            val sampleFlags = sectionItems.take(8).joinToString(" || ") { item ->
                                "${item.name}:type=${item.type},url=${item.url.substringBefore('?')}"
                            }
                            sectionDiagnostics.add("VOD sınıflandırma örnekleri: $sampleFlags")
                            val movieItems = sectionItems.filter { it.type == "Filmler" }
                            val seriesItems = sectionItems.filter { it.type == "Diziler" }
                            vodSeriesCount = seriesItems.size
                            val diagnostic = "VOD: kategori=${categories.size}, film=${movieItems.size}, dizi=${seriesItems.size}, ${responseShape.get()}"
                            sectionDiagnostics.add(diagnostic)
                            android.util.Log.i("KaiserTV", "Stalker $diagnostic")
                            if (sectionItems.isEmpty()) {
                                failed.add("Filmler")
                                runOnUiThread { updateLoadingMessage("${source.name}: VOD listesi boş döndü; alternatif dizi kataloğu denenecek…") }
                                return
                            }
                            if (movieItems.isEmpty()) failed.add("Filmler")
                            runOnUiThread {
                                // Preserve series from the dedicated "series" loader;
                                // this VOD result may contain movies only or partial series.
                                val currentSeries = items.filter { it.sourceId == source.id && it.type == "Diziler" }
                                val mergedSeries = (currentSeries + seriesItems)
                                    .distinctBy { it.id.ifBlank { it.url } }
                                items.removeAll { it.sourceId == source.id && it.type == "Filmler" }
                                items.addAll(movieItems)
                                items.removeAll { it.sourceId == source.id && it.type == "Diziler" }
                                items.addAll(mergedSeries)
                                saveCachedItems()
                                anySectionLoaded = true
                                if (selectedType == "Filmler" || selectedType == "Diziler" || selectedType == "Tümü") render()
                                updateLoadingMessage("${source.name}: VOD hazır (${movieItems.size} film, ${mergedSeries.size} dizi).")
                            }
                            return
                        }

                        val diagnostic = "$label: kategori=${categories.size}, içerik=${sectionItems.size}, $responseShape"
                        sectionDiagnostics.add(diagnostic)
                        android.util.Log.i("KaiserTV", "Stalker $diagnostic")
                        if (sectionItems.isEmpty()) {
                            failed.add(label)
                            runOnUiThread { updateLoadingMessage( "${source.name}: $label boş döndü; sonraki bölüm yükleniyor…") }
                            return
                        }
                        runOnUiThread {
                            if (type == "series") {
                                // Merge the dedicated Series catalogue with any series
                                // already discovered in VOD; prefer the dedicated endpoint
                                // when the same source item appears in both responses.
                                val merged = (items.filter { it.sourceId == source.id && it.type == label } + sectionItems)
                                    .distinctBy { it.id.ifBlank { it.url } }
                                items.removeAll { it.sourceId == source.id && it.type == label }
                                items.addAll(merged)
                            } else {
                                items.removeAll { it.sourceId == source.id && it.type == label }
                                items.addAll(sectionItems)
                            }
                            saveCachedItems()
                            anySectionLoaded = true
                            if (type == "itv") {
                                selectedType = "Canlı TV"
                                selectedGroup = "Tüm kategoriler"
                                expandedCategory = null
                                sidebarMode = "main"
                                // Keep the loading screen active until TV, VOD and
                                // Series workers have all completed. Do not expose the home
                                // screen while the other catalogues are still being fetched.
                            } else if (selectedType == label || selectedType == "Tümü") render()
                            updateLoadingMessage( "${source.name}: $label hazır (${sectionItems.size} içerik).")
                        }
                    } catch (e: Exception) {
                        failed.add(label)
                        android.util.Log.e("KaiserTV", "Stalker $label failed", e)
                        runOnUiThread { updateLoadingMessage( "${source.name}: $label alınamadı; sonraki bölüm deneniyor…") }
                    }
                }

                // PHASE 1: Load Live TV alone, then publish it and open the app.
                val tvTask = thread(name = "kaiser-stalker-tv") {
                    loadSection("itv", "Canlı TV", "get_genres")
                }
                tvTask.join()

                val tvPublished = java.util.concurrent.CountDownLatch(1)
                runOnUiThread {
                    if (items.any { it.sourceId == source.id && it.type == "Canlı TV" }) {
                        hasLoadedSource = true
                        isLoadingSource = false
                        selectedType = "Canlı TV"
                        selectedGroup = "Tüm kategoriler"
                        sidebarMode = "main"
                        render()
                        publishHomeChannel()
                    }
                    updateLoadingMessage("${source.name}: TV hazır. Film ve dizi kategorileri alınıyor…")
                    tvPublished.countDown()
                }
                tvPublished.await()

                // PHASE 2: Fetch all category lists before requesting any VOD/series items.
                val vodCategories = readCategories("vod")
                val seriesCategories = readCategories("series")
                runOnUiThread {
                    updateLoadingMessage("${source.name}: ${vodCategories.size} film ve ${seriesCategories.size} dizi kategorisi hazır. İçerikler yükleniyor…")
                }

                // PHASE 3: Load the contents using the already-fetched category lists.
                val vodTask = thread(name = "kaiser-stalker-vod") {
                    loadSection("vod", "Filmler", "get_categories", splitVodSeries = true, preloadedCategories = vodCategories)
                }
                val seriesTask = thread(name = "kaiser-stalker-series") {
                    loadSection("series", "Diziler", "get_categories", preloadedCategories = seriesCategories)
                }
                vodTask.join()
                seriesTask.join()

                runOnUiThread {
                    isLoadingSource = false
                    if (!anySectionLoaded) {
                        updateLoadingMessage( "${source.name}: katalog alınamadı.")
                        render()
                        showConnectionError(source.name, "Kataloglar alınamadı: ${failed.joinToString()}")
                    } else {
                        hasLoadedSource = true
                        updateLoadingMessage("${source.name}: yükleme tamamlandı" +
                            if (failed.isNotEmpty()) " • Eksik: ${failed.joinToString()}" else "")
                        render()
                        publishHomeChannel()
                        run {
                            // Save compact Stalker diagnostics after every successful catalogue load
                            // so the first VOD records can be inspected even when other sections work.
                            val diagnosticText = sectionDiagnostics
                                .sortedBy { if (it.startsWith("itv ")) 1 else 0 }
                                .take(80)
                                .joinToString(" | ")
                                .take(12000)
                            prefs.edit().putString("last_stalker_catalog_diagnostics", diagnosticText).apply()
                            android.util.Log.w("KaiserTV", "Stalker catalogue diagnostics: $diagnosticText")
                            runOnUiThread {
                                updateLoadingMessage("Stalker tanılama kaydedildi. Ayrıntılar Ayarlar bölümünde.")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("KaiserTV", "Stalker Portal load failed", e)
                runOnUiThread {
                    isLoadingSource = false
                    updateLoadingMessage( "${source.name}: ${friendlyLoadError(e)}")
                    render()
                    showConnectionError(source.name, friendlyLoadError(e))
                }
            }
        }
    }

    private fun resolveStalkerStream(source: SavedSource, channelId: String, portalCommand: String): String {
        val token = stalkerHandshake(source)
        // The cmd field returned with the ITV catalogue entry is the portal's actual
        // channel locator (often a prefixed ffmpeg/ffrt command). Do not synthesize
        // http://localhost/ch/{id}; many Ministra portals reject that placeholder.
        val command = portalCommand.trim().ifBlank { "ffmpeg http://localhost/ch/$channelId" }
        val response = stalkerRequest(source, token, mapOf(
            "type" to "itv", "action" to "create_link", "cmd" to command,
            "series" to "", "forced_storage" to "", "disable_ad" to "0"
        ))
        val cmd = response.optString("cmd").ifBlank { response.optString("url") }
        if (cmd.isBlank()) {
            val error = response.optString("error").ifBlank { response.optString("message") }
            throw Exception(error.ifBlank { "Portal yayın adresi döndürmedi." })
        }
        return cmd.removePrefix("ffmpeg ").removePrefix("ffrt ").trim()
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

                var firstBatchApplied = false
                var totalLoaded = 0

                // Fetch and publish one catalogue section at a time. Once Live TV is ready,
                // the user can navigate while Movies and Series continue loading in background.
                fun categoryMap(action: String, label: String): Map<String, String> {
                    val result = LinkedHashMap<String, String>()
                    return try {
                        forEachXtreamJsonObject("$api&action=$action", onAttemptStart = { result.clear() }) { item ->
                            val id = item.optString("category_id")
                            val name = item.optString("category_name")
                            if (id.isNotBlank() && name.isNotBlank()) result[id] = name
                        }
                        result
                    } catch (e: Exception) {
                        failedParts.add("$label kategorileri")
                        android.util.Log.w("KaiserTV", "$label category list failed", e)
                        emptyMap()
                    }
                }

                val sections: List<Triple<String, String, Pair<String, String>>> = listOf(
                    Triple("get_live_streams", "Canlı TV", "get_live_categories" to "TV"),
                    Triple("get_vod_streams", "Filmler", "get_vod_categories" to "Film"),
                    Triple("get_series", "Diziler", "get_series_categories" to "Dizi")
                )

                sections.forEachIndexed { sectionIndex, (action, type, categorySpec) ->
                    try {
                        val sectionStart = 12 + sectionIndex * 29
                        runOnUiThread {
                            loadProgress = sectionStart
                            status.text = "${source.name}: $type kategorileri alınıyor…"
                        }
                        val categories = categoryMap(categorySpec.first, categorySpec.second)
                        runOnUiThread {
                            loadProgress = sectionStart + 8
                            status.text = "${source.name}: $type yükleniyor…"
                        }

                        var sectionCount = 0
                        var batch = ArrayList<MediaItem>(500)

                        fun publishBatch(finalBatch: Boolean) {
                            val published = batch.toList()
                            batch.clear()
                            val isFirstBatch = !firstBatchApplied
                            if (isFirstBatch) firstBatchApplied = true
                            totalLoaded += published.size
                            val loadedInSection = sectionCount
                            runOnUiThread {
                                if (isFirstBatch) {
                                    items.removeAll { it.sourceId == source.id }
                                    selectedType = "Tümü"
                                    selectedGroup = "Tüm kategoriler"
                                    expandedCategory = null
                                    sidebarMode = "main"
                                    xtreamBase = base
                                    xtreamUser = user
                                    xtreamPass = pass
                                }
                                if (published.isNotEmpty()) items.addAll(published)
                                if (finalBatch) saveCachedItems()

                                if (type == "Canlı TV" && finalBatch) {
                                    // Make TV available as soon as its streamed catalogue is complete.
                                    isLoadingSource = false
                                    hasLoadedSource = true
                                    render()
                                    publishHomeChannel()
                                    tryOpenPendingTvHomeItem()
                                } else if (finalBatch && published.isNotEmpty() && (selectedType == type || selectedType == "Tümü")) {
                                    render()
                                }
                                if (finalBatch) {
                                    status.text = "${source.name}: $type hazır (${sectionCount} içerik)."
                                } else {
                                    status.text = "${source.name}: $type yükleniyor… ${loadedInSection} içerik"
                                }
                            }
                        }

                        forEachXtreamJsonObject("$api&action=$action") { o ->
                            val id = o.optString(
                                if (action == "get_series") "series_id" else "stream_id", ""
                            ).trim()
                            if (id.isNotBlank()) {
                                val name = o.optString("name", "İsimsiz içerik").ifBlank { "İsimsiz içerik" }
                                // Provider language tags identify channel variants, never movie/series cards.
                                if (type != "Canlı TV" && isLanguagePrefixedChannel(name)) return@forEachXtreamJsonObject
                                val categoryId = o.optString("category_id")
                                val group = o.optString("category_name").takeIf { it.isNotBlank() }
                                    ?: categories[categoryId] ?: "Diğer"
                                val ext = o.optString(
                                    "container_extension", if (type == "Canlı TV") "ts" else "mp4"
                                ).ifBlank { if (type == "Canlı TV") "ts" else "mp4" }
                                val plot = o.optString("plot", o.optString("description", ""))
                                val actors = o.optString("cast", o.optString("actors", ""))
                                val artworkKeys = when (type) {
                                    "Canlı TV" -> listOf("stream_icon", "logo", "cover", "cover_big", "movie_image")
                                    "Filmler" -> listOf("movie_image", "cover_big", "cover", "poster", "stream_icon")
                                    else -> listOf("cover_big", "cover", "movie_image", "poster", "stream_icon")
                                }
                                val rawArtwork = artworkKeys.asSequence()
                                    .map { key -> o.optString(key, "").trim() }
                                    .firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
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
                                batch.add(
                                    MediaItem(
                                        id, name, streamUrl, group, type, artwork,
                                        ext, if (type == "Diziler") id else "",
                                        source.id, plot, actors,
                                        o.optString("rating", o.optString("vote_average", "")),
                                        o.optString("releaseDate", o.optString("year", "")),
                                        o.optString("genre", "")
                                    )
                                )
                                sectionCount++
                                if (batch.size >= 500) publishBatch(finalBatch = false)
                                if (sectionCount % 1500 == 0) {
                                    val progress = (sectionStart + 8 + (sectionCount / 1500))
                                        .coerceIn(sectionStart + 8, sectionStart + 27)
                                    runOnUiThread {
                                        loadProgress = progress
                                        status.text = "${source.name}: $type yükleniyor… ${sectionCount} içerik"
                                    }
                                }
                            }
                        }
                        // Flush the last partial batch and persist once per completed section.
                        publishBatch(finalBatch = true)
                    } catch (e: Exception) {
                        failedParts.add(type)
                        android.util.Log.e("KaiserTV", "$type list failed", e)
                    }
                }

                runOnUiThread {
                    isLoadingSource = false
                    if (!firstBatchApplied) {
                        loadProgress = 0
                        status.visibility = View.VISIBLE
                        status.text = "${source.name}: kullanılabilir içerik bulunamadı."
                        render()
                        showConnectionError(source.name, "İçerik listeleri alınamadı: ${failedParts.distinct().joinToString()}")
                    } else {
                        loadProgress = 100
                        status.visibility = View.VISIBLE
                        status.text = if (failedParts.isEmpty()) {
                            "${source.name}: ${totalLoaded} içerik yüklendi."
                        } else {
                            "${source.name}: ${totalLoaded} içerik yüklendi. Eksik yanıt: ${failedParts.distinct().joinToString()}."
                        }
                        if (selectedType == "Tümü") render()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loadProgress = 0
                    isLoadingSource = false
                    status.visibility = View.VISIBLE
                    status.text = "${source.name}: ${friendlyLoadError(e)}"
                    render()
                    showConnectionError(source.name, friendlyLoadError(e))
                }
            }
        }
    }

    /**
     * Stream Xtream JSON arrays one object at a time. This avoids keeping the complete
     * provider response string and a JSONArray copy in memory at the same time.
     */
    private fun forEachXtreamJsonObject(
        address: String,
        onAttemptStart: () -> Unit = {},
        onItem: (JSONObject) -> Unit
    ) {
        var lastError: Exception? = null
        for (attempt in 0..2) {
            var conn: HttpURLConnection? = null
            var parsedAny = false
            try {
                onAttemptStart()
                conn = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "KaiserTV+/2.1")
                    setRequestProperty("Accept", "application/json, text/plain, */*")
                    useCaches = false
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    if (code == 429 || code >= 500) throw Exception("HTTP $code (yeniden denenecek)")
                    throw Exception("HTTP $code")
                }
                val stream = conn.inputStream
                InputStreamReader(stream, Charsets.UTF_8).use { input ->
                    JsonReader(input).use { reader ->
                        reader.isLenient = true
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val item = JSONObject()
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                when (reader.peek()) {
                                    JsonToken.STRING, JsonToken.NUMBER -> item.put(key, reader.nextString())
                                    JsonToken.BOOLEAN -> item.put(key, reader.nextBoolean())
                                    JsonToken.NULL -> {
                                        reader.nextNull()
                                        item.put(key, JSONObject.NULL)
                                    }
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            parsedAny = true
                            onItem(item)
                        }
                        reader.endArray()
                    }
                }
                return
            } catch (e: Exception) {
                lastError = e
                // Once any item was delivered, retrying would duplicate already published chunks.
                if (parsedAny || attempt == 2) throw e
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

    /**
     * Try the richer m3u_plus variant for URLs explicitly using type=m3u.
     * If the provider rejects m3u_plus, fall back to the original URL.
     */
    private fun requestM3uWithAutoPlus(address: String): List<MediaItem> {
        val typePattern = Regex("([?&]type=)m3u(?=(&|#|$))", RegexOption.IGNORE_CASE)
        val match = typePattern.find(address) ?: return requestM3u(address)
        val plusAddress = address.replaceRange(
            match.range,
            match.value.replace(Regex("m3u$", RegexOption.IGNORE_CASE), "m3u_plus")
        )
        return try {
            requestM3u(plusAddress)
        } catch (plusError: Exception) {
            android.util.Log.w("KaiserTV", "M3U+ endpoint unavailable; falling back to original M3U URL")
            try {
                requestM3u(address)
            } catch (fallbackError: Exception) {
                throw Exception(
                    "M3U+ denemesi başarısız (" + plusError.message + "); normal M3U denemesi de başarısız (" + fallbackError.message + ").",
                    fallbackError
                )
            }
        }
    }

    private fun requestM3u(address: String): List<MediaItem> {
        var lastError: Exception? = null
        for (attempt in 0..2) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(address).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "KaiserTV+/2.1")
                    setRequestProperty("Accept", "application/x-mpegURL, audio/x-mpegurl, text/plain, */*")
                    useCaches = false
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val message = try { conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readLine() } } catch (_: Exception) { null }
                    if (code == 429 || code >= 500) throw Exception("HTTP $code (yeniden denenecek)")
                    throw Exception("HTTP $code" + (message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""))
                }
                val parsed = conn.inputStream.bufferedReader(Charsets.UTF_8).use { parseM3u(it) }
                if (parsed.isEmpty()) throw Exception("M3U listesi boş veya desteklenmeyen biçimde.")
                return parsed
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
        throw Exception("M3U listesi alınamadı: " + (lastError?.message ?: "bağlantı hatası"))
    }

    private fun parseM3u(reader: java.io.BufferedReader): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        var name = "İçerik"
        var group = "Diğer"
        var icon = ""
        val headers = linkedMapOf<String, String>()

        fun attribute(line: String, key: String): String {
            val pattern = Regex("""(?:^|\s)${Regex.escape(key)}\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
            val match = pattern.find(line) ?: return ""
            return match.groupValues.getOrElse(1) { "" }.ifBlank {
                match.groupValues.getOrElse(2) { "" }
            }.trim()
        }

        fun displayName(line: String): String {
            var quote: Char? = null
            for (index in line.indices) {
                val ch = line[index]
                if ((ch == '"' || ch == '\'') && (quote == null || quote == ch)) {
                    quote = if (quote == null) ch else null
                } else if (ch == ',' && quote == null) {
                    return line.substring(index + 1).trim()
                }
            }
            return attribute(line, "tvg-name").ifBlank { "İçerik" }
        }

        fun headerName(raw: String): String = when (raw.trim().lowercase()) {
            "http-user-agent", "user-agent" -> "User-Agent"
            "http-referrer", "http-referer", "referer", "referrer" -> "Referer"
            else -> raw.trim()
        }

        while (true) {
            val rawLine = reader.readLine() ?: break
            if (rawLine.length > 256 * 1024) continue
            val line = rawLine.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTINF", true) -> {
                    name = displayName(line).ifBlank { attribute(line, "tvg-name") }.ifBlank { "İçerik" }
                    group = attribute(line, "group-title").ifBlank { "Diğer" }
                    icon = attribute(line, "tvg-logo")
                    headers.clear()
                }
                line.startsWith("#EXTGRP:", true) -> {
                    val extGroup = line.substringAfter(':').trim()
                    if (extGroup.isNotBlank()) group = extGroup
                }
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val option = line.substringAfter(':').trim()
                    val key = option.substringBefore('=', "")
                    val value = option.substringAfter('=', "").trim()
                    if (key.startsWith("http-", true) && value.isNotBlank()) {
                        headers[headerName(key)] = value
                    }
                }
                line.startsWith("#EXTHTTP:", true) -> {
                    val jsonText = line.substringAfter(':').trim()
                    try {
                        val json = JSONObject(jsonText)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val value = json.optString(key).trim()
                            if (value.isNotBlank()) headers[headerName(key)] = value
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("KaiserTV", "Invalid EXTHTTP headers for $name", e)
                    }
                }
                line.startsWith("#") || line.isBlank() -> Unit
                line.startsWith("http://", true) || line.startsWith("https://", true) -> {
                    // Some playlists append request headers after a pipe:
                    // stream-url|User-Agent=...&Referer=...
                    val streamUrl = line.substringBefore('|').trim()
                    val pipeOptions = line.substringAfter('|', "").trim()
                    if (pipeOptions.isNotBlank()) {
                        pipeOptions.split('&').forEach { part ->
                            val key = part.substringBefore('=', "").trim()
                            val value = part.substringAfter('=', "").trim()
                            if (key.isNotBlank() && value.isNotBlank()) {
                                val decoded = try { java.net.URLDecoder.decode(value, "UTF-8") } catch (_: Exception) { value }
                                headers[headerName(key)] = decoded
                            }
                        }
                    }
                    val type = classify(name, group, streamUrl)
                    val headerJson = if (headers.isEmpty()) "" else JSONObject(headers as Map<*, *>).toString()
                    out.add(MediaItem(streamUrl, name, streamUrl, group, type, icon, requestHeaders = headerJson))
                    name = "İçerik"
                    group = "Diğer"
                    icon = ""
                    headers.clear()
                }
            }
        }
        // M3U playlists often expose each episode as a flat stream entry.
        // Build series parents from SxxExx / 1x02 names, retaining child URLs.
        val episodePattern = Regex("(?i)^(.+?)\\s*(?:[-_. ]?S(\\d{1,2})\\s*E(\\d{1,3})|\\b(\\d{1,2})x(\\d{1,3})\\b)(?:\\s*[-_. ].*)?$")
        val seriesParents = LinkedHashMap<String, MediaItem>()
        val seriesEpisodes = ArrayList<MediaItem>()
        val ordinaryItems = ArrayList<MediaItem>()
        for (entry in out) {
            val match = episodePattern.matchEntire(entry.name.trim())
            if (entry.type != "Diziler" || match == null) {
                ordinaryItems.add(entry)
                continue
            }
            val seriesName = match.groupValues[1].trim().trimEnd('-', '_', '.', ' ')
            val seasonNumber = match.groupValues[2].ifBlank { match.groupValues[4] }.toIntOrNull() ?: 1
            val episodeNumber = match.groupValues[3].ifBlank { match.groupValues[5] }.toIntOrNull() ?: 1
            if (seriesName.isBlank()) {
                ordinaryItems.add(entry)
                continue
            }
            val stableKey = (entry.group + "|" + seriesName).lowercase(java.util.Locale.ROOT)
                .replace(Regex("\\s+"), " ").trim()
            val parentId = "m3u-series:" + stableKey.hashCode().toUInt().toString(16)
            val previous = seriesParents[parentId]
            if (previous == null || (previous.icon.isBlank() && entry.icon.isNotBlank())) {
                seriesParents[parentId] = MediaItem(
                    parentId, seriesName, "m3u-series://$parentId", entry.group,
                    "Diziler", entry.icon, "", parentId, entry.sourceId
                )
            }
            seriesEpisodes.add(entry.copy(
                name = "S${seasonNumber.toString().padStart(2, '0')} E${episodeNumber.toString().padStart(2, '0')}",
                group = "$seriesName • Sezon $seasonNumber",
                type = "Bölümler",
                seriesId = parentId
            ))
        }
        return ordinaryItems + seriesParents.values + seriesEpisodes
    }
    private fun isLanguagePrefixedChannel(name: String): Boolean =
        Regex("""^\s*[A-Za-z]{2,3}\s*:""").containsMatchIn(name)

    private fun classify(name: String, group: String, streamUrl: String = ""): String {
        // TR:, FR:, EN:, DE: and similar provider prefixes denote live channel variants.
        if (isLanguagePrefixedChannel(name)) return "Canlı TV"
        val urlPath = try { android.net.Uri.parse(streamUrl).path.orEmpty().lowercase(java.util.Locale.ROOT) } catch (_: Exception) { "" }
        // Provider URL paths are more reliable than group labels: many playlists
        // name live channel groups "FILM", "CINEMA" or "DIZI" even though they
        // are linear TV channels, which must never become VOD cards.
        when {
            Regex("/live/").containsMatchIn(urlPath) -> return "Canlı TV"
            Regex("/movie/").containsMatchIn(urlPath) -> return "Filmler"
            Regex("/series/").containsMatchIn(urlPath) -> return "Diziler"
        }
        val s = "$name $group".lowercase(java.util.Locale.ROOT)
        val seriesHints = listOf("series", "dizi", "tv show", "season", "sezon", "episode", "bölüm", "son bölüm", "tüm bölümler")
        val movieHints = listOf("movie", "film", "cinema", "vod", "sinema", "4k turk filmleri", "yabancı filmler", "çocuk filmleri")
        return when {
            seriesHints.any { it in s } -> "Diziler"
            movieHints.any { it in s } -> "Filmler"
            else -> "Canlı TV"
        }
    }

    private fun cachedItemsFile() = java.io.File(filesDir, "kaisertv_media_cache.json")

    private fun saveCachedItems() {
        // Keep only the newest pending snapshot; never queue multiple full-library copies.
        val snapshot = try { items.toList() } catch (_: Exception) { return }
        synchronized(cacheWriteLock) {
            pendingCacheSnapshot = snapshot
            if (cacheWriterRunning) return
            cacheWriterRunning = true
        }
        try {
            cacheExecutor.execute {
                while (true) {
                    val next = synchronized(cacheWriteLock) {
                        val value = pendingCacheSnapshot
                        pendingCacheSnapshot = null
                        if (value == null) cacheWriterRunning = false
                        value
                    } ?: break
                    val temp = java.io.File(filesDir, "kaisertv_media_cache.tmp")
                    try {
                        temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                            writer.write("[")
                            next.forEachIndexed { index, x ->
                                if (index > 0) writer.write(",")
                                val obj = JSONObject()
                                    .put("id", x.id).put("name", x.name).put("url", x.url).put("group", x.group)
                                    .put("type", x.type).put("icon", x.icon).put("extension", x.extension)
                                    .put("seriesId", x.seriesId).put("sourceId", x.sourceId).put("plot", x.plot)
                                    .put("actors", x.actors).put("rating", x.rating).put("releaseDate", x.releaseDate)
                                    .put("genre", x.genre).put("requestHeaders", x.requestHeaders)
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
        } catch (e: Exception) {
            synchronized(cacheWriteLock) { cacheWriterRunning = false }
            android.util.Log.e("KaiserTV", "Media cache writer could not start", e)
        }
    }

    private fun readCachedItems(sourceIdFilter: String? = null): List<MediaItem> {
        val file = cachedItemsFile()
        if (!file.exists() || file.length() <= 0L) return emptyList()
        if (file.length() > 64L * 1024L * 1024L) {
            android.util.Log.w("KaiserTV", "Media cache exceeds hard safety limit; skipping restore.")
            return emptyList()
        }
        // Read only the requested source when switching sources. This keeps the
        // memory ceiling bounded without forcing a network reload for older sources.
        val restored = ArrayList<MediaItem>(minOf(12000, (file.length() / 180).toInt().coerceAtLeast(64)))
        java.io.BufferedReader(java.io.InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { input ->
            android.util.JsonReader(input).use { reader ->
                reader.beginArray()
                while (reader.hasNext() && restored.size < 12000) {
                    var id = ""
                    var name = ""
                    var url = ""
                    var group = "Diğer"
                    var type = "Canlı TV"
                    var icon = ""
                    var extension = ""
                    var seriesId = ""
                    var sourceId = ""
                    var plot = ""
                    var actors = ""
                    var rating = ""
                    var releaseDate = ""
                    var genre = ""
                    var requestHeaders = ""
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        val value = if (reader.peek() == android.util.JsonToken.NULL) {
                            reader.nextNull()
                            ""
                        } else if (reader.peek() == android.util.JsonToken.STRING ||
                            reader.peek() == android.util.JsonToken.NUMBER ||
                            reader.peek() == android.util.JsonToken.BOOLEAN) {
                            reader.nextString()
                        } else {
                            reader.skipValue()
                            ""
                        }
                        when (key) {
                            "id" -> id = value
                            "name" -> name = value
                            "url" -> url = value
                            "group" -> group = value
                            "type" -> type = value
                            "icon" -> icon = value
                            "extension" -> extension = value
                            "seriesId" -> seriesId = value
                            "sourceId" -> sourceId = value
                            "plot" -> plot = value
                            "actors" -> actors = value
                            "rating" -> rating = value
                            "releaseDate" -> releaseDate = value
                            "genre" -> genre = value
                            "requestHeaders" -> requestHeaders = value
                        }
                    }
                    reader.endObject()
                    if (url.isNotBlank() && (sourceIdFilter.isNullOrBlank() || sourceId == sourceIdFilter)) {
                        restored.add(MediaItem(id, name, url, group, type, icon, extension, seriesId,
                            sourceId, plot, actors, rating, releaseDate, genre, requestHeaders))
                    }
                }
            }
        }
        return restored
    }

    private fun loadCachedItems() {
        try {
            val restored = readCachedItems()
            items.clear()
            items.addAll(restored)
            android.util.Log.i("KaiserTV", "Restored " + restored.size + " cached media items.")
        } catch (e: Exception) {
            android.util.Log.e("KaiserTV", "Media cache restore failed", e)
        }
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

            if (sidebarMode == "context" && selectedType in listOf("Canlı TV", "Filmler", "Diziler")) {
                // Return to the main navigation while preserving the tab the user entered from.
                val returnTab = selectedType
                expandedCategory = null
                selectedGroup = "Tüm kategoriler"
                focusedCategoryName = null
                sidebarMode = "main"
                prefs.edit().putString("last_section", returnTab).putString("last_group", "Tüm kategoriler").apply()
                rebuildNavigation()
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
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedDrawable(Color.rgb(13, 20, 33), 18, accent)
        }
        exitPanel.addView(makeKaiserLogoView(25f), LinearLayout.LayoutParams(dp(176), dp(40)).apply {
            gravity = Gravity.CENTER
        })
        exitPanel.addView(TextView(this).apply {
            text = t("Uygulamadan çıkmak istiyor musunuz?")
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val exitActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val exitDialog = AlertDialog.Builder(this).setView(exitPanel).create()
        fun exitActionButton(label: String, fillColor: Int, textColor: Int, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = t(label)
                textSize = 15f
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
        exitActions.addView(exitButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(0, 0, dp(10), 0)
        })
        exitActions.addView(cancelButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        exitPanel.addView(exitActions, LinearLayout.LayoutParams(-1, dp(42)))
        exitDialog.setOnShowListener {
            exitDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            exitDialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            exitDialog.window?.setDimAmount(0.78f)
            exitDialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.42f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
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

    private fun makeKaiserLogoView(textSizeSp: Float = 30f): TextView {
        val label = android.text.SpannableString("KaiserTV+")
        label.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, 6, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(0, 160, 255)), 6, 8, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        label.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(255, 205, 0)), 8, 9, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextView(this).apply {
            text = label
            textSize = textSizeSp
            gravity = Gravity.CENTER
            includeFontPadding = false
            letterSpacing = -0.035f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD_ITALIC)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "KaiserTV+"
            maxLines = 1
        }
    }

    private fun render() {
        if (!::content.isInitialized || !::categoryBar.isInitialized) return
        recordUiDiagnostics()
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
        // A series detail is a full-screen page: keep the left category/navigation rail hidden.
        if (::navBar.isInitialized) navBar.visibility =
            if (firstEntry || isLoadingSource || detailSeries != null) View.GONE else View.VISIBLE
        if (::sidebar.isInitialized && sidebarMode == "context") showContextNavigationStage(contextNavigationStage)
        // Context navigation can re-show its category rail during render.
        // Detail pages must always be edge-to-edge, for BOTH movies and series.
        if (detailMode) {
            if (::sidebar.isInitialized) sidebar.visibility = View.GONE
            if (::categoryListScroll.isInitialized) categoryListScroll.visibility = View.GONE
            if (::navBar.isInitialized) navBar.visibility = View.GONE
            if (::brandView.isInitialized) brandView.visibility = View.GONE
        }
        if (::mainHeader.isInitialized) mainHeader.visibility = View.GONE
        if (::dateClockRow.isInitialized) dateClockRow.visibility =
            if (firstEntry || isLoadingSource || detailMode || selectedType == "Ayarlar") View.GONE else View.VISIBLE
        if (::status.isInitialized) status.visibility = View.GONE // İçerik sayısı/kaynak adı üst başlıkta gösterilmez.
        if (::categoryScroll.isInitialized) categoryScroll.visibility = View.GONE
        if (firstEntry) {
            mainContentScroll.visibility = View.VISIBLE
            val welcome = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            welcome.addView(makeKaiserLogoView(38f), LinearLayout.LayoutParams(dp(260), dp(70)).apply {
                gravity = Gravity.CENTER
            })
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
                visibility = View.VISIBLE
            }
            val inlineM3u = RadioButton(this).apply {
                id = View.generateViewId(); text = "M3U / M3U8"
                setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            val inlineXtream = RadioButton(this).apply {
                id = View.generateViewId(); text = "Xtream Codes"
                setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            val inlineStalker = RadioButton(this).apply {
                id = View.generateViewId(); text = "Stalker Portal"
                setTextColor(Color.WHITE); buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            sourceMode.addView(inlineM3u)
            sourceMode.addView(inlineXtream)
            sourceMode.addView(inlineStalker)
            sourceMode.check(inlineXtream.id)
            playlistUrl = edit("M3U / M3U8 URL adresi", false).apply {
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
            val portalMac = edit("MAC adresi (00:1A:79:XX:XX:XX)", false).apply {
                id = View.generateViewId()
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            }
            val playlistLabel = TextView(this).apply {
                text = "M3U / M3U8 oynatma listesi URL'si"
                textSize = 13f; setTextColor(muted)
                setPadding(0, dp(4), 0, dp(3))
            }
            inlineForm.addView(sourceMode, LinearLayout.LayoutParams(-1, dp(44)).apply { setMargins(0, 0, 0, dp(6)) })
            inlineForm.addView(playlistLabel)
            inlineForm.addView(playlistUrl, LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, 0, 0, dp(6))
            })
            inlineForm.addView(server, LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, 0, 0, dp(6))
            })
            val inlineCredentials = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            inlineCredentials.addView(username, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, 0, dp(4), 0) })
            inlineCredentials.addView(password, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, 0, 0) })
            inlineForm.addView(inlineCredentials)
            fun updateInlineFields() {
                val mode = sourceMode.checkedRadioButtonId
                val useM3u = mode == inlineM3u.id
                val useStalker = mode == inlineStalker.id
                playlistLabel.visibility = if (useM3u) View.VISIBLE else View.GONE
                playlistUrl.visibility = if (useM3u) View.VISIBLE else View.GONE
                server.visibility = if (useM3u) View.GONE else View.VISIBLE
                inlineCredentials.visibility = if (mode == inlineXtream.id) View.VISIBLE else View.GONE
                portalMac.visibility = if (useStalker) View.VISIBLE else View.GONE
                server.hint = if (useStalker) "Portal URL (http://adres/c/)" else "Sunucu adresi (https://...)"
            }
            sourceMode.setOnCheckedChangeListener { _, _ -> updateInlineFields() }
            updateInlineFields()
            inlineForm.addView(portalMac, LinearLayout.LayoutParams(-1, dp(48)).apply {
                setMargins(0, 0, 0, dp(6))
            })
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
                    (when (sourceMode.checkedRadioButtonId) {
                        inlineM3u.id -> inlineM3u
                        inlineStalker.id -> inlineStalker
                        else -> inlineXtream
                    }).requestFocus()
                    true
                } else false
            }
            focusNextOnDown(sourceName) { sourceMode.getChildAt(0) }
            focusNextOnDown(inlineM3u) { if (sourceMode.checkedRadioButtonId == inlineM3u.id) playlistUrl else server }
            focusNextOnDown(inlineXtream) { server }
            focusNextOnDown(inlineStalker) { server }
            focusNextOnDown(server) { if (sourceMode.checkedRadioButtonId == inlineStalker.id) portalMac else username }
            focusNextOnDown(username) { password }
            focusNextOnDown(playlistUrl) { playlistUrl.focusSearch(View.FOCUS_DOWN) ?: playlistUrl }
            inlineForm.addView(makeButton("＋ KAYNAK EKLE", true) {
                val mode = sourceMode.checkedRadioButtonId
                val label = sourceName.text.toString().trim()
                when (mode) {
                    inlineM3u.id -> {
                        val address = playlistUrl.text.toString().trim()
                        if (!address.startsWith("http://") && !address.startsWith("https://")) {
                            Toast.makeText(this, "Geçerli bir M3U/M3U8 URL'si gir.", Toast.LENGTH_LONG).show()
                            return@makeButton
                        }
                        val src = SavedSource(java.util.UUID.randomUUID().toString(), label.ifBlank { "M3U ${savedSources.size + 1}" }, "m3u", address)
                        saveSource(src); hideKeyboard(); settingsPage = "main"; sidebarMode = "main"; selectedType = "Tümü"; render(); loadM3u(src, false)
                    }
                    inlineStalker.id -> {
                        val portal = server.text.toString().trim().trimEnd('/')
                        val macValue = portalMac.text.toString().trim().uppercase(java.util.Locale.ROOT)
                        val validMac = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$").matches(macValue)
                        if (!(portal.startsWith("http://") || portal.startsWith("https://")) || !validMac) {
                            Toast.makeText(this, "Portal URL ve geçerli MAC adresini girin.", Toast.LENGTH_LONG).show()
                            return@makeButton
                        }
                        val src = SavedSource(java.util.UUID.randomUUID().toString(), label.ifBlank { "Stalker Portal ${savedSources.size + 1}" }, "mac", portal, macValue)
                        saveSource(src); hideKeyboard()
                        settingsPage = "main"; sidebarMode = "main"; selectedType = "Tümü"; render()
                        loadStalker(src, false)
                    }
                    else -> {
                        val address = server.text.toString().trim()
                        if (!(address.startsWith("http://") || address.startsWith("https://")) ||
                            username.text.isNullOrBlank() || password.text.isNullOrBlank()) {
                            Toast.makeText(this, "Sunucu, kullanıcı adı ve şifreyi doldur.", Toast.LENGTH_LONG).show()
                            return@makeButton
                        }
                        val src = SavedSource(java.util.UUID.randomUUID().toString(), label.ifBlank { "Xtream ${savedSources.size + 1}" }, "xtream",
                            address.trimEnd('/'), username.text.toString().trim(), password.text.toString())
                        saveSource(src); hideKeyboard(); settingsPage = "main"; sidebarMode = "main"; selectedType = "Tümü"; render(); loadXtream(src, false)
                    }
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
            loadingMessageView = null
            val loadingPanel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }
            loadingPanel.addView(makeKaiserLogoView(34f), LinearLayout.LayoutParams(dp(230), dp(64)).apply {
                gravity = Gravity.CENTER
            })
            loadingMessageView = TextView(this).apply {
                text = status.text.toString().ifBlank { "Kaynak yükleniyor…" }
                textSize = 18f; setTextColor(muted); gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, 0)
            }
            loadingPanel.addView(loadingMessageView)
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
        // Show the complete provider category list immediately, even while
        // the category's media pages are still being downloaded in the background.
        val providerGroups = synchronized(providerCategoryNames) {
            providerCategoryNames[selectedType].orEmpty()
        }
        val groupsAvailable = (providerGroups + baseVisible.map { it.group.ifBlank { "Diğer" } }).distinct()
        categoryBar.removeAllViews()
        categoryScroll.visibility = View.GONE
        categoryBar.addView(makeButton("Tümü", selectedGroup == "Tüm kategoriler") { selectedGroup = "Tüm kategoriler"; render() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36).apply { setMargins(0, 2, 8, 2) })
        groupsAvailable.take(40).forEach { group ->
            categoryBar.addView(makeButton(if (group == selectedGroup) "● $group" else group, group == selectedGroup) {
                selectedGroup = group
                mediaItemsLimit = 12
                render()
            }.apply {
                textSize = 11f; minWidth = 0; minimumWidth = 0
                setPadding(12, 0, 12, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36).apply { setMargins(0, 2, 8, 2) })
        }
        val visible = if (selectedGroup == "Tüm kategoriler" || sidebarMode == "search") baseVisible
            else baseVisible.filter { it.group.ifBlank { it.type } == selectedGroup }

        // Live TV category content: compact logo/name grid; OK opens the channel fullscreen.
        if (selectedType == "Canlı TV") {
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
            // Kaynak ekleme işlemi ilk açılış formundan yapılır; ikinci CTA kaldırıldı.
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
                t("Haftanın Filmleri") to rankedByTrend(activeSourceItems.filter { it.type == "Filmler" }, trendingMovies).take(24),
                t("Haftanın Dizileri") to rankedByTrend(activeSourceItems.filter { it.type == "Diziler" }, trendingSeries).take(24),
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
            ?: if (selectedType in listOf("Filmler", "Diziler")) groups.take(1) else groups.take(8)
        groupsToRender.forEach { (groupName, entries) ->
            val showingExpandedCategory = expandedCategory?.first == groupName
            val posterGridMode = showingExpandedCategory || selectedType == "Filmler" || selectedType == "Diziler" || selectedType == "Canlı TV"
            val fiveColumnMediaGrid = selectedType in listOf("Filmler", "Diziler")
            val liveTvEightColumnGrid = selectedType == "Canlı TV"
            val sectionHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(42)
                setPadding(2, dp(4), 2, dp(6))
            }
            sectionHeader.addView(TextView(this).apply {
                text = groupName; textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(Color.WHITE)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
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
                // Keep every poster/card anchored to the same top edge, even when title heights differ.
                gravity = Gravity.TOP or Gravity.START
                clipChildren = false
                clipToPadding = false
                if (posterGridMode) setPadding(0, dp(14), 0, dp(8))
            }
            // Use localized display labels for comparison so the weekly poster layout
            // remains identical in every app language.
            val weeklyMovieFeature = selectedType == "Tümü" &&
                (groupName == t("Haftanın Filmleri") || groupName == t("Haftanın Dizileri"))
            // Ana ekranda haftanın filmleri tek tek geniş, afiş odaklı kartlar olarak gösterilir.
            // Yatay kaydırma ile bir sonraki tam genişlikte afişe geçilir.
            val featureCardWidth = dp(if (isMobileLayout) 156 else 140) // Kart genişliği afişle aynı
            val displayedEntries = when {
                posterGridMode -> entries.take(if (selectedType == "Canlı TV" && !showingExpandedCategory) 8 else mediaItemsLimit)
                weeklyMovieFeature -> entries.take(8)
                else -> entries.take(5)
            }
            var expandedPosterRow: LinearLayout? = null
            var expandedPosterCount = 0
            displayedEntries.forEachIndexed { itemIndex, item ->
                val weeklyPoster = weeklyMovieFeature && !showingExpandedCategory
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
                            // Weekly home posters must never move vertically when focus changes.
                            .translationY(if (weeklyPoster) 0f else if (hasFocus) -dp(10).toFloat() else 0f)
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
                // Scale weekly posters from the top edge; their top coordinates stay fixed.
                if (weeklyPoster) card.pivotY = 0f
                val poster = ImageView(this).apply {
                    scaleType = if (liveCard) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
                    contentDescription = item.name
                    background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                    setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                        intArrayOf(Color.rgb(8, 10, 14), Color.rgb(14, 18, 24))))
                    setPadding(if (liveCard) 12 else 0, if (liveCard) 12 else 0, if (liveCard) 12 else 0, if (liveCard) 12 else 0)
                    // Keep the poster's portrait aspect ratio; the card wraps the poster instead of stretching it.
                    layoutParams = LinearLayout.LayoutParams(
                        if (weeklyPoster) dp(if (isMobileLayout) 156 else 140) else -1,
                        if (liveCard) dp(if (isMobileLayout) 96 else 88) else if (weeklyPoster) dp(if (isMobileLayout) 234 else 210) else if (posterGridMode) dp(if (isMobileLayout) 224 else 210) else if (homeCard) 230 else 210
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
            val finalGridColumns = if (liveTvEightColumnGrid) { if (isMobileLayout) 4 else 8 } else if (fiveColumnMediaGrid) { if (isMobileLayout) 3 else 5 } else 4
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
                if (displayedEntries.size < entries.size) {
                    content.addView(makeButton(
                        "Daha fazla içerik göster  (${displayedEntries.size}/${entries.size})", false
                    ) {
                        mediaItemsLimit = (mediaItemsLimit + 12).coerceAtMost(entries.size)
                        render()
                    }, LinearLayout.LayoutParams(-1, dp(48)).apply {
                        setMargins(dp(8), dp(4), dp(8), dp(16))
                    })
                }
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

        val source = savedSources.firstOrNull { it.id == series.sourceId }
        if (source?.mode == "m3u") {
            val episodes = items.filter {
                it.sourceId == series.sourceId && it.type == "Bölümler" && it.seriesId == series.id
            }
            val rows = episodes.groupBy {
                it.group.substringAfterLast("Sezon ").ifBlank { "1" }
            }.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }.map { entry ->
                entry.key to entry.value.sortedBy { ep ->
                    Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(ep.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Int.MAX_VALUE
                }
            }
            seriesEpisodeRows = rows
            seriesEpisodesLoading = false
            if (rows.isEmpty()) seriesEpisodesError = "Bu M3U listesinde diziye ait sezon/bölüm numarası bulunamadı."
            render()
            return
        }
        if (source == null || (source.mode != "xtream" && source.mode != "mac")) {
            seriesEpisodesLoading = false
            seriesEpisodesError = "Dizi kaynağı bulunamadı. Kaynağı yeniden yükle."
            render()
            return
        }

        thread {
            try {
                if (source.mode == "mac") {
                    val token = stalkerHandshake(source)
                    val seriesUri = android.net.Uri.parse(series.url)
                    val seriesPortalId = seriesUri.host?.takeIf { it.isNotBlank() }
                        ?: seriesUri.lastPathSegment?.takeIf { it.isNotBlank() }
                        ?: series.id.substringAfterLast(':')
                    val categoryId = seriesUri.getQueryParameter("category")
                        ?.takeIf { it.isNotBlank() }
                    if (seriesPortalId.isBlank()) throw Exception("Dizi portal kimliği bulunamadı.")

                    // Stalker/Ministra portals do not agree on whether the selected
                    // series is passed as movie_id or series_id. Try the supported
                    // hierarchy shapes explicitly; the previous code only retried
                    // when movie_id was non-zero, although it was always set to zero.
                    fun isSeasonRow(row: JSONObject): Boolean {
                        if (row.optBoolean("is_season", false)) return true
                        val name = row.optString("name").ifBlank { row.optString("title") }.trim()
                        if (Regex("(?i)^(season|sezon)\\s*[-#:]?\\s*\\d+\\s*$").matches(name)) return true
                        val hasEpisodeIdentity = listOf("episode_id", "episode_number", "series_number", "episode")
                            .any { row.optString(it).isNotBlank() }
                        return !hasEpisodeIdentity &&
                            Regex("(?i)^(season|sezon)\\s*[-#:]?\\s*\\d+.*$").matches(name)
                    }

                    fun belongsToSelectedSeries(ep: JSONObject, expectedSeasonId: String? = null): Boolean {
                        // Some Stalker portals encode hierarchy in IDs rather than parent fields:
                        // season row 417:1, episode rows 417:1:1, 417:1:2, etc.
                        if (!expectedSeasonId.isNullOrBlank()) {
                            val rowSeasonId = ep.optString("season_id").trim()
                            if (rowSeasonId == expectedSeasonId) return true
                            val rowId = ep.optString("episode_id").ifBlank { ep.optString("id") }.trim()
                            if (rowId.startsWith("$expectedSeasonId:")) return true
                        }
                        val parentKeys = listOf("series_id", "parent_series_id", "parent_id", "owner_id", "tv_show_id", "movie_id", "video_id")
                        val parentValues = parentKeys.map { ep.optString(it).trim() }
                            .filter { it.isNotBlank() && it != "0" && !it.equals("null", true) }
                        if (parentValues.isNotEmpty()) return parentValues.any { it == seriesPortalId }
                        val nested = ep.optJSONObject("series")
                        val nestedId = nested?.optString("id").orEmpty()
                        if (nestedId.isNotBlank()) return nestedId == seriesPortalId
                        // If the portal omits parent IDs, only accept records explicitly
                        // labelled with the selected show; never display another series' episodes.
                        val title = ep.optString("series_name").ifBlank { ep.optString("parent_name") }
                        return title.isNotBlank() && title.contains(series.name, ignoreCase = true)
                    }

                    fun requestSeriesList(seasonId: String = "0", includeSeasonRows: Boolean = false): JSONArray {
                        // Ministra stores series, seasons and episodes in the VOD catalogue.
                        // The supported hierarchy endpoint is type=vod + movie_id=series ID;
                        // season_id=0 returns season rows, a concrete season ID returns episodes.
                        val combined = JSONArray()
                        for (page in 1..100) {
                            val params = linkedMapOf(
                                "type" to "vod",
                                "action" to "get_ordered_list",
                                "movie_id" to seriesPortalId,
                                "season_id" to seasonId,
                                "episode_id" to "0",
                                "p" to page.toString(),
                                "fav" to "0",
                                "sortby" to "added",
                                "hd" to "0",
                                "ended" to "0",
                                "search" to ""
                            )
                            val response = stalkerRequest(source, token, params)
                            val array = stalkerArray(response, "data", "items", "results")
                            val matched = JSONArray()
                            for (i in 0 until array.length()) {
                                val row = array.optJSONObject(i) ?: continue
                                if (seasonId != "0" && belongsToSelectedSeries(row, seasonId)) {
                                    matched.put(row)
                                } else if (seasonId == "0" && includeSeasonRows && isSeasonRow(row)) {
                                    matched.put(row)
                                } else if (seasonId == "0" && !includeSeasonRows && belongsToSelectedSeries(row)) {
                                    matched.put(row)
                                }
                            }
                            val sampleRows = (0 until minOf(array.length(), 3)).mapNotNull { rowIndex ->
                                val row = array.optJSONObject(rowIndex) ?: return@mapNotNull null
                                val rowKeys = row.keys().asSequence().joinToString(",")
                                val parentSummary = listOf("series_id", "parent_series_id", "parent_id", "owner_id", "tv_show_id", "movie_id", "video_id")
                                    .joinToString(",") { key -> "$key=${row.optString(key, "-")}" }
                                "keys=[$rowKeys], id=${row.optString("id")}, episode_id=${row.optString("episode_id")}, name=${row.optString("name").take(70)}, title=${row.optString("title").take(70)}, $parentSummary, season_id=${row.optString("season_id")}, is_episode=${row.optString("is_episode")}, is_season=${row.optString("is_season")}"
                            }.joinToString(" || ")
                            val dataValue = response.opt("data")
                            val detailDiagnostic = "DETAIL_VOD_SERIES request={type=vod,action=get_ordered_list,movie_id=$seriesPortalId,season_id=$seasonId,episode_id=0,p=$page} http=200 responseKeys=${response.keys().asSequence().joinToString(",")} dataType=${dataValue?.javaClass?.simpleName ?: "null"} raw=${array.length()} matched=${matched.length()} seasonRows=${(0 until array.length()).count { array.optJSONObject(it)?.let(::isSeasonRow) == true }} samples=$sampleRows"
                            android.util.Log.i("KaiserTV", "Stalker $detailDiagnostic")
                            synchronized(this@MainActivity) {
                                val previous = prefs.getString("last_stalker_catalog_diagnostics", "").orEmpty()
                                prefs.edit().putString("last_stalker_catalog_diagnostics",
                                    (previous + " | " + detailDiagnostic).takeLast(14000)).apply()
                            }
                            for (i in 0 until matched.length()) combined.put(matched.optJSONObject(i))
                            if (array.length() < 14) break
                        }
                        return combined
                    }

                    fun parseEpisodes(array: JSONArray, fallbackSeason: String?, alreadyScopedToSeries: Boolean = false): List<MediaItem> {
                        val rows = ArrayList<MediaItem>()
                        for (i in 0 until array.length()) {
                            val ep = array.optJSONObject(i) ?: continue
                            // Season entries are hierarchy nodes, never playable episodes.
                            if (isSeasonRow(ep)) continue
                            if (!alreadyScopedToSeries && !belongsToSelectedSeries(ep)) continue
                            val id = ep.optString("episode_id").ifBlank { ep.optString("id") }
                                .ifBlank { ep.optString("ch_id") }
                            if (id.isBlank()) continue
                            val episodeNumber = ep.optString("series_number").toIntOrNull()
                                ?: ep.optString("episode_number").toIntOrNull()
                                ?: ep.optString("episode").toIntOrNull()
                                ?: Regex("(?i)(?:episode|bölüm|ep)\\s*([0-9]+)").find(
                                    ep.optString("name").ifBlank { ep.optString("title") }
                                )?.groupValues?.getOrNull(1)?.toIntOrNull()
                                ?: (i + 1)
                            val seasonNumber = ep.optString("season_number")
                                .ifBlank { ep.optString("season") }
                                .ifBlank { fallbackSeason ?: "1" }
                            val command = ep.optString("cmd").ifBlank {
                                seriesUri.getQueryParameter("cmd").orEmpty()
                            }.ifBlank { "/media/file_$id.mpg" }
                            val episodeUrl = "stalker-episode://$id?cmd=${URLEncoder.encode(command, "UTF-8")}&series=$episodeNumber"
                            rows.add(MediaItem(
                                id,
                                ep.optString("name").ifBlank { ep.optString("title") }
                                    .ifBlank { "Bölüm $episodeNumber" },
                                episodeUrl, "${series.name} • Sezon $seasonNumber", "Bölümler",
                                ep.optString("screenshot_uri").ifBlank { ep.optString("pic") }
                                    .ifBlank { series.icon },
                                ep.optString("cmd").substringAfterLast('.').takeIf { it.length in 2..5 } ?: "mpg",
                                seriesPortalId, source.id,
                                ep.optString("description").ifBlank { ep.optString("plot") }
                            ))
                        }
                        return rows
                    }

                    // Parse the alternate Stalker shape where episode numbers are returned
                    // directly in a "series" array instead of episodes/data.
                    fun appendSeriesNumberArray(target: JSONArray, value: Any?, response: JSONObject) {
                        val values = value as? JSONArray ?: return
                        val seasonRowId = response.optString("id").trim()
                        val seasonNumberFromId = seasonRowId.substringAfterLast(':', "").toIntOrNull()
                        val parentCommand = if (seasonNumberFromId != null) {
                            val seriesNumericId = seriesPortalId.toLongOrNull()
                                ?: seriesPortalId.substringBefore(':').toLongOrNull()
                            if (seriesNumericId != null) {
                                val payload = JSONObject()
                                    .put("series_id", seriesNumericId)
                                    .put("season_num", seasonNumberFromId)
                                    .put("type", "series")
                                    .toString()
                                android.util.Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                            } else ""
                        } else {
                            response.optString("cmd")
                                .ifBlank { response.optJSONObject("js")?.optString("cmd").orEmpty() }
                                .ifBlank { seriesUri.getQueryParameter("cmd").orEmpty() }
                        }
                        for (index in 0 until values.length()) {
                            val raw = values.opt(index)
                            val row = when (raw) {
                                is JSONObject -> JSONObject(raw.toString())
                                is Number, is String -> JSONObject().put("episode_number", raw.toString())
                                else -> continue
                            }
                            if (row.optString("episode_number").isBlank()) {
                                row.put("episode_number", row.optString("series_number").ifBlank { (index + 1).toString() })
                            }
                            if (row.optString("id").isBlank() && row.optString("episode_id").isBlank()) {
                                row.put("id", "$seriesPortalId:episode:${row.optString("episode_number")}")
                            }
                            // Rows inside the selected show's own "series" array are already
                            // scoped to that show. Add the missing parent ID so the common parser
                            // does not discard these valid legacy episode-number entries.
                            val hasParent = listOf("series_id", "parent_series_id", "parent_id", "owner_id", "tv_show_id", "movie_id", "video_id")
                                .any { key -> row.optString(key).isNotBlank() && row.optString(key) != "0" }
                            if (!hasParent) row.put("series_id", seriesPortalId)
                            if (row.optString("cmd").isBlank() && parentCommand.isNotBlank()) row.put("cmd", parentCommand)
                            if (row.optString("season_number").isBlank()) row.put("season_number", "1")
                            if (row.optString("name").isBlank()) row.put("name", "Bölüm ${row.optString("episode_number")}")
                            target.put(row)
                        }
                    }

                    fun extractEpisodeArray(response: JSONObject): JSONArray {
                        val result = stalkerArray(response, "episodes", "items", "results", "data")
                        if (result.length() > 0) return result
                        val nested = response.optJSONObject("js")
                        if (nested != null) {
                            val nestedRows = stalkerArray(nested, "episodes", "items", "results", "data")
                            if (nestedRows.length() > 0) return nestedRows
                        }
                        return JSONArray()
                    }

                    fun requestDedicatedSeriesEpisodes(): JSONArray {
                        val response = stalkerRequest(source, token, mapOf(
                            "type" to "series",
                            "action" to "get_series_episodes",
                            "movie_id" to seriesPortalId,
                            "series_id" to seriesPortalId,
                            "p" to "1"
                        ))
                        val array = extractEpisodeArray(response)
                        appendSeriesNumberArray(array, response.opt("series"), response)
                        response.optJSONObject("js")?.let { nested ->
                            appendSeriesNumberArray(array, nested.opt("series"), nested)
                        }
                        val sample = (0 until minOf(array.length(), 4)).mapNotNull { n ->
                            array.optJSONObject(n)?.let { row ->
                                "id=${row.optString("id")},episode=${row.optString("episode_number")},name=${row.optString("name").take(45)}"
                            }
                        }.joinToString(" || ")
                        val summary = "DEDICATED_EPISODES request={type=series,action=get_series_episodes,movie_id=$seriesPortalId,series_id=$seriesPortalId} count=${array.length()} rootSeries=${response.optJSONArray("series")?.length() ?: 0} jsSeries=${response.optJSONObject("js")?.optJSONArray("series")?.length() ?: 0} responseKeys=${response.keys().asSequence().joinToString(",")} sample=$sample raw=${response.toString().take(1800)}"
                        android.util.Log.i("KaiserTV", "Stalker $summary")
                        appendStalkerSeriesEndpointDiagnostic(summary)
                        return array
                    }

                    // Some portals use get_ordered_list under type=series, not type=vod.
                    fun requestSeriesTypeOrderedList(seasonId: String = "0"): JSONArray {
                        val combined = JSONArray()
                        for (page in 1..30) {
                            val response = stalkerRequest(source, token, mapOf(
                                "type" to "series",
                                "action" to "get_ordered_list",
                                "movie_id" to seriesPortalId,
                                "series_id" to seriesPortalId,
                                "season_id" to seasonId,
                                "episode_id" to "0",
                                "p" to page.toString()
                            ))
                            val rows = extractEpisodeArray(response)
                            appendSeriesNumberArray(rows, response.opt("series"), response)
                            response.optJSONObject("js")?.let { appendSeriesNumberArray(rows, it.opt("series"), it) }
                            // Some portals nest the episode-number array inside each season row.
                            val seasonRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                                .filter { isSeasonRow(it) }
                            for (seasonRow in seasonRows) {
                                val seasonLabel = seasonRow.optString("name").ifBlank { seasonRow.optString("title") }
                                val seasonNumber = seasonRow.optString("season_number").ifBlank {
                                    Regex("(?i)(?:season|sezon)\\s*[-#:]?\\s*(\\d+)")
                                        .find(seasonLabel)?.groupValues?.getOrNull(1) ?: "1"
                                }
                                val episodeRows = JSONArray()
                                appendSeriesNumberArray(episodeRows, seasonRow.opt("series"), seasonRow)
                                for (episodeIndex in 0 until episodeRows.length()) {
                                    val episodeRow = episodeRows.optJSONObject(episodeIndex) ?: continue
                                    episodeRow.put("season_number", seasonNumber)
                                    episodeRow.put("season_id", seasonRow.optString("id"))
                                    episodeRow.put("series_id", seriesPortalId)
                                    rows.put(episodeRow)
                                }
                            }
                            for (n in 0 until rows.length()) combined.put(rows.opt(n))
                            val summary = "STANDARD_SERIES_ORDERED_LIST request={type=series,action=get_ordered_list,movie_id=$seriesPortalId,season_id=$seasonId,p=$page} count=${rows.length()} keys=${response.keys().asSequence().joinToString(",")} raw=${response.toString().take(900)}"
                            appendStalkerSeriesEndpointDiagnostic(summary)
                            if (rows.length() < 14) break
                        }
                        return combined
                    }

                    // This portal does not implement get_series_episodes. Use its confirmed
                    // STANDARD_SERIES_ORDERED_LIST route first to avoid a guaranteed empty request.
                    // Season rows are expanded from their own "series" number arrays below.
                    val seriesRows = try { requestSeriesTypeOrderedList("0") } catch (e: Exception) {
                        android.util.Log.w("KaiserTV", "Stalker type=series get_ordered_list failed", e)
                        appendStalkerSeriesEndpointDiagnostic("STANDARD_SERIES_ORDERED_LIST_ERROR ${e.javaClass.simpleName}: ${e.message}")
                        JSONArray()
                    }
                    var episodeItems = parseEpisodes(seriesRows, null)
                    appendStalkerSeriesEndpointDiagnostic("STANDARD_SERIES_ORDERED_LIST_RESULT raw=${seriesRows.length()} parsed=${episodeItems.size}")
                    if (episodeItems.isEmpty()) {
                        val standardRows = try { requestSeriesList("0") } catch (e: Exception) {
                            android.util.Log.w("KaiserTV", "Stalker type=vod fallback failed", e)
                            JSONArray()
                        }
                        episodeItems = parseEpisodes(standardRows, null)
                        appendStalkerSeriesEndpointDiagnostic("STANDARD_VOD_FALLBACK raw=${standardRows.length()} parsed=${episodeItems.size}")
                    }
                    if (episodeItems.isEmpty()) {
                        // Several Ministra portals return a season node (for example id=417:1)
                        // from the dedicated action. Capture it before filtering, then query that
                        // exact season ID. A generic list may contain unrelated show titles.
                        val seasonArrays = listOf(
                            requestSeriesList("0", includeSeasonRows = true),
                            requestSeriesList(seriesPortalId, includeSeasonRows = true)
                        )
                        val seasons = LinkedHashMap<String, Pair<String, String>>()
                        for (array in seasonArrays) {
                            for (i in 0 until array.length()) {
                                val season = array.optJSONObject(i) ?: continue
                                if (!isSeasonRow(season)) continue
                                val seasonId = season.optString("season_id").ifBlank { season.optString("id") }.trim()
                                if (seasonId.isBlank() || seasonId == "0") continue
                                val seasonLabel = season.optString("name").ifBlank { season.optString("title") }
                                val number = season.optString("season_number").ifBlank {
                                    Regex("(?i)(?:season|sezon)\\s*[-#:]?\\s*(\\d+)")
                                        .find(seasonLabel)?.groupValues?.getOrNull(1)
                                        ?: (seasons.size + 1).toString()
                                }
                                seasons[seasonId] = seasonId to number
                            }
                        }
                        val allEpisodes = ArrayList<MediaItem>()
                        for ((seasonId, seasonNumber) in seasons.values) {
                            val seasonRows = requestSeriesList(seasonId)
                            // The request is already scoped to this exact season ID.
                            // Do not require parent-series fields that this portal omits.
                            val parsed = parseEpisodes(seasonRows, seasonNumber, alreadyScopedToSeries = true)
                            allEpisodes.addAll(parsed)
                            val seasonDiagnostic = "SEASON_EPISODE_QUERY request={type=vod,action=get_ordered_list,movie_id=$seriesPortalId,season_id=$seasonId,episode_id=0} seasonId=$seasonId seasonNo=$seasonNumber raw=${seasonRows.length()} parsed=${parsed.size} sample=${(0 until minOf(seasonRows.length(), 5)).mapNotNull { idx -> seasonRows.optJSONObject(idx)?.let { row -> "id=${row.optString("id")},episode_id=${row.optString("episode_id")},name=${row.optString("name").take(55)},series_number=${row.optString("series_number")},episode_number=${row.optString("episode_number")},season_id=${row.optString("season_id")},is_series=${row.optString("is_series")},is_movie=${row.optString("is_movie")}" } }.joinToString(" || ")}"
                            android.util.Log.i("KaiserTV", "Stalker $seasonDiagnostic")
                            synchronized(this@MainActivity) {
                                val previous = prefs.getString("last_stalker_catalog_diagnostics", "").orEmpty()
                                prefs.edit().putString("last_stalker_catalog_diagnostics",
                                    (previous + " | " + seasonDiagnostic).takeLast(14000)).apply()
                            }
                        }
                        episodeItems = allEpisodes.distinctBy { it.id }
                        if (episodeItems.isEmpty()) {
                            val noEpisodes = "NO_EPISODES series=${series.name.take(70)} portalId=$seriesPortalId seasons=${seasons.keys.joinToString(",")}"
                            android.util.Log.w("KaiserTV", "Stalker $noEpisodes")
                            synchronized(this@MainActivity) {
                                val previous = prefs.getString("last_stalker_catalog_diagnostics", "").orEmpty()
                                prefs.edit().putString("last_stalker_catalog_diagnostics",
                                    (previous + " | " + noEpisodes).takeLast(14000)).apply()
                            }
                        }
                    }

                    val grouped = episodeItems.groupBy {
                        it.group.substringAfterLast("Sezon ").ifBlank { "1" }
                    }
                    val sortedRows = grouped.entries
                        .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                        .map { it.key to it.value.sortedBy { ep ->
                            Regex("\\d+").find(ep.name)?.value?.toIntOrNull() ?: Int.MAX_VALUE
                        } }
                    runOnUiThread {
                        if (detailSeries?.favoriteKey != series.favoriteKey) return@runOnUiThread
                        seriesEpisodeRows = sortedRows
                        seriesEpisodesLoading = false
                        if (sortedRows.isEmpty()) seriesEpisodesError = "Portal bu dizi için sezon veya bölüm listesi döndürmedi. Dizi kimliği: $seriesPortalId"
                        render()
                    }
                    return@thread
                }
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
        content.setBackgroundColor(Color.TRANSPARENT)
        mainContentScroll.setBackgroundColor(Color.TRANSPARENT)
        mainContentScroll.scrollTo(0, 0)
        fun goBackFromSettings() {
            // Back from a settings subsection returns to the settings hub first.
            if (settingsPage != "main") {
                settingsPage = "main"
                prefs.edit().putString("last_settings_page", "main").apply()
                render()
                return
            }
            // Only Back on the settings hub leaves Settings for the home screen.
            detailSeries = null
            detailMovie = null
            seriesEpisodeRows = emptyList()
            seriesEpisodesLoading = false
            seriesEpisodesError = ""
            sidebarMode = "main"
            selectedType = "Tümü"
            selectedGroup = "Tüm kategoriler"
            expandedCategory = null
            focusedCategoryName = null
            prefs.edit()
                .putString("last_settings_page", "main")
                .putString("last_section", selectedType)
                .putString("last_group", selectedGroup)
                .apply()
            if (::sidebar.isInitialized) sidebar.visibility = View.VISIBLE
            if (::brandView.isInitialized) brandView.visibility = View.VISIBLE
            rebuildNavigation()
            render()
        }
        fun heading(title: String, subtitle: String) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            val headingText = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headingText.addView(TextView(this).apply {
                text = t(title); textSize = 27f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            headingText.addView(TextView(this).apply {
                text = t(subtitle); textSize = 14f; setTextColor(muted); setPadding(0, dp(4), 0, 0)
            })
            header.addView(headingText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val settingsBackButton = makeButton("←  ${t("Geri")}", false) { goBackFromSettings() }
            header.addView(settingsBackButton,
                LinearLayout.LayoutParams(dp(118), dp(42)).apply { gravity = Gravity.CENTER_VERTICAL })
            content.addView(header, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                if (!prefs.getString("last_stalker_catalog_diagnostics", "").isNullOrBlank()) {
                    option("Stalker Tanılama", "Son MAC katalog sonucunu görüntüle ve kopyala") { showStalkerDiagnostics() }
                }
                option("Stalker Bölüm API Tanılaması", "Dizi bölüm isteklerinin ayrı kaydını görüntüle ve kopyala") { showStalkerSeriesEndpointDiagnostics() }
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
                                    activateSourceFromCache(source)
                                } else {
                                    selectedCategorySourceId = ""
                                    selectedGroup = "Tüm kategoriler"
                                    expandedCategory = null
                                    focusedCategoryName = null
                                    selectedType = "Tümü"
                                    focusFirstCategoryOnRender = false
                                    focusFirstContentOnRender = false
                                    // Do not clear the in-memory catalogue or overwrite
                                    // the disk cache when disabling a source. The next source
                                    // selection can reuse its already-loaded items.
                                    prefs.edit()
                                        .putString("last_group", selectedGroup)
                                        .putString("last_section", selectedType)
                                        .putString("last_source_id", "")
                                        .apply()
                                    render()
                                }
                            }
                        }
                        sourceRow.addView(sourceSelect, LinearLayout.LayoutParams(dp(54), dp(42)))
                        sourceRow.addView(makeButton(t("Yenile"), false) {
                            when (source.mode) { "xtream" -> loadXtream(source, true); "mac" -> loadStalker(source, true); else -> loadM3u(source, true) }
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
                    Triple("Antrasit • Mavi", Color.rgb(8, 10, 14), Color.rgb(0, 166, 255)),
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


            }
            "about" -> {
                heading("Hakkında", t("KaiserTV+ uygulama bilgileri"))
                val aboutPanel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(28), dp(24), dp(28), dp(24))
                    background = roundedDrawable(Color.rgb(10, 13, 19), 18)
                }
                aboutPanel.addView(makeKaiserLogoView(31f), LinearLayout.LayoutParams(dp(190), dp(58)).apply {
                    gravity = Gravity.CENTER
                    bottomMargin = dp(34)
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

                val historySections = listOf(
                    Triple("Canlı TV", "▣", "TV"),
                    Triple("Filmler", "▰", "Filmler"),
                    Triple("Diziler", "▤", "Diziler")
                )
                val historyTabs = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                historySections.forEach { section ->
                    val sectionType = section.first
                    val count = recent.count { it.type == sectionType }
                    val selected = selectedHistoryType == sectionType
                    val tab = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(9), dp(8), dp(9))
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        background = roundedDrawable(
                            if (selected) accent else panel,
                            13,
                            if (selected) accentBright else Color.rgb(58, 69, 88)
                        )
                        setOnFocusChangeListener { v, focused ->
                            if (!selected) v.background = roundedDrawable(
                                if (focused) panel else panel,
                                13,
                                if (focused) accent else Color.rgb(58, 69, 88)
                            )
                            v.scaleX = if (focused) 1.025f else 1f
                            v.scaleY = if (focused) 1.025f else 1f
                        }
                        setOnClickListener {
                            selectedHistoryType = sectionType
                            selectedHistoryKeys.clear()
                            render()
                        }
                    }
                    tab.addView(TextView(this).apply {
                        text = "${section.second}  ${t(section.third)}"
                        textSize = 16f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(if (selected) Color.WHITE else muted)
                        gravity = Gravity.CENTER
                    })
                    tab.addView(TextView(this).apply {
                        text = "$count ${t("içerik")}"
                        textSize = 11f
                        setTextColor(if (selected) Color.WHITE else muted)
                        gravity = Gravity.CENTER
                        setPadding(0, dp(3), 0, 0)
                    })
                    historyTabs.addView(tab, LinearLayout.LayoutParams(0, dp(66), 1f).apply {
                        setMargins(dp(4), dp(2), dp(4), dp(8))
                    })
                }
                content.addView(historyTabs, LinearLayout.LayoutParams(-1, dp(76)))
                val selectedEntries = recent.filter { it.type == selectedHistoryType }
                val selectedHeading = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(5), dp(8), dp(8))
                }
                selectedHeading.addView(TextView(this).apply {
                    text = t(when (selectedHistoryType) {
                        "Canlı TV" -> "İzlenen TV kanalları"
                        "Filmler" -> "İzlenen filmler"
                        else -> "İzlenen diziler"
                    })
                    textSize = 17f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                selectedHeading.addView(TextView(this).apply {
                    text = selectedEntries.size.toString()
                    textSize = 13f
                    setTextColor(accentBright)
                })
                content.addView(selectedHeading)
                val historyList = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    clipToPadding = false
                    background = roundedDrawable(panel, 14, Color.rgb(48, 57, 73))
                }
                if (selectedEntries.isEmpty()) {
                    historyList.addView(TextView(this).apply {
                        text = t("Henüz içerik yok")
                        textSize = 14f
                        setTextColor(muted)
                        gravity = Gravity.CENTER
                        setPadding(dp(8), dp(24), dp(8), dp(24))
                    })
                }
                selectedEntries.forEach { item ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(6), dp(8), dp(6))
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
                    }
                    row.addView(check, LinearLayout.LayoutParams(dp(34), dp(40)))
                    row.addView(TextView(this).apply {
                        text = item.name
                        textSize = 14f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_VERTICAL
                        setTextColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    fun updateSelection(focused: Boolean = row.hasFocus()) {
                        val isSelected = selectedHistoryKeys.contains(item.favoriteKey)
                        check.isChecked = isSelected
                        row.background = roundedDrawable(
                            if (isSelected || focused) panel else Color.TRANSPARENT,
                            10,
                            if (isSelected || focused) accent else Color.rgb(48, 57, 73)
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
                    historyList.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, dp(4))
                    })
                }
                content.addView(historyList, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(2), dp(8), dp(2), dp(8))
                })
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

    @Volatile private var lastPosterDiskTrimAt = 0L

    @Synchronized
    private fun trimPosterDiskCache(maxBytes: Long = 180L * 1024L * 1024L) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Her afiş indirmesinde tüm klasörü taramak yerine en fazla beş dakikada bir temizle.
        if (now - lastPosterDiskTrimAt < 5L * 60L * 1000L) return
        lastPosterDiskTrimAt = now
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

        try {
            posterExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                val diskFile = posterFile(address)
                if (diskFile.isFile && diskFile.length() > 0L && diskFile.length() <= 8L * 1024L * 1024L) {
                    try {
                        bitmap = decodePoster(diskFile.readBytes())
                        if (bitmap == null) diskFile.delete()
                        else diskFile.setLastModified(System.currentTimeMillis())
                    } catch (e: Exception) {
                        android.util.Log.w("KaiserTV", "Cached poster unreadable; redownloading", e)
                        diskFile.delete()
                    }
                } else if (diskFile.isFile && diskFile.length() > 8L * 1024L * 1024L) {
                    diskFile.delete()
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
        } catch (_: RejectedExecutionException) {
            // Queue is full: leave the poster as a placeholder rather than stalling the TV.
            posterRequests.remove(address)
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            posterCache.evictAll()
            android.util.Log.i("KaiserTV", "Poster memory cache cleared; trim level=" + level)
        }
    }

    override fun onDestroy() {
        getSharedPreferences("androtv_diagnostics", MODE_PRIVATE).edit()
            .putBoolean("session_active", false)
            .apply()
        previewPlayer?.release()
        previewPlayer = null
        posterExecutor.shutdownNow()
        cacheExecutor.shutdownNow()
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
        publishHomeChannel()
    }

    private var pendingSeriesEpisodes: List<MediaItem> = emptyList()

    private var openingPlayer = false
    private var fullscreenPoster: MediaItem? = null


    private fun publishHomeChannel() {
        if (!packageManager.hasSystemFeature("android.software.leanback") || android.os.Build.VERSION.SDK_INT < 26) return
        val eligible = items.filter { it.type == "Filmler" || it.type == "Diziler" }
        if (eligible.isEmpty() && recent.isEmpty()) return

        val weekly = eligible.sortedByDescending {
            it.rating.toFloatOrNull() ?: 0f
        }.take(8)
        val newest = eligible.asReversed().take(12)
        val watched = recent.filter { it.type == "Filmler" || it.type == "Diziler" }.take(12)

        val entries = mutableListOf<TvHomeEntry>()
        fun addGroup(label: String, list: List<MediaItem>) {
            list.forEach { item ->
                entries.add(TvHomeEntry(
                    key = tvHomeStableKey(item),
                    title = item.name,
                    description = "$label • ${item.plot.ifBlank { item.genre }}",
                    posterUri = item.icon.takeIf { poster ->
                        poster.isNotBlank() &&
                            (xtreamUser.isBlank() || !poster.contains(xtreamUser, ignoreCase = true)) &&
                            (xtreamPass.isBlank() || !poster.contains(xtreamPass))
                    } ?: "",
                    isSeries = item.type == "Diziler"
                ))
            }
        }
        addGroup("Haftanın Seçkisi", weekly)
        addGroup("Yeni Eklenenler", newest)
        addGroup("Son İzlenenler", watched)

        Thread {
            TvHomeChannelPublisher.publish(applicationContext, entries.distinctBy { it.key })
        }.start()
    }

    private fun tvHomeStableKey(item: MediaItem): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(item.favoriteKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun tryOpenPendingTvHomeItem() {
        val key = pendingTvHomeItemKey ?: return
        val item = items.firstOrNull { tvHomeStableKey(it) == key } ?: return
        pendingTvHomeItemKey = null
        when (item.type) {
            "Canlı TV" -> play(item)
            "Diziler" -> showSeriesDetail(item)
            "Filmler" -> showMovieDetail(item)
            else -> showItemDetails(item)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTvHomeItemKey = intent.getStringExtra(EXTRA_TV_HOME_ITEM_KEY)
        tryOpenPendingTvHomeItem()
    }

    private fun play(item: MediaItem, startOver: Boolean = false) {
        if (openingPlayer) return
        if (item.url.startsWith("stalker://") || item.url.startsWith("stalker-vod://") || item.url.startsWith("stalker-episode://")) {
            val source = savedSources.firstOrNull { it.id == item.sourceId && it.mode == "mac" }
            if (source == null) {
                Toast.makeText(this, "Stalker kaynağı bulunamadı. Kaynağı yeniden ekleyin.", Toast.LENGTH_LONG).show()
                return
            }
            val parsedUrl = android.net.Uri.parse(item.url)
            val channelId = parsedUrl.host?.takeIf { it.isNotBlank() } ?: parsedUrl.lastPathSegment.orEmpty()
            val isLive = item.url.startsWith("stalker://")
            val isVod = item.url.startsWith("stalker-vod://")
            val rawCommand = parsedUrl.getQueryParameter("cmd").orEmpty()
            val seriesNumber = parsedUrl.getQueryParameter("series") ?: "0"
            openingPlayer = true
            status.text = "${item.name}: yayın bağlantısı alınıyor…"
            thread(name = "kaiser-stalker-play") {
                try {
                    val streamUrl = if (isLive) {
                        resolveStalkerStream(source, channelId, rawCommand)
                    } else {
                        val token = stalkerHandshake(source)
                        val command = rawCommand.ifBlank { "/media/file_$channelId.mpg" }
                        fun extractPlayableLink(response: JSONObject): String {
                            fun fromObject(obj: JSONObject?): String {
                                if (obj == null) return ""
                                for (key in listOf("cmd", "url", "stream_url", "link", "playback_url")) {
                                    val value = obj.optString(key).trim()
                                    if (value.startsWith("http://", true) || value.startsWith("https://", true) ||
                                        value.startsWith("rtsp://", true) || value.startsWith("rtmp://", true) ||
                                        value.startsWith("udp://", true) || value.startsWith("ffmpeg ") ||
                                        value.startsWith("ffrt ")) return value
                                }
                                val data = obj.opt("data")
                                if (data is JSONObject) return fromObject(data)
                                if (data is String && (data.startsWith("http://") || data.startsWith("https://"))) return data
                                return ""
                            }
                            return fromObject(response).removePrefix("ffmpeg ").removePrefix("ffrt ").trim()
                        }

                        val requestParams = mapOf(
                            "type" to "vod", "action" to "create_link", "cmd" to command,
                            "series" to seriesNumber, "download" to "0",
                            "disable_ad" to "0", "force_ch_link_check" to "0"
                        )
                        val response = stalkerRequest(source, token, requestParams)
                        var resolved = extractPlayableLink(response)
                        appendStalkerSeriesEndpointDiagnostic(
                            "EPISODE_CREATE_LINK type=vod id=$channelId series=$seriesNumber cmd=${command.take(220)} responseKeys=${response.keys().asSequence().joinToString(",")} response=${response.toString().take(900)} link=${resolved.take(300)}"
                        )
                        // A few Stalker implementations expose the same series resolver under type=series.
                        if (resolved.isBlank()) {
                            val alternate = stalkerRequest(source, token, requestParams + ("type" to "series"))
                            resolved = extractPlayableLink(alternate)
                            appendStalkerSeriesEndpointDiagnostic(
                                "EPISODE_CREATE_LINK type=series id=$channelId series=$seriesNumber responseKeys=${alternate.keys().asSequence().joinToString(",")} response=${alternate.toString().take(900)} link=${resolved.take(300)}"
                            )
                        }
                        resolved.takeIf { it.isNotBlank() }
                            ?: throw Exception("Portal bölüm için oynatma adresi döndürmedi. Stalker tanılamasını kontrol edin.")
                    }
                    runOnUiThread {
                        openingPlayer = false
                        play(item.copy(url = streamUrl), startOver)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("KaiserTV", "Stalker stream resolve failed", e)
                    runOnUiThread {
                        openingPlayer = false
                        Toast.makeText(this, friendlyLoadError(e), Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
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
                .putExtra("requestHeaders", item.requestHeaders)
                .putExtra("isLive", item.type == "Canlı TV")
                .putExtra("bufferMode", prefs.getString("player_buffer", "normal"))
                .putExtra("aspectMode", prefs.getString("player_aspect", "fit"))
                .putExtra("resumeEnabled", prefs.getBoolean("player_resume", true))
                .putExtra("startOver", startOver)
            if (item.type == "Bölümler") {
                // Keep episode navigation metadata in process memory, not Intent extras.
                val episodeList = pendingSeriesEpisodes
                // Stalker episode rows contain resolver-only stalker-episode:// URIs.
                // They must never be handed to ExoPlayer as direct media URLs. The selected
                // episode has already been resolved to a real HTTP URL before this player opens.
                val hasUnresolvedStalkerEpisodes = episodeList.any {
                    it.url.startsWith("stalker-episode://", ignoreCase = true)
                }
                if (hasUnresolvedStalkerEpisodes) {
                    PlayerActivity.pendingEpisodeNames = emptyList()
                    PlayerActivity.pendingEpisodeUrls = emptyList()
                    PlayerActivity.pendingEpisodeIndex = 0
                } else {
                    PlayerActivity.pendingEpisodeNames = episodeList.map { it.name }
                    PlayerActivity.pendingEpisodeUrls = episodeList.map { it.url }
                    PlayerActivity.pendingEpisodeIndex = episodeList.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
                }
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
                PlayerActivity.pendingChannelHeaders = channels.map { it.requestHeaders }
                PlayerActivity.pendingChannelIndex = channels.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
                PlayerActivity.pendingRecentChannelNames = recentChannels.map { it.name }
                PlayerActivity.pendingRecentChannelUrls = recentChannels.map { it.url }
                PlayerActivity.pendingRecentChannelHeaders = recentChannels.map { it.requestHeaders }
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
