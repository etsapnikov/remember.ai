package ai.prinim.prinyal.returns

import ai.prinim.prinyal.PrinyalApp
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Страховка возвратов (Р-8, риск PRD §9 подтверждён на Honor Magic6 Pro):
 * за первые дни прогона ни один аларм не дозвонился — прошивка душит ресиверы.
 *
 * Раз в 15 минут воркер выбирает возвраты, чей срок прошёл, а `fired_at` пуст, и
 * показывает их. Петля с люфтом до 15 минут хуже точной, но несравнимо лучше
 * молчащей; `drift_ms` в аналитике честно покажет, сколько возвратов доставила
 * страховка, а сколько — алармы (§8: цель ≤ 2 мин остаётся за алармами).
 *
 * WorkManager прошивки давят реже, чем AlarmManager-ресиверы: он маскируется под
 * системный JobScheduler.
 */
class ReturnCatchUpWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = PrinyalApp.of(applicationContext)
        val now = System.currentTimeMillis()

        // Отметка о самом заходе, без привязки к возврату: она отвечает на
        // вопрос, жив ли страховочный воркер вообще — прошивка душит и его.
        ReturnDiag.log(applicationContext, "—", ReturnDiag.CATCHUP, mapOf("why" to "заход"))

        app.db.returns().due(now).forEach { entity ->
            val item = app.db.items().byId(entity.itemId) ?: return@forEach
            Notifications.showReturn(
                applicationContext,
                entity.id,
                item,
                entity.attempt,
                app.settings.windowsNow(),
            )
            ReturnDiag.log(
                applicationContext, entity.id, ReturnDiag.SHOWN, mapOf("catchup" to true),
            )
            app.repository.markFired(entity.id)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "prinyal_return_catchup"

        /** Идемпотентно: KEEP не плодит дублей при каждом запуске приложения. */
        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReturnCatchUpWorker>(
                15, TimeUnit.MINUTES,
            ).build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}
