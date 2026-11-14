package com.steplayer.shared.xtream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class AuthResponseDto(
    @SerialName("user_info") val userInfo: UserInfoDto? = null,
    @SerialName("server_info") val serverInfo: ServerInfoDto? = null,
)

@Serializable
internal data class UserInfoDto(
    @Serializable(FlexString::class) val username: String? = null,
    @Serializable(FlexString::class) val message: String? = null,
    @Serializable(FlexBool::class) val auth: Boolean? = null,
    @Serializable(FlexString::class) val status: String? = null,
    @SerialName("exp_date") @Serializable(FlexLong::class) val expDate: Long? = null,
    @SerialName("is_trial") @Serializable(FlexBool::class) val isTrial: Boolean? = null,
    @SerialName("active_cons") @Serializable(FlexInt::class) val activeCons: Int? = null,
    @SerialName("max_connections") @Serializable(FlexInt::class) val maxConnections: Int? = null,
    @SerialName("allowed_output_formats") val allowedOutputFormats: JsonElement? = null,
)

@Serializable
internal data class ServerInfoDto(
    @Serializable(FlexString::class) val url: String? = null,
    @Serializable(FlexString::class) val port: String? = null,
    @SerialName("https_port") @Serializable(FlexString::class) val httpsPort: String? = null,
    @SerialName("server_protocol") @Serializable(FlexString::class) val serverProtocol: String? = null,
    @Serializable(FlexString::class) val timezone: String? = null,
    @SerialName("timestamp_now") @Serializable(FlexLong::class) val timestampNow: Long? = null,
    @SerialName("time_now") @Serializable(FlexString::class) val timeNow: String? = null,
)

@Serializable
internal data class CategoryDto(
    @SerialName("category_id") @Serializable(FlexString::class) val categoryId: String? = null,
    @SerialName("category_name") @Serializable(FlexString::class) val categoryName: String? = null,
)

@Serializable
internal data class LiveStreamDto(
    @Serializable(FlexInt::class) val num: Int? = null,
    @Serializable(FlexString::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(FlexString::class) val streamId: String? = null,
    @SerialName("stream_icon") @Serializable(FlexString::class) val streamIcon: String? = null,
    @SerialName("epg_channel_id") @Serializable(FlexString::class) val epgChannelId: String? = null,
    @SerialName("category_id") @Serializable(FlexString::class) val categoryId: String? = null,
    @SerialName("tv_archive") @Serializable(FlexBool::class) val tvArchive: Boolean? = null,
    @SerialName("tv_archive_duration") @Serializable(FlexInt::class) val tvArchiveDuration: Int? = null,
)

@Serializable
internal data class VodStreamDto(
    @Serializable(FlexString::class) val name: String? = null,
    @SerialName("stream_id") @Serializable(FlexString::class) val streamId: String? = null,
    @SerialName("stream_icon") @Serializable(FlexString::class) val streamIcon: String? = null,
    @Serializable(FlexDouble::class) val rating: Double? = null,
    @Serializable(FlexLong::class) val added: Long? = null,
    @SerialName("category_id") @Serializable(FlexString::class) val categoryId: String? = null,
    @SerialName("container_extension") @Serializable(FlexString::class) val containerExtension: String? = null,
    @Serializable(FlexString::class) val year: String? = null,
)

@Serializable
internal data class SeriesDto(
    @Serializable(FlexString::class) val name: String? = null,
    @SerialName("series_id") @Serializable(FlexString::class) val seriesId: String? = null,
    @Serializable(FlexString::class) val cover: String? = null,
    @Serializable(FlexString::class) val plot: String? = null,
    @Serializable(FlexString::class) val genre: String? = null,
    @Serializable(FlexString::class) val releaseDate: String? = null,
    @SerialName("release_date") @Serializable(FlexString::class) val releaseDate2: String? = null,
    @SerialName("last_modified") @Serializable(FlexLong::class) val lastModified: Long? = null,
    @Serializable(FlexDouble::class) val rating: Double? = null,
    @SerialName("category_id") @Serializable(FlexString::class) val categoryId: String? = null,
    @Serializable(FlexString::class) val year: String? = null,
)
