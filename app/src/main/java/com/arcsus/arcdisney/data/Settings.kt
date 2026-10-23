package com.arcsus.arcdisney.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** The TMDB key typed into the app (for builds made without one), the AllDebrid sign-in, and first-run setup. */
class Settings(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val TMDB_KEY = stringPreferencesKey("tmdb_key")
        val SKIPPED_KEY = booleanPreferencesKey("skipped_tmdb_key")
        val SETUP_DONE = booleanPreferencesKey("setup_done")
        val LAST_VERSION = stringPreferencesKey("last_version")
        val ALLDEBRID_KEY = stringPreferencesKey("alldebrid_key")
        val DEBRID_OFFERED = booleanPreferencesKey("debrid_offered")
    }

    /** This device's own AllDebrid API key, from its PIN sign-in ("" when signed out). */
    val allDebridKey: Flow<String> = store.data.map { it[Keys.ALLDEBRID_KEY].orEmpty() }

    suspend fun saveAllDebridKey(key: String) {
        store.edit { if (key.isBlank()) it.remove(Keys.ALLDEBRID_KEY) else it[Keys.ALLDEBRID_KEY] = key }
    }

    /** First run has offered the AllDebrid sign-in (taken or skipped). */
    suspend fun debridOffered(): Boolean = store.data.first()[Keys.DEBRID_OFFERED] == true

    suspend fun markDebridOffered() {
        store.edit { it[Keys.DEBRID_OFFERED] = true }
    }

    /** Records the running version; returns the one before it (null on first run). */
    suspend fun swapVersion(current: String): String? {
        val last = store.data.first()[Keys.LAST_VERSION]
        if (last != current) store.edit { it[Keys.LAST_VERSION] = current }
        return last
    }

    /** First-run setup (getting the device online) has been finished or skipped. */
    suspend fun setupDone(): Boolean = store.data.first()[Keys.SETUP_DONE] == true

    suspend fun finishSetup() {
        store.edit { it[Keys.SETUP_DONE] = true }
    }

    suspend fun tmdbKey(): String = store.data.first()[Keys.TMDB_KEY].orEmpty()

    /** "Just re-check" was chosen, so the update button stops asking for a key. */
    suspend fun skippedKey(): Boolean = store.data.first()[Keys.SKIPPED_KEY] == true

    suspend fun saveTmdbKey(key: String) {
        store.edit {
            it[Keys.TMDB_KEY] = key.trim()
            it[Keys.SKIPPED_KEY] = false
        }
    }

    suspend fun skipTmdbKey() {
        store.edit { it[Keys.SKIPPED_KEY] = true }
    }
}
