package com.steplayer.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.steplayer.app.app
import kotlinx.coroutines.launch

/** "Pausa" button of the download notification. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val pending = goAsync()
        context.app.appScope.launch {
            try { context.app.core.downloads.pause(id) } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.steplayer.app.download.PAUSE"
        const val EXTRA_ID = "id"
    }
}
