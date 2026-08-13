package ai.prinim.prinyal.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Установка новой сборки самим приложением (dogfood-контур).
 *
 * Зачем вообще: `adb install` на HarmonyOS проходит через диалог подтверждения —
 * каждое обновление требовало тапа по телефону, а обход диалога автотапом ломался
 * от любой смены прошивки. С Android 12 приложение, обновляющее **само себя**,
 * имеет право поставить APK без единого окна: `USER_ACTION_NOT_REQUIRED`. Условия —
 * тот же пакет, та же подпись и однажды выданное «установка из этого источника».
 *
 * Приёмник открыт наружу (иначе `adb shell am broadcast` до него не достучится),
 * поэтому единственный настоящий замок — проверка подписи: ставим только APK,
 * подписанный тем же ключом, что и текущая сборка. Подсунутый в папку чужой файл
 * до установки не доедет.
 */
object SelfUpdater {

    const val ACTION_UPDATE = "ai.prinim.prinyal.action.SELF_UPDATE"
    const val ACTION_STATUS = "ai.prinim.prinyal.action.SELF_UPDATE_STATUS"

    private const val TAG = "PrinyalUpdate"

    /**
     * Куда класть новую сборку. Внешний files-каталог выбран потому, что в него
     * пишет `adb push` без root и без FileProvider, а чужие приложения — нет.
     */
    fun apkFile(context: Context): File =
        File(context.getExternalFilesDir(null), "update.apk")

    /**
     * @return null, если установка запущена; иначе причина отказа — её же
     *         возвращаем наружу, чтобы deploy-скрипт не гадал по молчанию.
     */
    fun install(context: Context): String? {
        val apk = apkFile(context)
        if (!apk.isFile || apk.length() == 0L) return "no_apk"

        val mismatch = verify(context, apk)
        if (mismatch != null) return mismatch

        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            params.setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Ради этой строки всё и затевалось: обновление себя без окна.
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
                )
            }
            // Забрать владение обновлениями система вправе считать решением
            // человека и показать окно — ровно то, чего мы избегаем. Поэтому
            // просим владение только когда это явно включено флагом, а не всегда.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                flag(context, "ownership")
            ) {
                params.setRequestUpdateOwnership(true)
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(statusSender(context, sessionId))
            }
            Log.i(TAG, "сессия $sessionId зафиксирована, ${apk.length()} байт")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "установка не началась: ${e.message}")
            "error:${e.message}"
        }
    }

    private fun statusSender(context: Context, sessionId: Int) =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(ACTION_STATUS).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender

    /**
     * Переключатель поведения установки без пересборки: `update-flags.txt` рядом с
     * APK, одно слово на строку. Нужен потому, что каждая проверка гипотезы иначе
     * стоит полной пересборки и заливки 393 МБ на телефон.
     */
    private fun flag(context: Context, name: String): Boolean {
        val file = File(context.getExternalFilesDir(null), "update-flags.txt")
        return runCatching {
            file.isFile && file.readLines().any { it.trim() == name }
        }.getOrDefault(false)
    }

    /** @return null если APK свой, иначе код причины. */
    private fun verify(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES

        val incoming = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        }.getOrNull() ?: return "unreadable_apk"

        if (incoming.packageName != context.packageName) {
            return "foreign_package:${incoming.packageName}"
        }

        val mine = runCatching { pm.getPackageInfo(context.packageName, flags) }
            .getOrNull() ?: return "own_signature_unknown"

        return if (fingerprints(incoming) == fingerprints(mine)) null else "signature_mismatch"
    }

    private fun fingerprints(info: android.content.pm.PackageInfo): Set<String> {
        val signers = info.signingInfo?.apkContentsSigners ?: return emptySet()
        val sha = MessageDigest.getInstance("SHA-256")
        return signers.map { sha.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
            .toSet()
    }
}
