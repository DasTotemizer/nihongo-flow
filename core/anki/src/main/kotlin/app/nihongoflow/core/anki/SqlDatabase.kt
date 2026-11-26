package app.nihongoflow.core.anki

import java.io.Closeable
import java.io.File

/**
 * The little read-only SQL access the importer needs. Android implements it over the
 * platform SQLite; tests implement it over sqlite-jdbc.
 */
interface SqlDatabase : Closeable {
    fun <T> query(sql: String, row: (SqlRow) -> T): List<T>
}

interface SqlRow {
    fun long(index: Int): Long
    fun string(index: Int): String?
    fun blob(index: Int): ByteArray?
}

fun interface SqlOpener {
    /** Opens [file] read-only. */
    fun open(file: File): SqlDatabase
}
