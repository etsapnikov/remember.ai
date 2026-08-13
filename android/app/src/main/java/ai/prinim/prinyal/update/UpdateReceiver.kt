package ai.prinim.prinyal.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.File

/**
 * «Положили новую сборку — ставь». Команда приходит бродкастом от deploy-скрипта.
 *
 * Открыт наружу намеренно: `adb shell am broadcast` доставляет только в exported.
 * Опасности в этом нет — [SelfUpdater] ставит лишь APK с нашей подписью.
 */
class UpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SelfUpdater.ACTION_UPDATE) return
        val reason = SelfUpdater.install(context)
        UpdateLog.write(context, if (reason == null) "started" else "rejected $reason")
    }
}

/**
 * Итог установки. Отдельный приёмник, потому что PackageInstaller отвечает
 * асинхронно — commit() возвращается задолго до того, как что-то произошло.
 */
class UpdateStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            // Прошивка требует подтверждения. Окно намеренно НЕ показываем: это
            // фоновое обновление, и выбрасывать человеку диалог поверх его дел —
            // хуже, чем не обновиться. Записываем отказ, решение принимает
            // deploy-скрипт (он умеет поставить обычным путём).
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                UpdateLog.write(context, "pending_user_action")

            PackageInstaller.STATUS_SUCCESS -> UpdateLog.write(context, "success")

            else -> UpdateLog.write(context, "failed $status $message")
        }
        Log.i("PrinyalUpdate", "статус установки: $status $message")
    }
}

/**
 * Состояние обновления в файле рядом с APK.
 *
 * Не аналитика и не logcat: на Honor логи зашифрованы прошивкой, а deploy-скрипту
 * нужен ответ, который можно прочитать снаружи одним `adb shell cat`.
 */
object UpdateLog {
    fun file(context: Context) = File(context.getExternalFilesDir(null), "update-status.txt")

    fun write(context: Context, line: String) {
        runCatching { file(context).writeText(line) }
    }
}
