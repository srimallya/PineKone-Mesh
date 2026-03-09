package com.pinekone.app.identity

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.pinekone.app.crypto.PkCrypto
import java.time.Instant
import java.util.UUID

data class PkIdentity(
    val nodeId: String,
    val displayName: String,
    val publicKey: ByteArray,
    val secretKey: ByteArray,
    val createdAt: Instant
) {
    val fingerprint: ByteArray = PkCrypto.fingerprint(publicKey)
}

class IdentityRepository(
    context: Context,
    private val scope: CoroutineScope
) {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            context.preferencesDataStoreFile(DATA_STORE_FILE)
        }

    private val identityMutex = Mutex()

    val identityFlow: Flow<PkIdentity> = dataStore.data
        .catch { throwable ->
            if (throwable is java.io.IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs ->
            val encoded = prefs[KEY_IDENTITY]
            if (encoded.isNullOrBlank()) {
                val fresh = createIdentity()
                scope.launch { persistIdentity(fresh) }
                fresh
            } else {
                decodeIdentity(encoded)
            }
        }

    suspend fun getIdentity(): PkIdentity {
        return identityMutex.withLock {
            identityFlow.first()
        }
    }

    private fun encode(identity: PkIdentity): String {
        val pkHex = identity.publicKey.joinToString("") { "%02x".format(it) }
        val skHex = identity.secretKey.joinToString("") { "%02x".format(it) }
        return listOf(
            identity.nodeId,
            identity.displayName,
            pkHex,
            skHex,
            identity.createdAt.epochSecond.toString()
        ).joinToString("|")
    }

    private fun decodeIdentity(encoded: String): PkIdentity {
        val parts = encoded.split("|")
        require(parts.size == 5) { "Corrupt identity record" }
        return PkIdentity(
            nodeId = parts[0],
            displayName = parts[1],
            publicKey = parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            secretKey = parts[3].chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            createdAt = Instant.ofEpochSecond(parts[4].toLong())
        )
    }

    private suspend fun persistIdentity(identity: PkIdentity) {
        dataStore.edit { prefs ->
            prefs[KEY_IDENTITY] = encode(identity)
        }
    }

    private fun createIdentity(): PkIdentity {
        val nodeId = UUID.randomUUID().toString()
        val displayName = "Relay ${nodeId.substring(0, 4)}"
        val (publicKey, secretKey) = PkCrypto.generateKeyPair()
        return PkIdentity(
            nodeId = nodeId,
            displayName = displayName,
            publicKey = publicKey,
            secretKey = secretKey,
            createdAt = Instant.now()
        )
    }

    companion object {
        private const val DATA_STORE_FILE = "pk_identity.pb"
        private val KEY_IDENTITY = stringPreferencesKey("identity")
    }
}
