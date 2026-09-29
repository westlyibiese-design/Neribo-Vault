package com.westly.neribovault.data.local

import androidx.room.TypeConverter

/** Stores a list of strings as one column, items joined by the control character U+001F. */
class StringListConverter {
    @TypeConverter
    fun fromList(value: List<String>): String = value.joinToString(SEPARATOR)

    @TypeConverter
    fun toList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(SEPARATOR)

    private companion object {
        const val SEPARATOR = "\u001F"
    }
}
