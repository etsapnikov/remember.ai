package ai.prinim.prinyal.returns

import ai.prinim.prinyal.PrinyalApp
import ai.prinim.prinyal.domain.StuckPolicy
import ai.prinim.prinyal.data.ItemState
import ai.prinim.prinyal.data.Analytics
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Возврат наступил: показать карточку и записать факт срабатывания. */
class ReturnAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val returnId = intent.getStringExtra(ReturnScheduler.EXTRA_RETURN_ID) ?: return
        val app = PrinyalApp.of(context)
        val pending = goAsync()

        // Первая отметка — до любых проверок: она отвечает на главный вопрос,
        // дошёл ли до нас аларм вообще. Всё остальное уже наши решения.
        ReturnDiag.log(context, returnId, ReturnDiag.ALARM)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entity = app.db.returns().byId(returnId)
                if (entity == null) {
                    ReturnDiag.log(context, returnId, ReturnDiag.SKIPPED, mapOf("why" to "нет записи"))
                    return@launch
                }
                if (entity.firedAt != null) {
                    ReturnDiag.log(context, returnId, ReturnDiag.SKIPPED, mapOf("why" to "уже показан"))
                    return@launch
                }
                val item = app.db.items().byId(entity.itemId)
                if (item == null) {
                    ReturnDiag.log(context, returnId, ReturnDiag.SKIPPED, mapOf("why" to "нет пункта"))
                    return@launch
                }

                // Третий перенос — не напоминание, а выбор (Р-15.8).
                val fork = StuckPolicy.isFork(
                    ItemState.of(item.state),
                    app.db.returns().forItem(item.id),
                )
                Notifications.showReturn(
                    context,
                    returnId,
                    item,
                    entity.attempt,
                    app.settings.windowsNow(),
                    fork = fork,
                )
                ReturnDiag.log(context, returnId, ReturnDiag.SHOWN)
                app.repository.markFired(returnId)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Ответ пользователя из шторки. Свайп (`IGNORED`) — тоже ответ: он назначает один
 * тихий второй заход, после которого пункт уходит в `expired` и больше не пилит.
 */
class ReturnActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val returnId = intent.getStringExtra(ReturnScheduler.EXTRA_RETURN_ID) ?: return
        val action = intent.action ?: return
        val app = PrinyalApp.of(context)
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entity = app.db.returns().byId(returnId) ?: return@launch
                val itemId = entity.itemId
                ReturnDiag.log(context, returnId, ReturnDiag.ANSWERED, mapOf("action" to action))

                when (action) {
                    DONE -> {
                        app.repository.markDone(itemId)
                        app.repository.recordAction(returnId, "done")
                        Notifications.cancel(context, returnId)
                    }
                    LATER -> {
                        val at = app.repository.snooze(itemId)
                        app.repository.recordAction(returnId, "later")
                        if (at != null) Notifications.showLaterConfirmation(context, returnId, at)
                        else Notifications.cancel(context, returnId)
                    }
                    DISMISS -> {
                        app.repository.dismissItem(itemId, fromReturn = true)
                        app.repository.recordAction(returnId, "miss")
                        Notifications.cancel(context, returnId)
                    }
                    // «Уменьшить» и «сказать иначе» открывают карточку: малый шаг
                    // придумывает модель, и человек должен его увидеть до замены.
                    // Молча подменять текст дела продукт не вправе — это его
                    // слова, а не наши (Р-15.8).
                    SHRINK, REPHRASE -> {
                        app.repository.recordAction(returnId, "later")
                        app.analytics.log(
                            Analytics.RETURN_ACTION,
                            mapOf("item" to itemId, "action" to if (action == SHRINK) "shrink" else "rephrase"),
                        )
                        Notifications.cancel(context, returnId)
                    }
                    IGNORED -> {
                        app.repository.recordAction(returnId, "ignored")
                        app.repository.scheduleSecondAttempt(returnId)
                    }
                }

                if (action != IGNORED) {
                    ai.prinim.prinyal.ui.theme.Haptics.returnAction(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val DONE = "ai.prinim.prinyal.RETURN_DONE"
        const val LATER = "ai.prinim.prinyal.RETURN_LATER"
        const val DISMISS = "ai.prinim.prinyal.RETURN_DISMISS"
        /** Развилка застрявшего (Р-15.8). */
        const val SHRINK = "ai.prinim.prinyal.RETURN_SHRINK"
        const val REPHRASE = "ai.prinim.prinyal.RETURN_REPHRASE"
        const val IGNORED = "ai.prinim.prinyal.RETURN_IGNORED"
    }
}

/**
 * Алармы не переживают выключение телефона — после загрузки ставим их заново.
 * Без этого приёмка F-6 («возврат срабатывает после перезагрузки») не проходит.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = PrinyalApp.of(context)
        val pending = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.rescheduleAll()

                // Пропущенные, пока телефон был выключен, не выбрасываем: показываем
                // сразу — лучше поздно, чем молча.
                val now = System.currentTimeMillis()
                app.db.returns().due(now).forEach { entity ->
                    val item = app.db.items().byId(entity.itemId) ?: return@forEach
                    Notifications.showReturn(
                        context, entity.id, item, entity.attempt, app.settings.windowsNow(),
                    )
                    app.repository.markFired(entity.id)
                }

                // Своё событие, не RETURN_FIRED: по нему считается надёжность
                // возвратов §8, и перезагрузки завышали бы знаменатель.
                app.analytics.log(
                    "alarms_rescheduled",
                    mapOf("action" to intent.action),
                )
            } finally {
                pending.finish()
            }
        }
    }
}
