package app.nihongoflow.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** Where imported audio and pictures are kept. Names come from the cards' media references. */
object MediaFiles {
    fun dir(context: Context) = File(context.filesDir, "media")

    /** The file for [name], or null if it's missing or the name tries to leave the folder. */
    fun file(context: Context, name: String): File? {
        if (name.isBlank() || '/' in name || '\\' in name || name == "." || name == "..") return null
        return File(dir(context), name).takeIf { it.isFile }
    }

    /** Decodes a picture scaled down to about [maxSide] pixels, so large images can't run out of memory. */
    fun picture(file: File, maxSide: Int = 1200): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

/** Plays a card's sounds one after another. A new [play] or [stop] cuts off what's playing. */
class SoundPlayer(private val context: Context) {
    private var player: MediaPlayer? = null
    private val queue = ArrayDeque<File>()

    fun play(names: List<String>) {
        stop()
        names.mapNotNullTo(queue) { MediaFiles.file(context, it) }
        next()
    }

    fun stop() {
        queue.clear()
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
    }

    private fun next() {
        val file = queue.removeFirstOrNull() ?: return
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(file.path)
            p.setOnCompletionListener { done -> finished(done) }
            p.setOnErrorListener { failed, _, _ -> finished(failed); true }
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            p.release() // an unreadable file is skipped, not fatal
            next()
        }
    }

    private fun finished(p: MediaPlayer) {
        p.release()
        if (player === p) player = null
        next()
    }
}
