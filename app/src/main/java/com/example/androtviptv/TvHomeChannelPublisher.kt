package com.example.androtviptv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat

data class TvHomeEntry(
    val key: String,
    val title: String,
    val description: String,
    val posterUri: String,
    val isSeries: Boolean
)

/**
 * Publishes a single KaiserTV+ preview channel containing three curated groups:
 * weekly movie/series picks, recently added titles, and recently watched titles.
 * Android TV requires API 26+ for app preview channels.
 */
object TvHomeChannelPublisher {
    private const val PREF_CHANNEL_ID = "tv_home_preview_channel_id"
    private const val CHANNEL_PROVIDER_ID = "kaisertv-preview-channel-v1"

    fun publish(context: Context, entries: List<TvHomeEntry>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!context.packageManager.hasSystemFeature("android.software.leanback")) return
        if (entries.isEmpty()) return

        try {
            val helper = PreviewChannelHelper(context)
            val prefs = context.getSharedPreferences("androtv", Context.MODE_PRIVATE)
            var channelId = prefs.getLong(PREF_CHANNEL_ID, -1L)
            var channelExists = channelId > 0 && helper.getPreviewChannel(channelId) != null

            if (!channelExists) {
                val logo = try {
                    val encoded = context.assets.open("kaisertv_logo.jpg.b64").bufferedReader().use { it.readText().trim() }
                    val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) { null }

                val appIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                val builder = PreviewChannel.Builder()
                    .setDisplayName("KaiserTV+")
                    .setDescription("Haftanın seçkisi, yeni eklenenler ve son izlediklerin")
                    .setAppLinkIntent(appIntent)
                    .setInternalProviderId(CHANNEL_PROVIDER_ID)
                if (logo != null) builder.setLogo(logo)
                val channel = builder.build()
                channelId = helper.publishDefaultChannel(channel)
                if (channelId > 0) prefs.edit().putLong(PREF_CHANNEL_ID, channelId).apply()
                logo?.recycle()
                channelExists = channelId > 0
            }

            if (!channelExists) return

            // Refresh this app's row without creating duplicates.
            context.contentResolver.delete(
                TvContractCompat.buildProgramsUriForChannel(channelId),
                null,
                null
            )

            entries.take(45).forEachIndexed { index, entry ->
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(MainActivity.EXTRA_TV_HOME_ITEM_KEY, entry.key)
                }
                val program = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(if (entry.isSeries) PreviewProgram.TYPE_TV_EPISODE else PreviewProgram.TYPE_MOVIE)
                    .setTitle(entry.title.take(180))
                    .setDescription(entry.description.take(500))
                    .setIntentUri(Uri.parse(launchIntent.toUri(Intent.URI_INTENT_SCHEME)))
                    .setInternalProviderId(entry.key.take(200))
                    .setWeight(1000 - index)
                    .apply {
                        if (entry.posterUri.startsWith("https://") || entry.posterUri.startsWith("http://")) {
                            setPosterArtUri(Uri.parse(entry.posterUri))
                        }
                    }
                    .build()
                helper.publishPreviewProgram(program)
            }
        } catch (security: SecurityException) {
            android.util.Log.w("KaiserTV", "Android TV home channel permission is not available", security)
        } catch (error: Exception) {
            android.util.Log.e("KaiserTV", "Could not publish Android TV home channel", error)
        }
    }
}
