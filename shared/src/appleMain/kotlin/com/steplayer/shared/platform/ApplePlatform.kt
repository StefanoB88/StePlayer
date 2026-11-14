package com.steplayer.shared.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.SynchronousFlag
import com.steplayer.shared.StePlayerCore
import com.steplayer.shared.db.StePlayerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSUserDomainMask

/**
 * Apple implementation. The Keychain store is provided from Swift ([credentialStore])
 * because the Security framework is far nicer to use there.
 */
class ApplePlatform(
    override val credentialStore: CredentialStore,
    override val defaultUserAgent: String,
    /** tvOS has no persistent storage guarantees: use Caches there. */
    private val useCachesForDownloads: Boolean = false,
) : PlatformDependencies {

    override fun createSqlDriver(): SqlDriver {
        val schema = StePlayerDatabase.Schema
        return NativeSqliteDriver(
            DatabaseConfiguration(
                name = "steplayer.db",
                version = if (schema.version > Int.MAX_VALUE) error("Schema version too large") else schema.version.toInt(),
                create = { connection -> wrapConnection(connection) { schema.create(it) } },
                upgrade = { connection, old, new -> wrapConnection(connection) { schema.migrate(it, old.toLong(), new.toLong()) } },
                // WAL: readers never block the writer (lists stay responsive during sync/EPG import).
                journalMode = JournalMode.WAL,
                extendedConfig = DatabaseConfiguration.Extended(
                    foreignKeyConstraints = true,
                    // Crash-safe with WAL; no fsync per transaction.
                    synchronousFlag = SynchronousFlag.NORMAL,
                ),
                lifecycleConfig = DatabaseConfiguration.Lifecycle(
                    // 4 MiB page cache per connection (default 2 MiB).
                    onCreateConnection = { connection -> connection.rawExecSql("PRAGMA cache_size = -4096") },
                ),
            ),
            // Several flows query at once (lists, now/next, badges): allow parallel WAL readers.
            maxReaderConnections = 4,
        )
    }

    override fun createHttpClient(): HttpClient = HttpClient(Darwin) {
        stePlayerDefaults()
        engine {
            configureSession {
                allowsCellularAccess = true
                HTTPMaximumConnectionsPerHost = 6
            }
        }
    }

    override val downloadsDirectory: String
        get() {
            val dir = if (useCachesForDownloads) NSCachesDirectory else NSApplicationSupportDirectory
            val base = NSSearchPathForDirectoriesInDomains(dir, NSUserDomainMask, true).first() as String
            val path = "$base/Downloads"
            NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
            return path
        }

    override val prefersHlsLive: Boolean = true
}

/** Convenience for Swift: `StePlayerCoreKt.createCore(store, ua)`. */
fun createCore(credentialStore: CredentialStore, userAgent: String, isTv: Boolean): StePlayerCore =
    StePlayerCore(ApplePlatform(credentialStore, userAgent, useCachesForDownloads = isTv))
