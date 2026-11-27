package app.nihongoflow

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

/**
 * Keeps the details of the last crash on this device, so the learner can choose to send
 * them. Nothing is sent automatically, and the file never leaves the phone otherwise.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(report(app, error, thread.name)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun report(context: Context, error: Throwable, thread: String = Thread.currentThread().name): String = buildString {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("Nihongo Flow $version")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Thread: $thread")
        append(error.stackTraceToString().take(30_000))
    }

    fun last(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Nihongo Flow problem details")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Send the details").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
