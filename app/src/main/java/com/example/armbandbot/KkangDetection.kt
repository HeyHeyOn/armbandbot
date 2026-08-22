package com.heyheyon.armbandbot

internal fun parseGallogCounts(raw: String): Pair<Int, Int>? {
    val fields = raw.trim().split(',').map(String::trim)
    val numericFields = if (fields.lastOrNull().isNullOrEmpty()) fields.dropLast(1) else fields
    if (numericFields.size < 2) return null
    val values = numericFields.map { field ->
        if (field.isEmpty() || field.any { it !in '0'..'9' }) return null
        field.toIntOrNull() ?: return null
    }
    return values[0] to values[1]
}
