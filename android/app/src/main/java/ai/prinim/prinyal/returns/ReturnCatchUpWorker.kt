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

        // Заодно страхуем вечерний вопрос (Р-22.2).
        //
        // У возвратов страховка была с самого начала — у «Дней» её не было
        // вовсе, и вопрос держался на одном аларме. Аларм переставляется при
        // каждом запуске приложения: три деплоя подряд после 21:30 — и вопрос
        // молча уехал на завтра. На прошивке Honor, которая душит алармы, то же
        // самое случится и без деплоев.
        catchUpDayAsk(app)

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

    /**
     * Спросить про день, если время пришло, а вопроса не было.
     *
     * Правила те же, что у самого вопроса: один раз за вечер, до полуночи, и
     * только если человек ещё не рассказал. После полуночи не спрашиваем —
     * вопрос про сегодня, а сегодня уже кончилось.
     */
    private suspend fun catchUpDayAsk(app: ai.prinim.prinyal.PrinyalApp) {
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.LocalDateTime.now(zone)
        val bedtime = app.settings.bedtimeNow()
        if (now.toLocalTime() < bedtime) return

        val today = now.toLocalDate().toString()
        if (app.db.days().byDate(today) != null) return
        if (app.settings.dayAskedOn() == today) return

        app.settings.setDayAskedOn(today)
        DayAsk.showAsk(applicationContext)
        app.analytics.log("day_asked", mapOf("date" to today, "via" to "страховка"))
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
