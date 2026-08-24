package ai.prinim.prinyal.data

import ai.prinim.prinyal.domain.Scheduler
import ai.prinim.prinyal.capture.SilenceWindow
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

    /** Переопределение вшитого ключа DeepSeek — чтобы сменить его без пересборки. */
    var deepSeekKey: String
        get() = secure.getString(KEY_DEEPSEEK, "").orEmpty()
        set(value) = secure.edit().putString(KEY_DEEPSEEK, value).apply()

    val serverUrl: Flow<String> = context.dataStore.data.map { it[SERVER_URL].orEmpty() }

    val llmEnabled: Flow<Boolean> = context.dataStore.data.map { it[LLM_ENABLED] ?: true }

    /** Порог тишины авто-стопа: на шумной улице дефолт режет речь (§11, п. 1). */
    val silenceThreshold: Flow<Int> = context.dataStore.data.map {
        it[SILENCE_THRESHOLD] ?: DEFAULT_SILENCE_THRESHOLD
    }

    /**
     * Сколько ждать паузу. Отдельно от порога тишины намеренно: порог — про шум
     * вокруг, терпение — про то, как человек говорит. Одной ручкой это не
     * настраивается.
     */
    val silencePatience: Flow<SilenceWindow.Patience> = context.dataStore.data.map {
        SilenceWindow.Patience.of(it[SILENCE_PATIENCE] ?: SilenceWindow.Patience.NORMAL.wire)
    }

    val windows: Flow<Scheduler.Windows> = context.dataStore.data.map { it.toWindows() }

    suspend fun serverUrlNow(): String = serverUrl.first()

    suspend fun llmEnabledNow(): Boolean = llmEnabled.first()

    suspend fun windowsNow(): Scheduler.Windows = windows.first()

    suspend fun silenceThresholdNow(): Int = silenceThreshold.first()

    suspend fun setServerUrl(value: String) {
        context.dataStore.edit { it[SERVER_URL] = value.trim().trimEnd('/') }
    }

    /**
     * Разовый сброс выключенного разбора (Р-15.5).
     *
     * Тумблер уехал из основных настроек в «Для разработчика». Если он остался
     * выключенным с прошлой версии, человек остался бы без разбора навсегда и
     * без очевидного способа его вернуть — поэтому один раз включаем обратно.
     * Флаг «уже чинили» нужен, чтобы не спорить с осознанным выключением.
     */
    suspend fun healParsingToggle() {
        context.dataStore.edit {
            if (it[PARSE_HEALED] == true) return@edit
            it[PARSE_HEALED] = true
            it[LLM_ENABLED] = true
        }
    }

    suspend fun setLlmEnabled(value: Boolean) {
        context.dataStore.edit { it[LLM_ENABLED] = value }
    }

    suspend fun setSilenceThreshold(value: Int) {
        context.dataStore.edit { it[SILENCE_THRESHOLD] = value }
    }

    suspend fun silencePatienceNow(): SilenceWindow.Patience = silencePatience.first()

    suspend fun setSilencePatience(value: SilenceWindow.Patience) {
        context.dataStore.edit { it[SILENCE_PATIENCE] = value.wire }
    }

    // --- счётчики подсказок жестов (спека R1.1 §8) ---
    // Подсказка гаснет после трёх применений жеста и возвращается, если жестом
    // не пользовались 30 дней.

    suspend fun hintVisible(key: String): Boolean {
        val data = context.dataStore.data.first()
        val uses = data[intPreferencesKey("hint_${key}_uses")] ?: 0
        val last = data[longPreferencesKey("hint_${key}_last")] ?: 0L
        if (uses < 3) return true
        val month = 30L * 24 * 60 * 60 * 1000
        return System.currentTimeMillis() - last > month
    }

    suspend fun hintUsed(key: String) {
        context.dataStore.edit {
            val usesKey = intPreferencesKey("hint_${key}_uses")
            it[usesKey] = (it[usesKey] ?: 0) + 1
            it[longPreferencesKey("hint_${key}_last")] = System.currentTimeMillis()
        }
    }

    // --- починка структуры (Р-15.12) ---
    // Здесь живёт такт: когда продукт в последний раз просил навести порядок и
    // по каким темам ему уже сказали «не надо».

    suspend fun lastStructureOffer(): Long? =
        context.dataStore.data.first()[longPreferencesKey("structure_last")]

    suspend fun structureOffered(at: Long) {
        context.dataStore.edit { it[longPreferencesKey("structure_last")] = at }
    }

    suspend fun structureRefusedAt(key: String): Long? =
        context.dataStore.data.first()[longPreferencesKey("structure_no_$key")]

    suspend fun structureRefused(key: String, at: Long) {
        context.dataStore.edit { it[longPreferencesKey("structure_no_$key")] = at }
    }

    /** Пересчёт людей по прежним записям делается один раз (Д-26). */
    suspend fun peopleBackfilled(): Boolean =
        context.dataStore.data.first()[booleanPreferencesKey("people_backfilled")] ?: false

    suspend fun setPeopleBackfilled() {
        context.dataStore.edit { it[booleanPreferencesKey("people_backfilled")] = true }
    }

    /** «Перед сном» (Р-18.1): когда спрашивать про день. Не вечернее окно — оно для дел. */
    val bedtime: Flow<LocalTime> = context.dataStore.data.map {
        parse(it[BEDTIME], LocalTime.of(21, 30))
    }

    suspend fun bedtimeNow(): LocalTime = bedtime.first()

    suspend fun setBedtime(time: LocalTime) {
        context.dataStore.edit { it[BEDTIME] = time.toString() }
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
        private const val KEY_DEEPSEEK = "deepseek_key"
        const val DEFAULT_SILENCE_THRESHOLD = 900

        private val BEDTIME = stringPreferencesKey("bedtime")
        private val SERVER_URL = stringPreferencesKey("server_url")
        private val LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        private val SILENCE_THRESHOLD = intPreferencesKey("silence_threshold")
        private val SILENCE_PATIENCE = intPreferencesKey("silence_patience")
        private val PARSE_HEALED = booleanPreferencesKey("parse_toggle_healed")
        private val W_MORNING = stringPreferencesKey("window_morning")
        private val W_DAY = stringPreferencesKey("window_day")
        private val W_EVENING = stringPreferencesKey("window_evening")
        private val W_WEEKEND = stringPreferencesKey("window_weekend")
    }
}
