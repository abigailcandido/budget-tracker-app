package com.abigailcandido.budgettracker.data.local

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Must be declared once at file level. DataStore throws if two instances are
// ever created for the same file name within one process.
private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

class TokenStore(context: Context) {

    private val appContext = context.applicationContext

    private val tokenKey = stringPreferencesKey("session_token")

    /**
     * Built on first use rather than in the constructor, because it touches the
     * hardware keystore and that is slow enough to matter during app startup.
     */
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withSharedPref(appContext, KEYSET_NAME, PREF_FILE_NAME)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    val token: Flow<String?> = appContext.authDataStore.data
        .map { preferences -> preferences[tokenKey]?.let(::decrypt) }
        .flowOn(Dispatchers.IO)

    suspend fun currentToken(): String? = token.first()

    suspend fun save(token: String) = withContext(Dispatchers.IO) {
        val encrypted = encrypt(token)
        appContext.authDataStore.edit { preferences ->
            preferences[tokenKey] = encrypted
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        appContext.authDataStore.edit { preferences ->
            preferences.remove(tokenKey)
        }
        Unit
    }

    private fun encrypt(value: String): String {
        val cipherText = aead.encrypt(value.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        return Base64.encodeToString(cipherText, Base64.NO_WRAP)
    }

    /**
     * Returns null rather than throwing when the stored value cannot be read —
     * which happens legitimately if the keystore key was lost, for example after
     * the user clears app data. A null token simply means "not signed in", and
     * the next successful login overwrites it.
     */
    private fun decrypt(stored: String): String? = try {
        String(aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), ASSOCIATED_DATA), Charsets.UTF_8)
    } catch (error: GeneralSecurityException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    }

    private companion object {
        const val KEYSET_NAME = "budget_tracker_keyset"
        const val PREF_FILE_NAME = "budget_tracker_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://budget_tracker_master_key"
        val ASSOCIATED_DATA = "session_token".toByteArray(Charsets.UTF_8)
    }
}