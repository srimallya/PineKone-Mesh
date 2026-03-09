package com.pinekone.app.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

class AuthRepository(
    context: Context,
    scope: CoroutineScope
) {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile(DATA_STORE_FILE)
        }

    private val mutex = Mutex()

    suspend fun isPinSet(): Boolean = mutex.withLock {
        dataStore.data.first()[KEY_PIN_HASH]?.isNotBlank() == true
    }

    suspend fun savePin(pin: String) {
        val hash = hash(pin)
        mutex.withLock {
            dataStore.edit { prefs ->
                prefs[KEY_PIN_HASH] = hash
            }
        }
    }

    suspend fun verifyPin(pin: String): Boolean = mutex.withLock {
        val stored = dataStore.data.first()[KEY_PIN_HASH]
        stored != null && stored == hash(pin)
    }

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val hashed = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hashed.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val DATA_STORE_FILE = "pk_auth.pb"
        private val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
        private const val HASH_ALGORITHM = "SHA-256"
    }
}
