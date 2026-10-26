package com.ethora.chat.core.e2ee

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * [OmemoStore] backed by EncryptedSharedPreferences, one file per account. The
 * device's private keys and sessions are encrypted at rest, unlike web, which
 * keeps them in plain IndexedDB.
 */
class OmemoPrefsStore private constructor(private val prefs: SharedPreferences) : OmemoStore {

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    companion object {
        private val masterKeyLock = Any()
        @Volatile private var masterKey: MasterKey? = null

        fun forAccount(context: Context, accountJid: String): OmemoPrefsStore {
            val app = context.applicationContext
            val key = synchronized(masterKeyLock) {
                masterKey ?: MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                    .also { masterKey = it }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(accountJid.lowercase().toByteArray(Charsets.UTF_8))
            val name = "ethora_omemo_" + OmemoCrypto.toHex(digest).take(24)
            val prefs = EncryptedSharedPreferences.create(
                app,
                name,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return OmemoPrefsStore(prefs)
        }
    }
}
