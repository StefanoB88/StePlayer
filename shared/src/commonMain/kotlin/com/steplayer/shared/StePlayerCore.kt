package com.steplayer.shared

import com.steplayer.shared.data.CatalogRepository
import com.steplayer.shared.data.DownloadRepository
import com.steplayer.shared.data.EpgRepository
import com.steplayer.shared.data.LibraryRepository
import com.steplayer.shared.data.SourceRepository
import com.steplayer.shared.data.StreamResolver
import com.steplayer.shared.data.SyncManager
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.platform.PlatformDependencies
import com.steplayer.shared.playback.PlaybackManager
import com.steplayer.shared.reminders.ExpiryReminders
import com.steplayer.shared.pairing.PairingServer
import com.steplayer.shared.settings.PlayerPreferences
import com.steplayer.shared.diagnostics.ConnectionTest
import com.steplayer.shared.search.SearchIndex
import com.steplayer.shared.search.SmartSearch

/**
 * Single entry point of the shared core. Create exactly one per process
 * (Android: Application; iOS: App struct) and keep it for the app lifetime.
 */
class StePlayerCore(platform: PlatformDependencies) {
    private val driver = platform.createSqlDriver()
    private val database = StePlayerDatabase(driver)
    private val http = platform.createHttpClient()
    private val searchIndex = SearchIndex(driver, database)

    val sources = SourceRepository(database, platform.credentialStore, http, searchIndex)
    val epg = EpgRepository(database, driver, http, sources, platform.defaultUserAgent, searchIndex)
    val catalog = CatalogRepository(database, http, sources, platform.defaultUserAgent)
    val sync = SyncManager(database, http, sources, epg, platform.defaultUserAgent, searchIndex)

    /** Global smart search across channels, movies, series and the whole TV guide. */
    val search = SmartSearch(database, searchIndex)
    val library = LibraryRepository(database)
    val streams = StreamResolver(database, sources, platform.downloadsDirectory, platform.prefersHlsLive, platform.defaultUserAgent)
    val downloads = DownloadRepository(database, streams)

    /** Connection leases, error translation, reconnection and stall detection for players. */
    val playback = PlaybackManager(sources, http, platform.defaultUserAgent)

    /** Subscription expiry reminders (3 days / 1 day before) and home banners. */
    val reminders = ExpiryReminders(database, sources)

    /** "Configura la TV col telefono": local QR pairing server (TV apps only). */
    val pairing = PairingServer(sources)

    /** Player preferences: buffer, "still watching", audio/subtitle languages, style, A/V offsets. */
    val playerPreferences = PlayerPreferences(database)

    /** "Test connessione": home network vs provider speed. */
    val connectionTest = ConnectionTest(http, sources, catalog, streams, playback.connections)
}
