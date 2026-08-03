package ai.prinim.prinyal.returns

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.Instant

/**
 * Постановка возвратов в AlarmManager (PRD §F-6).
 *
 * Уведомления локальные: push-инфраструктуры нет, бэкенд может лежать — возвраты
 * всё равно придут (PRD §2, п. 1).
 *
 * Отдельно логируем план и факт: расхождение — это данные о прошивке (§9, риск 1),
 * а не только баг. Без этого лога легко списать молчание на идею продукта.
 */
// open — чтобы тесты подменяли только AlarmManager, оставляя остальной путь настоящим.
open class ReturnScheduler(private val context: Context) {

    private val alarms = context.getSystemService(AlarmManager::class.java)

    open fun schedule(returnId: String, at: Instant) {
        val intent = intentFor(returnId)
        val triggerAt = at.toEpochMilli()

        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        } else {
            // Разрешения на точные алармы нет — окно всё равно приедет, но с люфтом.
            // Это ровно тот случай, когда метрика «в пределах 2 минут» просядет не
            // по вине продукта; поэтому пишем в лог явно.
            Log.w(TAG, "нет разрешения на точные алармы: $returnId уйдёт с люфтом")
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, intent)
        }
        Log.i(TAG, "план: $returnId на $at")
    }

    open fun cancel(returnId: String) {
        alarms.cancel(intentFor(returnId))
    }

    open fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarms.canScheduleExactAlarms() else true

    private fun intentFor(returnId: String): PendingIntent {
        val intent = Intent(context, ReturnAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            // Data делает PendingIntent уникальным: без неё вторая постановка
            // затирает первую, и половина возвратов молча исчезает.
            data = android.net.Uri.parse("prinyal://return/$returnId")
            putExtra(EXTRA_RETURN_ID, returnId)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_FIRE = "ai.prinim.prinyal.RETURN_FIRE"
        const val EXTRA_RETURN_ID = "return_id"
        private const val TAG = "PrinyalReturns"
    }
}
