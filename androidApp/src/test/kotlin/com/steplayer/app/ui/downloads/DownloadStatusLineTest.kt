package com.steplayer.app.ui.downloads

import com.steplayer.app.download.SpeedInfo
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.Download
import com.steplayer.shared.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStatusLineTest {
    private val gb = (1.2 * (1L shl 30)).toLong()
    private fun d(status: DownloadStatus, done: Long = 300L shl 20, total: Long = gb, error: String? = null) =
        Download("1:MOVIE:1", 1, ContentType.MOVIE, "1", null, "Film", null, null, "mp4", status, null, done, total, error, 0)
    private fun line(x: Download, speed: SpeedInfo? = null) = downloadStatusLine(x, speed, "In attesa", "In pausa", "Completato", "Non riuscito")

    @Test fun everyStateHasOneLine() {
        assertEquals("24% di 1,2 GB · 2,4 MB/s · 3 min",
            line(d(DownloadStatus.RUNNING), SpeedInfo((2.4 * (1L shl 20)).toLong(), 150, 2)))
        assertEquals("In pausa · 300 MB di 1,2 GB", line(d(DownloadStatus.PAUSED)))
        assertEquals("In attesa · 1,2 GB", line(d(DownloadStatus.QUEUED)))
        assertEquals("In attesa", line(d(DownloadStatus.QUEUED, 0, 0)))
        assertEquals("Completato · 1,2 GB", line(d(DownloadStatus.COMPLETED, gb)))
        assertEquals("Non riuscito · Spazio esaurito", line(d(DownloadStatus.FAILED, error = "Spazio esaurito")))
    }
}
