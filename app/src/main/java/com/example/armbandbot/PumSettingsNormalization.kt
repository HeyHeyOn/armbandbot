package com.heyheyon.armbandbot

internal const val PUM_PROCESS_MODE_BLOCK = "BLOCK"
internal const val PUM_PROCESS_MODE_DELETE = "DELETE"
internal const val PUM_PROCESS_MODE_HOLD = "HOLD"
internal const val PUM_DEFAULT_BLOCK_DURATION_HOURS = 6
internal const val PUM_MIN_BLOCK_DURATION_HOURS = 1
internal const val PUM_MAX_BLOCK_DURATION_HOURS = 744

internal data class NormalizedPumSettings(
    val processMode: String,
    val blockDurationHours: Int,
)

/** Pure fail-safe policy shared by persisted settings boundaries and runtime resolution. */
internal fun normalizePumSettings(
    processMode: Any?,
    blockDurationHours: Any?,
    legacyDeleteOnly: Boolean = false,
    processModePresent: Boolean = processMode != null,
): NormalizedPumSettings = NormalizedPumSettings(
    processMode = when (processMode) {
        PUM_PROCESS_MODE_BLOCK,
        PUM_PROCESS_MODE_DELETE,
        PUM_PROCESS_MODE_HOLD,
        -> processMode as String
        else -> if (!processModePresent && legacyDeleteOnly) PUM_PROCESS_MODE_DELETE else PUM_PROCESS_MODE_BLOCK
    },
    blockDurationHours = coercePumDurationHours(blockDurationHours),
)

private fun coercePumDurationHours(value: Any?): Int {
    val number = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    } ?: return PUM_DEFAULT_BLOCK_DURATION_HOURS

    return number.toInt().coerceIn(PUM_MIN_BLOCK_DURATION_HOURS, PUM_MAX_BLOCK_DURATION_HOURS)
}
