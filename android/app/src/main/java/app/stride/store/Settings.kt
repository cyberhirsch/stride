package app.stride.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.stride.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Session(val token: String, val userId: String, val name: String, val email: String)

data class SettingsState(
    val serverUrl: String,
    val session: Session?,
    val defaultLicense: License,
)

/**
 * App settings and the auth session, in a DataStore inside the app's private storage
 * (not included in backups: allowBackup=false).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val userName = stringPreferencesKey("user_name")
        val userEmail = stringPreferencesKey("user_email")
        val license = stringPreferencesKey("default_license")
    }

    val state: Flow<SettingsState> = context.dataStore.data.map { p ->
        val token = p[Keys.token]
        val userId = p[Keys.userId]
        SettingsState(
            serverUrl = p[Keys.serverUrl] ?: DEFAULT_SERVER_URL,
            session = if (token != null && userId != null) {
                Session(token, userId, p[Keys.userName].orEmpty(), p[Keys.userEmail].orEmpty())
            } else null,
            defaultLicense = License.of(p[Keys.license]),
        )
    }

    suspend fun current(): SettingsState = state.first()

    suspend fun setServerUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        context.dataStore.edit {
            if (clean.isEmpty() || clean == DEFAULT_SERVER_URL) it.remove(Keys.serverUrl) else it[Keys.serverUrl] = clean
        }
    }

    suspend fun setDefaultLicense(license: License) {
        context.dataStore.edit { it[Keys.license] = license.value }
    }

    suspend fun saveSession(auth: AuthResponse) {
        context.dataStore.edit {
            it[Keys.token] = auth.token
            it[Keys.userId] = auth.record.id
            it[Keys.userName] = auth.record.name.orEmpty()
            it[Keys.userEmail] = auth.record.email.orEmpty()
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(Keys.token)
            it.remove(Keys.userId)
            it.remove(Keys.userName)
            it.remove(Keys.userEmail)
        }
    }

    companion object {
        val DEFAULT_SERVER_URL: String = BuildConfig.STRIDE_API_URL
    }
}
