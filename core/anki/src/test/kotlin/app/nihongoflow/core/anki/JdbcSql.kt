package app.nihongoflow.core.anki

import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.Connection

/** Test SQL access over sqlite-jdbc. Like Android's SQLite, it has no `unicase` collation. */
class JdbcSql(file: File) : SqlDatabase {
    private val connection: Connection = SQLiteConfig().apply { setReadOnly(true) }
        .createConnection("jdbc:sqlite:${file.absolutePath}")

    override fun <T> query(sql: String, row: (SqlRow) -> T): List<T> =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val wrapped = object : SqlRow {
                    override fun long(index: Int) = rs.getLong(index + 1)
                    override fun string(index: Int): String? = rs.getString(index + 1)
                    override fun blob(index: Int): ByteArray? = rs.getBytes(index + 1)
                }
                buildList { while (rs.next()) add(row(wrapped)) }
            }
        }

    override fun close() = connection.close()

    companion object {
        val opener = SqlOpener { JdbcSql(it) }
    }
}

object Fixtures {
    fun file(name: String): File = File(requireNotNull(Fixtures::class.java.getResource("/anki/$name")) { name }.toURI())
}

