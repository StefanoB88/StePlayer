package com.steplayer.shared

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.steplayer.shared.platform.ApplePlatform
import com.steplayer.shared.platform.CredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplePlatformTest {
    private val store = object : CredentialStore {
        override fun save(key: String, value: String) = Unit
        override fun load(key: String): String? = null
        override fun delete(key: String) = Unit
    }

    private fun SqlDriver.pragma(name: String): String =
        executeQuery(null, "PRAGMA $name", { c -> QueryResult.Value(if (c.next().value) c.getString(0).orEmpty() else "") }, 0).value

    @Test fun sqliteIsTunedForWal() {
        val driver = ApplePlatform(store, "StePlayer/Test").createSqlDriver()
        try {
            assertEquals("wal", driver.pragma("journal_mode").lowercase())
            assertEquals("1", driver.pragma("synchronous"), "NORMAL")
            assertEquals("-4096", driver.pragma("cache_size"))
            assertEquals("1", driver.pragma("foreign_keys"))
        } finally {
            driver.close()
        }
    }
}
