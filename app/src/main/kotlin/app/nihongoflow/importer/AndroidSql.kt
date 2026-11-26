package app.nihongoflow.importer

import android.database.sqlite.SQLiteDatabase
import app.nihongoflow.core.anki.SqlDatabase
import app.nihongoflow.core.anki.SqlOpener
import app.nihongoflow.core.anki.SqlRow
import java.io.File

/** Read-only access to an Anki collection copy through the platform SQLite. */
class AndroidSql(file: File) : SqlDatabase {
    private val db = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    override fun <T> query(sql: String, row: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, null).use { c ->
            val r = object : SqlRow {
                override fun long(index: Int) = c.getLong(index)
                override fun string(index: Int): String? = if (c.isNull(index)) null else c.getString(index)
                override fun blob(index: Int): ByteArray? = if (c.isNull(index)) null else c.getBlob(index)
            }
            buildList { while (c.moveToNext()) add(row(r)) }
        }

    override fun close() = db.close()

    companion object {
        val opener = SqlOpener { AndroidSql(it) }
    }
}
