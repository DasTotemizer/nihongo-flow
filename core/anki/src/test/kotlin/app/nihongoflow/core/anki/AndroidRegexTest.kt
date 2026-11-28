package app.nihongoflow.core.anki

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Android compiles regular expressions with ICU, which is stricter than the JVM: a bare
 * `{` or `}` that isn't a `{n,m}` quantifier is a syntax error there (and crashes the app
 * the first time the pattern's object loads), while the JVM accepts it. JVM tests can't
 * catch that, so this checks every regex literal in the app's source instead.
 */
class AndroidRegexTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val raw = Regex("Regex\\(\"\"\"(.*?)\"\"\"")
    private val quoted = Regex("Regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val quantifier = Regex("\\{\\d+(,\\d*)?\\}")

    @Test
    fun `every regex in the app has escaped braces`() {
        val sources = listOf("core", "app").map { File(root, it) }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" && "${File.separator}main${File.separator}" in it.path }.toList()
        }
        assertTrue(sources.size > 10, "found the source files")
        val problems = sources.flatMap { file ->
            file.readLines().flatMapIndexed { i, line ->
                val patterns = raw.findAll(line).map { it.groupValues[1] } +
                    quoted.findAll(line).map { it.groupValues[1].replace("\\\\", "\\") }
                patterns.filter { !icuSafe(it) }.map { "${file.relativeTo(root)}:${i + 1}  $it" }.toList()
            }
        }
        assertTrue(problems.isEmpty(), "Escape { and } in these patterns (Android rejects them):\n" + problems.joinToString("\n"))
    }

    private fun icuSafe(p: String): Boolean {
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> {
                    val q = quantifier.matchAt(p, i) ?: return false
                    i = q.range.last
                }
                c == '}' -> return false
            }
            i++
        }
        return true
    }
}
