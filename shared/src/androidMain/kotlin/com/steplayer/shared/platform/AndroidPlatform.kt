package com.steplayer.shared.platform

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.steplayer.shared.db.StePlayerDatabase
import com.steplayer.shared.net.NetworkStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidPlatform(
    private val context: Context,
    override val defaultUserAgent: String,
) : PlatformDependencies {

    override val credentialStore: CredentialStore = KeystoreCredentialStore(context)

    override fun createSqlDriver(): SqlDriver = AndroidSqliteDriver(
        schema = StePlayerDatabase.Schema,
        context = context,
        name = "steplayer.db",
        callback = object : AndroidSqliteDriver.Callback(StePlayerDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
                db.enableWriteAheadLogging()
                // With WAL, NORMAL is crash-safe (only the last commits may roll back on power
                // loss, never corruption) and avoids an fsync per transaction during big imports.
                // query() works for every PRAGMA on every Android version (execSQL can refuse
                // pragmas on some builds); tuning must never prevent the app from starting.
                runCatching { db.query("PRAGMA synchronous = NORMAL").close() }
                // 8 MiB page cache (default 2 MiB): big catalog/EPG queries stay in memory.
                runCatching { db.query("PRAGMA cache_size = -8192").close() }
            }
        },
    )

    override fun createHttpClient(): HttpClient = HttpClient(OkHttp) {
        stePlayerDefaults()
        engine {
            config {
                retryOnConnectionFailure(true)
                followSslRedirects(true)
            }
        }
    }

    /** From ConnectivityManager: Android itself checks whether Internet works (VALIDATED). */
    override fun networkStatus(): NetworkStatus = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        when {
            cm == null -> NetworkStatus.UNKNOWN
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkStatus.OFFLINE
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetworkStatus.CONNECTED_NO_INTERNET
            else -> NetworkStatus.ONLINE
        }
    } catch (e: Exception) {
        NetworkStatus.UNKNOWN
    }

    override val downloadsDirectory: String
        // Private internal storage: not reachable by other apps, file managers or USB, not backed up.
        get() = File(context.noBackupFilesDir, "downloads").apply { mkdirs() }.absolutePath

    override val prefersHlsLive: Boolean = false
}

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore.
 * Ciphertext is stored in private SharedPreferences, excluded from cloud backup.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("secure_store", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun save(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        prefs.edit().putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    override fun load(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, 12)
            val data = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val ALIAS = "steplayer_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
