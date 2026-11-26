package app.nihongoflow.core.anki

/**
 * A minimal protobuf reader, enough for the few messages current Anki stores as blobs
 * (note type kind, template formats, deck kind, media list). Unknown fields are skipped.
 */
internal class Proto(private val bytes: ByteArray) {

    sealed interface Value {
        data class Varint(val value: Long) : Value
        class Bytes(val value: ByteArray) : Value {
            val text: String get() = value.toString(Charsets.UTF_8)
        }
        data object Other : Value
    }

    data class Field(val number: Int, val value: Value)

    fun fields(): List<Field> {
        val out = mutableListOf<Field>()
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= bytes.size) throw AnkiImportException("This file looks damaged.")
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw AnkiImportException("This file looks damaged.")
            }
        }
        while (pos < bytes.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out += Field(number, Value.Varint(varint()))
                1 -> { pos += 8; out += Field(number, Value.Other) }
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || pos + len > bytes.size) throw AnkiImportException("This file looks damaged.")
                    out += Field(number, Value.Bytes(bytes.copyOfRange(pos, pos + len)))
                    pos += len
                }
                5 -> { pos += 4; out += Field(number, Value.Other) }
                else -> throw AnkiImportException("This file looks damaged.")
            }
        }
        return out
    }

    fun varint(number: Int): Long? = fields().firstNotNullOfOrNull { f -> (f.value as? Value.Varint)?.takeIf { f.number == number }?.value }

    fun string(number: Int): String? = fields().firstNotNullOfOrNull { f -> (f.value as? Value.Bytes)?.takeIf { f.number == number }?.text }

    fun messages(number: Int): List<Proto> = fields().filter { it.number == number }.mapNotNull { (it.value as? Value.Bytes)?.let { b -> Proto(b.value) } }

    fun has(number: Int): Boolean = fields().any { it.number == number }
}
