package dev.shipyu.mindverse.core.data.db

import androidx.room.TypeConverter

/**
 * String-list converters. Pipeline-extracted tags and capture references are short tokens;
 * values are newline-joined with `\\` and `\n` escaping. Deliberately no JSON dependency
 * for two small fields (keeps the storage core dependency-lean for the fdroid flavor).
 */
internal class ListConverters {
    @TypeConverter
    fun fromStringList(values: List<String>): String {
        return values.joinToString("\n") { value ->
            value.replace("\\", "\\\\").replace("\n", "\\n")
        }
    }

    @TypeConverter
    fun toStringList(encoded: String): List<String> {
        if (encoded.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var index = 0
        while (index < encoded.length) {
            val ch = encoded[index]
            when {
                ch == '\\' && index + 1 < encoded.length -> {
                    sb.append(encoded[index + 1])
                    index += 2
                }
                ch == '\n' -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                    index++
                }
                else -> {
                    sb.append(ch)
                    index++
                }
            }
        }
        out.add(sb.toString())
        return out
    }
}
