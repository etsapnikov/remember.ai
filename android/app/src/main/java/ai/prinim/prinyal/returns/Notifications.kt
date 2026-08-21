package ai.prinim.prinyal.returns

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.ItemEntity
import ai.prinim.prinyal.domain.Phrases
import ai.prinim.prinyal.domain.Scheduler
import ai.prinim.prinyal.ui.MainActivity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import java.time.Instant

/**
 * Шторка — самая ответственная поверхность: продукт живёт в ней больше, чем в
 * приложении (ТЗ UI §3.5).
 *
 * Правила, зашитые здесь: у возврата всегда видна причина; действий ровно три
 * (ограничение канала Android); тон ровный, без драмы и восклицаний.
 */
object Notifications {

    const val CHANNEL_RETURNS = "returns"
    const val CHANNEL_UNDERSTANDING = "understanding"
    const val CHANNEL_CAPTURE = "capture"

    private const val ID_UNDERSTANDING_BASE = 100_000
    private const val ID_BATCH = 42

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RETURNS,
                context.getString(R.string.channel_returns),
                // Возврат — услуга в нужный момент, а не срочный алерт: важность
                // «default», без вибро поверх фирменного паттерна.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.channel_returns_desc)
                enableVibration(true)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UNDERSTANDING,
                context.getString(R.string.channel_understanding),
                // Разбор приходит сам, пользователь его не ждал — тихо.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_understanding_desc) }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                context.getString(R.string.channel_capture),
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = context.getString(R.string.channel_capture_desc) }
        )
    }

    /** «Доказательство понимания» (F-5): что понял и что с этим будет. */
    fun showUnderstanding(context: Context, noteId: String, items: List<ItemEntity>, degraded: String?) {
        if (items.isEmpty()) return

        val lines = items.map { item ->
            val plan = Phrases.plan(context, item)
            val doubt = Phrases.uncertainty(context, item)
            if (doubt != null) "${item.text} — $plan · $doubt" else "${item.text} — $plan"
        }

        val style = NotificationCompat.InboxStyle()
        lines.take(5).forEach(style::addLine)
        Phrases.degraded(context, degraded)?.let(style::setSummaryText)

        val notification = base(context, CHANNEL_UNDERSTANDING)
            .setContentTitle(context.getString(R.string.understanding_title))
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(openNote(context, noteId))
            .setAutoCancel(true)
            .build()

        notify(context, ID_UNDERSTANDING_BASE + noteId.hashCode().and(0xFFFF), notification)
    }

    /**
     * Пачка после оффлайна: одна сводная, не по штуке — по штуке раздражает после
     * утра без сети (PRD §11, п. 4).
     */
    fun showBatch(context: Context, count: Int, at: Instant = Instant.now()) {
        if (count <= 0) return
        val text = context.resources.getQuantityString(R.plurals.notes_parsed, count, count)
        val notification = base(context, CHANNEL_UNDERSTANDING)
            .setContentTitle(text)
            .setContentText(Phrases.partOfDay(context, at))
            .setContentIntent(openFeed(context))
            .setAutoCancel(true)
            .build()
        notify(context, ID_BATCH, notification)
    }

    /**
     * Контекст-пак собран (Р-15.13).
     *
     * Уведомление, а не молчание: команду человек сказал голосом и экрана перед
     * собой не держит. Тап открывает системное «Поделиться» — файл нужен не в
     * приложении, а снаружи, ради этого пак и собирался.
     */
    fun showPack(context: Context, topic: String, file: java.io.File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.files", file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, file.name.hashCode(), chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = base(context, CHANNEL_UNDERSTANDING)
            .setContentTitle(context.getString(R.string.pack_ready, topic))
            .setContentText(context.getString(R.string.pack_share))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notify(context, ID_PACK, notification)
    }

    private const val ID_PACK = 7714

    /**
     * Команда услышана, пак ждёт выбора (Д-28).
     *
     * Уведомление, а не готовый файл: человек говорил голосом и экрана перед
     * собой не держит, но и отдавать наружу непросмотренное продукт не вправе.
     * Тап открывает выбор записей.
     */
    fun showPackPick(context: Context, topic: String) {
        // Явный интент в MainActivity — тот же способ, что у открытия
        // карточки: экран не экспортирован, и схема живёт только внутри.
        val open = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("prinyal://pack/${Uri.encode(topic)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_PACK_TOPIC, topic)
        }
        val pending = PendingIntent.getActivity(
            context, topic.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = base(context, CHANNEL_UNDERSTANDING)
            .setContentTitle(context.getString(R.string.pack_ready, topic))
            .setContentText(context.getString(R.string.pack_pick_open))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notify(context, ID_PACK, notification)
    }

    /** Пункт переписан (Р-15.8): человек должен увидеть, во что превратилось дело. */
    fun showReworded(context: Context, noteId: String, text: String) {
        val notification = base(context, CHANNEL_UNDERSTANDING)
            .setContentTitle(context.getString(R.string.fork_reworded))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openNote(context, noteId))
            .setAutoCancel(true)
            .build()
        notify(context, ID_REWORD, notification)
    }

    /**
     * Уменьшить не вышло. Сообщаем прямо: молчание после нажатия неотличимо от
     * поломки, а выдуманный «малый шаг» хуже отсутствия шага.
     */
    fun showReworkFailed(context: Context, itemId: String) {
        val notification = base(context, CHANNEL_UNDERSTANDING)
            .setContentTitle(context.getString(R.string.fork_failed))
            .setAutoCancel(true)
            .build()
        notify(context, ID_REWORD + itemId.hashCode() % 7, notification)
    }

    private const val ID_REWORD = 7715

    /** Возврат (F-6): текст айтема, причина и три действия. */
    fun showReturn(
        context: Context,
        returnId: String,
        item: ItemEntity,
        attempt: Int,
        windows: Scheduler.Windows,
        /** Третий перенос: вместо напоминания приходит выбор (Р-15.8). */
        fork: Boolean = false,
    ) {
        if (fork) {
            showFork(context, returnId, item)
            return
        }
        val reason = Phrases.reason(context, item, attempt, Instant.now(), windows)

        val notification = base(context, CHANNEL_RETURNS)
            .setContentTitle(item.text)
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.text).setSummaryText(reason))
            .setContentIntent(openNote(context, item.noteId))
            .addAction(0, context.getString(R.string.action_done), action(context, returnId, ReturnActionReceiver.DONE))
            .addAction(0, context.getString(R.string.action_later), action(context, returnId, ReturnActionReceiver.LATER))
            .addAction(0, context.getString(R.string.action_dismiss), action(context, returnId, ReturnActionReceiver.DISMISS))
            // Свайп — это тоже ответ «сейчас не до тебя»: ловим его, чтобы назначить
            // второй заход, а не потерять пункт молча.
            .setDeleteIntent(action(context, returnId, ReturnActionReceiver.IGNORED))
            .setAutoCancel(true)
            .build()

        notify(context, returnId.hashCode(), notification)
    }

    /**
     * Развилка застрявшего пункта (Р-15.8).
     *
     * Третий перенос — сигнал, что дело в нынешнем виде не делается. Четвёртое
     * одинаковое напоминание ничего не изменит, оно только научит человека
     * отмахиваться. Поэтому здесь не напоминание, а выбор — и все три варианта
     * действия, без диагнозов: продукт видит перенос, а не причину.
     *
     * Три действия помещаются в системное уведомление Android — проверено на
     * той же раскладке, что и обычный возврат.
     */
    private fun showFork(context: Context, returnId: String, item: ItemEntity) {
        val notification = base(context, CHANNEL_RETURNS)
            .setContentTitle(item.text)
            .setContentText(context.getString(R.string.fork_reason))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(item.text)
                    .setSummaryText(context.getString(R.string.fork_reason))
            )
            .setContentIntent(openNote(context, item.noteId))
            .addAction(0, context.getString(R.string.fork_shrink), action(context, returnId, ReturnActionReceiver.SHRINK))
            .addAction(0, context.getString(R.string.fork_rephrase), action(context, returnId, ReturnActionReceiver.REPHRASE))
            .addAction(0, context.getString(R.string.action_dismiss), action(context, returnId, ReturnActionReceiver.DISMISS))
            .setDeleteIntent(action(context, returnId, ReturnActionReceiver.IGNORED))
            .setAutoCancel(true)
            .build()

        notify(context, returnId.hashCode(), notification)
    }

    /** Короткое подтверждение «позже» — тем же уведомлением, без нового шума. */
    fun showLaterConfirmation(context: Context, returnId: String, at: Instant) {
        val notification = base(context, CHANNEL_RETURNS)
            .setContentTitle(Phrases.laterConfirmation(context, at))
            .setTimeoutAfter(6_000)
            .setAutoCancel(true)
            .build()
        notify(context, returnId.hashCode(), notification)
    }

    fun cancel(context: Context, returnId: String) {
        NotificationManagerCompat.from(context).cancel(returnId.hashCode())
    }

    /**
     * Значок уведомления картинкой, а не ссылкой на ресурс.
     *
     * `setSmallIcon(R.drawable…)` уходит наружу парой «имя пакета + id», и
     * получатель должен сам достать картинку из ресурсов приложения. Телефон
     * может, часы — нет: приложения на них не стоит, и вместо нашего знака они
     * рисуют серую заглушку. Растеризуем вектор здесь и отдаём пиксели —
     * тогда значок доезжает куда угодно.
     *
     * Считается один раз: рисунок один и тот же на все уведомления.
     */
    @Volatile
    private var iconCache: IconCompat? = null

    private fun icon(context: Context): IconCompat = iconCache ?: synchronized(this) {
        iconCache ?: run {
            val size = context.resources.getDimensionPixelSize(
                android.R.dimen.notification_large_icon_width
            ).coerceIn(48, 192)
            val drawable = ContextCompat.getDrawable(context, R.drawable.ic_stat_note)
            val bitmap = createBitmap(size, size)
            drawable?.apply {
                setBounds(0, 0, size, size)
                // Белым с альфой: систему интересует только альфа-канал, она
                // сама красит значок под свою шторку.
                setTint(android.graphics.Color.WHITE)
                draw(android.graphics.Canvas(bitmap))
            }
            IconCompat.createWithBitmap(bitmap).also { iconCache = it }
        }
    }

    private fun base(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            // Вектор с альфа-маской (Р-3): PNG без альфы был невидим в статус-баре.
            .setSmallIcon(icon(context))
            // Акцент «это он мне принёс»: по цвету нашу карточку находят в чужой
            // шторке за пару секунд (ТЗ айдентики §9.2).
            .setColor(ContextCompat.getColor(context, R.color.accent_self))
            .setColorized(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)

    private fun action(context: Context, returnId: String, action: String): PendingIntent {
        val intent = Intent(context, ReturnActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("prinyal://return/$returnId/$action")
            putExtra(ReturnScheduler.EXTRA_RETURN_ID, returnId)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openNote(context: Context, noteId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("prinyal://note/$noteId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openFeed(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
