package app.nihongoflow.core.study

/**
 * Media references inside note fields, written as `[sound:file.mp3]` (Anki's own syntax)
 * and `[image:file.jpg]`. The files live in the app's media folder. Until audio and image
 * display arrive, faces show the text without the references.
 */
object MediaRefs {
    private val ref = Regex("""\[(sound|image):([^\]\r\n]+)]""")

    fun sounds(text: String): List<String> = ref.findAll(text).filter { it.groupValues[1] == "sound" }.map { it.groupValues[2].trim() }.toList()

    fun images(text: String): List<String> = ref.findAll(text).filter { it.groupValues[1] == "image" }.map { it.groupValues[2].trim() }.toList()

    fun all(text: String): List<String> = ref.findAll(text).map { it.groupValues[2].trim() }.toList()

    fun withoutRefs(text: String): String =
        ref.replace(text, "").lines().joinToString("\n") { it.trimEnd() }.trim()
}
