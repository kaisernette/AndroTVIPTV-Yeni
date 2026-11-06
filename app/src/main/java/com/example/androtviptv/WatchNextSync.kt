package com.example.androtviptv

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram

internal object WatchNextSync {
    const val KIND_MOVIE = 0
    const val KIND_SERIES = 1
    const val TYPE_CONTINUE = 0
    const val TYPE_WATCHLIST = 3
    private const val PREFIX = "kaisertv:"

    data class Entry(
        val key: String, val sourceId: String, val itemId: String, val title: String,
        val description: String, val poster: String, val kind: Int, val watchType: Int,
        val positionMs: Long, val durationMs: Long
    )

    fun sync(context: Context, entries: List<Entry>) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val wanted = entries.distinctBy { it.key }.take(30)
            wanted.forEach { upsert(context, it) }
            val wantedIds = wanted.map { PREFIX + it.key }.toSet()
            val resolver = context.contentResolver
            val projection = arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                val keyIndex = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                while (cursor.moveToNext()) {
                    val internalId = cursor.getString(keyIndex).orEmpty()
                    if (internalId.startsWith(PREFIX) && internalId !in wantedIds) {
                        resolver.delete(TvContractCompat.buildWatchNextProgramUri(cursor.getLong(idIndex)), null, null)
                    }
                }
            }
        }
    }

    fun upsert(context: Context, entry: Entry) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val resolver = context.contentResolver
            val internalId = PREFIX + entry.key
            val launchUri = Uri.Builder().scheme("kaisertv").authority("watch-next")
                .appendQueryParameter("source", entry.sourceId).appendQueryParameter("id", entry.itemId).build()
            val builder = WatchNextProgram.Builder()
                .setType(if (entry.kind == KIND_SERIES) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setWatchNextType(entry.watchType)
                .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                .setTitle(entry.title.take(180))
                .setDescription(entry.description.take(500))
                .setIntentUri(launchUri)
                .setInternalProviderId(internalId)
                .setContentId(internalId)
            if (entry.poster.startsWith("http://") || entry.poster.startsWith("https://")) builder.setPosterArtUri(Uri.parse(entry.poster))
            if (entry.durationMs > 0L) builder.setDurationMillis(entry.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (entry.positionMs > 0L) builder.setLastPlaybackPositionMillis(entry.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val values = builder.build().toContentValues()
            val projection = arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            var existingId: Long? = null
            resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                val keyIndex = cursor.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
                while (cursor.moveToNext()) if (cursor.getString(keyIndex) == internalId) { existingId = cursor.getLong(idIndex); break }
            }
            if (existingId != null) resolver.update(TvContractCompat.buildWatchNextProgramUri(existingId!!), values, null, null)
            else resolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
        }
    }
}
