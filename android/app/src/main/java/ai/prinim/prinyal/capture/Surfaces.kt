package ai.prinim.prinyal.capture

import ai.prinim.prinyal.R
import ai.prinim.prinyal.data.CaptureSource
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/**
 * Системные поверхности, ведущие в тот же жест: виджет 1×1 и плитка Quick Settings.
 *
 * Обе ничего не показывают и ничего не спрашивают — они просто кнопка. Виджет 2×2
 * с ближайшим возвратом (ТЗ UI §3.10) — за рамками R1.
 */

fun captureIntent(context: Context, source: CaptureSource): Intent =
    Intent(context, CaptureActivity::class.java).apply {
        // Уникальная data не даёт системе переиспользовать PendingIntent так, что
        // источник жеста теряется, — а он нужен аналитике F-9.
        data = Uri.parse("prinyal://capture/${source.wire}")
        putExtra(CaptureActivity.EXTRA_SOURCE, source.wire)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

class CaptureWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_capture).apply {
                // Подкрепление ловится там, где рука уже тянется записывать
                // (макет 13d), — строкой под клавишей, а не второй плиткой:
                // вторая плитка того же продукта воюет с самим жестом за место.
                //
                // Рост — новость, падение — молчание: «меньше, чем на прошлой»
                // не печатается никогда. Ни нуля, ни прочерка, ни процентов.
                setTextViewText(R.id.widget_note, weekLine(context))
                setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        context,
                        0,
                        captureIntent(context, CaptureSource.WIDGET),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            manager.updateAppWidget(id, views)
        }
    }

    private fun weekLine(context: Context): String {
        // Читаем синхронно и мимо Room-Flow: onUpdate живёт миллисекунды, а
        // цифра обновляется раз в сутки — гонять корутину незачем.
        val prefs = context.getSharedPreferences(WIDGET_PREFS, Context.MODE_PRIVATE)
        val closed = prefs.getInt(KEY_CLOSED_WEEK, 0)
        // От двух: «закрыто 1» — не новость, а напоминание, что закрыл всего одно.
        return if (closed >= 2) context.getString(R.string.widget_week_closed, closed) else ""
    }

    companion object {
        const val WIDGET_PREFS = "widget"
        const val KEY_CLOSED_WEEK = "closed_week"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CaptureWidget::class.java))
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(context, CaptureWidget::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                )
            }
        }
    }
}

class CaptureTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = captureIntent(this, CaptureSource.TILE)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Шторка обязана закрыться сама: между тапом и записью не должно быть
        // ни одного лишнего кадра.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
