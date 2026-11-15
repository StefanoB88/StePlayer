package com.steplayer.shared.platform

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient

/**
 * Secure storage for secrets (Xtream passwords, M3U URLs that embed tokens).
 * Android: Keystore-backed AES-GCM. Apple: Keychain (implemented in Swift).
 */
interface CredentialStore {
    fun save(key: String, value: String)
    fun load(key: String): String?
    fun delete(key: String)
}

/** Everything the shared core needs from the host platform. */
interface PlatformDependencies {
    val credentialStore: CredentialStore
    fun createSqlDriver(): SqlDriver
    fun createHttpClient(): HttpClient
    /** Absolute directory where downloaded files live. Re-resolved on every launch. */
    val downloadsDirectory: String
    /** iOS/tvOS AVPlayer only handles HLS for live; Android/ExoPlayer is happy with raw TS. */
    val prefersHlsLive: Boolean
    /** Default User-Agent sent to streaming servers. */
    val defaultUserAgent: String
}
