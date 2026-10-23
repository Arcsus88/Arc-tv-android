package com.arcsus.arcdisney.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** The TMDB key typed into the app, for builds made without one. */
class Settings(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val TMDB_KEY = stringPreferencesKey("tmdb_key")
        val SKIPPED_KEY = booleanPreferencesKey("skipped_tmdb_key")
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
