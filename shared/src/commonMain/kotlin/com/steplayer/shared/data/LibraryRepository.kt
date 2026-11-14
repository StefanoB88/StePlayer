package com.steplayer.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.WatchProgress
import com.steplayer.shared.util.CFlow
import com.steplayer.shared.util.nowMillis
import com.steplayer.shared.util.wrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Favorites, watch progress ("continue watching") and recently watched channels. */
class LibraryRepository internal constructor(private val db: StePlayerDatabase) {
    private val q get() = db.schemaQueries

    fun isFavorite(sourceId: Long, type: ContentType, itemId: String): CFlow<Boolean> =
        q.isFavorite(sourceId, type.name, itemId).asFlow().mapToOne(Dispatchers.IO).map { it > 0 }.wrap()

    fun favoriteIds(sourceId: Long, type: ContentType): CFlow<Set<String>> =
        q.favoriteIds(sourceId, type.name).asFlow().mapToList(Dispatchers.IO).map { it.toSet() }.wrap()

    @Throws(Throwable::class)
    suspend fun toggleFavorite(sourceId: Long, type: ContentType, itemId: String): Boolean = withContext(Dispatchers.IO) {
        db.transactionWithResult {
            val fav = q.isFavorite(sourceId, type.name, itemId).executeAsOne() > 0
            if (fav) q.removeFavorite(sourceId, type.name, itemId)
            else q.addFavorite(sourceId, type.name, itemId, nowMillis())
            !fav
        }
    }

    @Throws(Throwable::class)
    suspend fun saveProgress(
        sourceId: Long, type: ContentType, itemId: String, parentId: String?,
        title: String, subtitle: String?, image: String?, positionMs: Long, durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.upsertProgress(sourceId, type.name, itemId, parentId, title, subtitle, image, positionMs, durationMs, nowMillis())
    }

    @Throws(Throwable::class)
    suspend fun progress(sourceId: Long, type: ContentType, itemId: String): WatchProgress? = withContext(Dispatchers.IO) {
        q.progressFor(sourceId, type.name, itemId).executeAsOneOrNull()?.toModel()
    }

    fun seriesProgress(sourceId: Long, seriesId: String): CFlow<Map<String, WatchProgress>> =
        q.progressOfSeries(sourceId, seriesId).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.associate { it.item_id to it.toModel() } }.wrap()

    fun continueWatching(sourceId: Long, limit: Long = 20): CFlow<List<WatchProgress>> =
        q.continueWatching(sourceId, limit).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }.wrap()

    fun recentChannels(sourceId: Long, limit: Long = 20): CFlow<List<WatchProgress>> =
        q.recentChannels(sourceId, limit).asFlow().mapToList(Dispatchers.IO).map { l -> l.map { it.toModel() } }.wrap()

    @Throws(Throwable::class)
    suspend fun removeProgress(sourceId: Long, type: ContentType, itemId: String) = withContext(Dispatchers.IO) {
        q.deleteProgress(sourceId, type.name, itemId)
    }
}
