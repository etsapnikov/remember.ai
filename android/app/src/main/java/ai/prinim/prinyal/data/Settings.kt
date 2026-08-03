package ai.prinim.prinyal.data

import ai.prinim.prinyal.domain.Scheduler
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalTime

private val Context.dataStore by preferencesDataStore("prinyal_settings")

/**
 * Настройки F-8. Минимум: адрес и токен бэкенда, окна дня, тумблер DeepSeek.
 *
 * Токен живёт отдельно, в EncryptedSharedPreferences (PRD §7) — в обычном DataStore
 * он лежал бы открытым текстом в бэкапе и в adb-выгрузке.
 */
class Settings(private val context: Context) {

    private val secure: SharedPreferences by lazy {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "prinyal_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var token: String
        get() = secure.getString(KEY_TOKEN, "").orEmpty()
        set(value) = secure.edit().putString(KEY_TOKEN, value).apply()

    val serverUrl: Flow<String> = context.dataStore.data.map { it[SERVER_URL].orEmpty() }

    val llmEnabled: Flow<Boolean> = context.dataStore.data.map { it[LLM_ENABLED] ?: true }

    /** Порог тишины авто-стопа: на шумной улице дефолт режет речь (§11, п. 1). */
    val silenceThreshold: Flow<Int> = context.dataStore.data.map {
        it[SILENCE_THRESHOLD] ?: DEFAULT_SILENCE_THRESHOLD
    }

    val windows: Flow<Scheduler.Windows> = context.dataStore.data.map { it.toWindows() }

    suspend fun serverUrlNow(): String = serverUrl.first()

    suspend fun llmEnabledNow(): Boolean = llmEnabled.first()

    suspend fun windowsNow(): Scheduler.Windows = windows.first()

    suspend fun silenceThresholdNow(): Int = silenceThreshold.first()

    suspend fun setServerUrl(value: String) {
        context.dataStore.edit { it[SERVER_URL] = value.trim().trimEnd('/') }
    }

    suspend fun setLlmEnabled(value: Boolean) {
        context.dataStore.edit { it[LLM_ENABLED] = value }
    }

    suspend fun setSilenceThreshold(value: Int) {
        context.dataStore.edit { it[SILENCE_THRESHOLD] = value }
    }

    suspend fun setWindow(window: Window, time: LocalTime) {
        val key = when (window) {
            Window.MORNING, Window.TOMORROW_MORNING -> W_MORNING
            Window.DAY -> W_DAY
            Window.EVENING -> W_EVENING
            Window.WEEKEND -> W_WEEKEND
        }
        context.dataStore.edit { it[key] = time.toString() }
    }

    private fun Preferences.toWindows() = Scheduler.Windows(
        morning = parse(this[W_MORNING], LocalTime.of(8, 0)),
        day = parse(this[W_DAY], LocalTime.of(12, 30)),
        evening = parse(this[W_EVENING], LocalTime.of(19, 30)),
        weekend = parse(this[W_WEEKEND], LocalTime.of(11, 0)),
    )

    private fun parse(raw: String?, fallback: LocalTime): LocalTime =
        raw?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: fallback

    companion object {
        private const val KEY_TOKEN = "backend_token"
        const val DEFAULT_SILENCE_THRESHOLD = 900

        private val SERVER_URL = stringPreferencesKey("server_url")
        private val LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        private val SILENCE_THRESHOLD = intPreferencesKey("silence_threshold")
        private val W_MORNING = stringPreferencesKey("window_morning")
        private val W_DAY = stringPreferencesKey("window_day")
        private val W_EVENING = stringPreferencesKey("window_evening")
        private val W_WEEKEND = stringPreferencesKey("window_weekend")
    }
}
