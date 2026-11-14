package com.steplayer.app.player

import android.content.Context
import android.content.Intent

enum class PlayKind { LIVE, MOVIE, EPISODE, CATCHUP }

/** What to play. Carried in the Intent so the player can be (re)created from scratch. */
data class PlayRequest(
    val kind: PlayKind,
    val sourceId: Long,
    val itemId: String,
    val categoryId: String? = null,
    val seriesId: String? = null,
    /** -1 = resume from saved progress, 0 = from start, >0 = explicit position. */
    val startMs: Long = -1,
    val catchupStart: Long = 0,
    val catchupEnd: Long = 0,
    val title: String? = null,
    val subtitle: String? = null,
    val extension: String? = null,
) {
    fun toIntent(context: Context) = Intent(context, PlayerActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0)
        putExtra(K_KIND, kind.name)
        putExtra(K_SOURCE, sourceId)
        putExtra(K_ITEM, itemId)
        putExtra(K_CATEGORY, categoryId)
        putExtra(K_SERIES, seriesId)
        putExtra(K_START, startMs)
        putExtra(K_CU_START, catchupStart)
        putExtra(K_CU_END, catchupEnd)
        putExtra(K_TITLE, title)
        putExtra(K_SUBTITLE, subtitle)
        putExtra(K_EXT, extension)
    }

    companion object {
        private const val K_KIND = "kind"
        private const val K_SOURCE = "source"
        private const val K_ITEM = "item"
        private const val K_CATEGORY = "category"
        private const val K_SERIES = "series"
        private const val K_START = "start"
        private const val K_CU_START = "cu_start"
        private const val K_CU_END = "cu_end"
        private const val K_TITLE = "title"
        private const val K_SUBTITLE = "subtitle"
        private const val K_EXT = "ext"

        fun from(intent: Intent): PlayRequest? {
            val kind = intent.getStringExtra(K_KIND)?.let { runCatching { PlayKind.valueOf(it) }.getOrNull() } ?: return null
            return PlayRequest(
                kind = kind,
                sourceId = intent.getLongExtra(K_SOURCE, -1),
                itemId = intent.getStringExtra(K_ITEM) ?: return null,
                categoryId = intent.getStringExtra(K_CATEGORY),
                seriesId = intent.getStringExtra(K_SERIES),
                startMs = intent.getLongExtra(K_START, -1),
                catchupStart = intent.getLongExtra(K_CU_START, 0),
                catchupEnd = intent.getLongExtra(K_CU_END, 0),
                title = intent.getStringExtra(K_TITLE),
                subtitle = intent.getStringExtra(K_SUBTITLE),
                extension = intent.getStringExtra(K_EXT),
            )
        }
    }
}

object PlayerLauncher {
    fun live(context: Context, sourceId: Long, channelId: String, categoryId: String?) =
        context.startActivity(PlayRequest(PlayKind.LIVE, sourceId, channelId, categoryId = categoryId).toIntent(context))

    fun movie(context: Context, sourceId: Long, movieId: String, startMs: Long, offlineTitle: String? = null, offlineExt: String? = null) =
        context.startActivity(PlayRequest(PlayKind.MOVIE, sourceId, movieId, startMs = startMs, title = offlineTitle, extension = offlineExt).toIntent(context))

    fun episode(
        context: Context, sourceId: Long, seriesId: String, episodeId: String, startMs: Long,
        offlineTitle: String? = null, offlineSubtitle: String? = null, offlineExt: String? = null,
    ) = context.startActivity(
        PlayRequest(PlayKind.EPISODE, sourceId, episodeId, seriesId = seriesId, startMs = startMs, title = offlineTitle, subtitle = offlineSubtitle, extension = offlineExt)
            .toIntent(context),
    )

    fun catchup(context: Context, sourceId: Long, channelId: String, start: Long, end: Long, title: String) =
        context.startActivity(PlayRequest(PlayKind.CATCHUP, sourceId, channelId, catchupStart = start, catchupEnd = end, title = title).toIntent(context))
}
