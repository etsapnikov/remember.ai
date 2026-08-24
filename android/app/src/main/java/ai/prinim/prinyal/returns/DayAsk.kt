package ai.prinim.prinyal.returns

import ai.prinim.prinyal.PrinyalApp
import ai.prinim.prinyal.R
import ai.prinim.prinyal.capture.CaptureActivity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Вечерний вопрос и итог недели (Р-18.1, Р-18.3, макеты 12b/12e).
 *
 * Два аларма в одном месте, потому что они близнецы: оба ежедневно/еженедельно
 * переставляют сами себя после срабатывания, оба переживают перезагрузку через
 * общий пересбор алармов, оба молчат при пропуске — второго раза не бывает.
 */
object DayAsk {

    /**
     * Поставить оба аларма на ближайшие моменты. Зовётся при старте приложения,
     * после перезагрузки и после смены времени «Перед сном» — постановка
     * идемпотентна: intent с тем же requestCode заменяет прежний.
     */
    fun schedule(context: Context, bedtime: LocalTime, zone: ZoneId = ZoneId.systemDefault()) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now(zone)

        val ask = now.toLocalDate().atTime(bedtime)
            .let { if (it.isAfter(now)) it else it.plusDays(1) }
        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            ask.atZone(zone).toInstant().toEpochMilli(),
            intent(context, ACTION_ASK),
        )

        // Итог — воскресенье, через полчаса после вопроса: последнее
        // впечатление недели должно попасть внутрь итога (решение дизайнера).
        var recapDay = now.toLocalDate()
        while (recapDay.dayOfWeek != DayOfWeek.SUNDAY) recapDay = recapDay.plusDays(1)
        var recap = recapDay.atTime(bedtime.plusMinutes(30))
        if (!recap.isAfter(now)) recap = recap.plusDays(7)
        alarms.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            recap.atZone(zone).toInstant().toEpochMilli(),
            intent(context, ACTION_RECAP),
        )
    }

    private fun intent(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            if (action == ACTION_ASK) RC_ASK else RC_RECAP,
            Intent(context, DayAskReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.channel_day),
                // Вопрос приходит в назначенное человеком время — обычная
                // важность, без звука-сирены.
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    /** Уведомление-вопрос: текст — сам вопрос, без обёртки и имени фичи (12b). */
    fun showAsk(context: Context) {
        val open = PendingIntent.getActivity(
            context,
            RC_ASK,
            Intent(context, CaptureActivity::class.java).apply {
                putExtra(CaptureActivity.EXTRA_DAY, LocalDate.now().toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(context.getString(R.string.day_question))
            .setContentIntent(open)
            .setAutoCancel(true)
            // До утра вопрос теряет смысл — сам уходит из шторки.
            .setTimeoutAfter(6 * 60 * 60 * 1000L)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_ASK, notification) }
    }

    fun showRecapReady(context: Context) {
        val open = PendingIntent.getActivity(
            context,
            RC_RECAP,
            Intent(context, ai.prinim.prinyal.ui.MainActivity::class.java).apply {
                putExtra(ai.prinim.prinyal.ui.MainActivity.EXTRA_OPEN_WEEKLY, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(context.getString(R.string.week_recap_ready))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID_RECAP, notification) }
    }

    const val ACTION_ASK = "ai.prinim.prinyal.DAY_ASK"
    const val ACTION_RECAP = "ai.prinim.prinyal.WEEK_RECAP"
    const val CHANNEL = "day"
    private const val RC_ASK = 3001
    private const val RC_RECAP = 3002
    private const val ID_ASK = 300_001
    private const val ID_RECAP = 300_002
}
