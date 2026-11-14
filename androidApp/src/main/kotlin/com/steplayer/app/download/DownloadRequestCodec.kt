package com.steplayer.app.download

import android.os.PersistableBundle
import androidx.work.Data
import androidx.work.workDataOf
import com.steplayer.shared.data.DownloadRequest

/** (De)serialises a DownloadRequest for JobScheduler extras and WorkManager input data. */
internal object DownloadRequestCodec {
    private const val ID = "id"
    private const val URL = "url"
    private const val FILE = "file"
    private const val TITLE = "title"
    private const val HEADER_NAMES = "header_names"
    private const val HEADER_VALUES = "header_values"
    private const val ATTEMPT = "attempt"

    fun toBundle(r: DownloadRequest, attempt: Int = 0) = PersistableBundle().apply {
        putString(ID, r.id); putString(URL, r.url); putString(FILE, r.fileName); putString(TITLE, r.title)
        putStringArray(HEADER_NAMES, r.headers.keys.toTypedArray())
        putStringArray(HEADER_VALUES, r.headers.values.toTypedArray())
        putInt(ATTEMPT, attempt)
    }

    fun fromBundle(b: PersistableBundle): DownloadRequest? {
        val names = b.getStringArray(HEADER_NAMES).orEmpty()
        val values = b.getStringArray(HEADER_VALUES).orEmpty()
        return DownloadRequest(
            id = b.getString(ID) ?: return null,
            url = b.getString(URL) ?: return null,
            headers = names.indices.associate { names[it] to values.getOrElse(it) { "" } },
            fileName = b.getString(FILE) ?: return null,
            title = b.getString(TITLE).orEmpty(),
        )
    }

    fun attempt(b: PersistableBundle) = b.getInt(ATTEMPT, 0)

    fun toData(r: DownloadRequest): Data = workDataOf(
        ID to r.id, URL to r.url, FILE to r.fileName, TITLE to r.title,
        HEADER_NAMES to r.headers.keys.toTypedArray(), HEADER_VALUES to r.headers.values.toTypedArray(),
    )

    fun fromData(d: Data): DownloadRequest? {
        val names = d.getStringArray(HEADER_NAMES).orEmpty()
        val values = d.getStringArray(HEADER_VALUES).orEmpty()
        return DownloadRequest(
            id = d.getString(ID) ?: return null,
            url = d.getString(URL) ?: return null,
            headers = names.indices.associate { names[it] to values.getOrElse(it) { "" } },
            fileName = d.getString(FILE) ?: return null,
            title = d.getString(TITLE).orEmpty(),
        )
    }
}
