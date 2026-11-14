package com.steplayer.shared.data

import com.steplayer.shared.model.Channel
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Download
import com.steplayer.shared.model.DownloadStatus
import com.steplayer.shared.model.EpgProgram
import com.steplayer.shared.model.Episode
import com.steplayer.shared.model.Movie
import com.steplayer.shared.model.Series
import com.steplayer.shared.model.Source
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.model.WatchProgress
import com.steplayer.shared.db.Channel as DbChannel
import com.steplayer.shared.db.Download as DbDownload
import com.steplayer.shared.db.Epg_program as DbProgram
import com.steplayer.shared.db.Episode as DbEpisode
import com.steplayer.shared.db.Movie as DbMovie
import com.steplayer.shared.db.Series as DbSeries
import com.steplayer.shared.db.Source as DbSource
import com.steplayer.shared.db.Watch_progress as DbProgress

internal fun DbSource.toModel() = Source(
    id = id,
    name = name,
    type = SourceType.valueOf(type),
    url = url,
    username = username,
    epgUrl = epg_url,
    userAgent = user_agent,
    serverTimeOffsetSec = server_time_offset_sec,
    allowedFormats = allowed_formats?.split(',')?.filter { it.isNotBlank() } ?: emptyList(),
    expiresAt = expires_at,
    maxConnections = max_connections?.toInt(),
    lastSyncAt = last_sync_at,
    lastEpgSyncAt = last_epg_sync_at,
)

internal fun DbChannel.toModel() = Channel(
    sourceId = source_id, id = id, num = num?.toInt(), name = name, logo = logo,
    categoryId = category_id, epgId = epg_id, archiveDays = archive_days.toInt(),
)

internal fun DbMovie.toModel() = Movie(
    sourceId = source_id, id = id, name = name, poster = poster, categoryId = category_id,
    rating = rating, year = year?.toInt(), extension = extension, addedAt = added_at,
)

internal fun DbSeries.toModel() = Series(
    sourceId = source_id, id = id, name = name, cover = cover, categoryId = category_id,
    plot = plot, rating = rating, year = year?.toInt(), genre = genre,
)

internal fun DbEpisode.toModel() = Episode(
    sourceId = source_id, seriesId = series_id, id = id, season = season.toInt(), number = number.toInt(),
    title = title, plot = plot, image = image, durationSec = duration_sec?.toInt(), extension = extension,
)

internal fun DbProgram.toModel() = EpgProgram(
    channelEpgId = channel_epg_id, startAt = start_at, endAt = end_at, title = title, description = description,
)

internal fun DbProgress.toModel() = WatchProgress(
    sourceId = source_id, type = ContentType.valueOf(type), itemId = item_id, parentId = parent_id,
    title = title, subtitle = subtitle, image = image, positionMs = position_ms, durationMs = duration_ms,
    updatedAt = updated_at,
)

internal fun DbDownload.toModel() = Download(
    id = id, sourceId = source_id, type = ContentType.valueOf(type), itemId = item_id, parentId = parent_id,
    title = title, subtitle = subtitle, image = image, extension = extension,
    status = DownloadStatus.valueOf(status), filePath = file_path, bytesDownloaded = bytes_downloaded,
    totalBytes = total_bytes, error = error, createdAt = created_at,
)
