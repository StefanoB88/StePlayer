package com.steplayer.shared.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.steplayer.shared.db.StePlayerDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.Properties

/** Host JVM platform, used by tests and tooling only. */
class JvmPlatform(
    private val engineClient: HttpClient? = null,
    override val downloadsDirectory: String = System.getProperty("java.io.tmpdir"),
) : PlatformDependencies {
    override val credentialStore: CredentialStore = object : CredentialStore {
        private val map = HashMap<String, String>()
        override fun save(key: String, value: String) { map[key] = value }
        override fun load(key: String) = map[key]
        override fun delete(key: String) { map.remove(key) }
    }

    override fun createSqlDriver(): SqlDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") }, StePlayerDatabase.Schema)

    override fun createHttpClient(): HttpClient = engineClient ?: HttpClient(OkHttp) { stePlayerDefaults() }
    override val prefersHlsLive = false
    override val defaultUserAgent = "StePlayer/Test"
}
