package com.heyheyon.armbandbot

internal const val GLOBAL_SCAN_SCOPE = "GLOBAL"

internal fun resolveScanScopeId(botId: String, independent: Boolean): String {
    if (!independent) return GLOBAL_SCAN_SCOPE
    require(botId.isNotBlank()) { "독립 검사 기록에는 botId가 필요합니다." }
    return botId
}
