package com.heyheyon.armbandbot

import org.json.JSONArray

/** A remote source belongs to one existing local list; no list-type columns are needed. */
internal val REMOTE_CHANNEL_KEYS = linkedSetOf("normal", "bypass", "user_blacklist", "user_whitelist", "nickname_blacklist", "nickname_bypass_blacklist", "nickname_whitelist")
internal const val REMOTE_CHANNEL_VERSION_KEY = "remote_channels_version"
internal fun remoteListPrefKey(channel: String, field: String): String {
    require(channel in REMOTE_CHANNEL_KEYS)
    return "remote_channel_${channel}_$field"
}
internal fun remoteListSourceId(url: String, kind: RemoteSourceKind, format: String): String = "${kind.name}|$format|$url"
internal fun remoteChannelTitle(channel: String): String = when(channel) {
    "normal" -> "일반 금지어"; "bypass" -> "우회 금지어"
    "user_blacklist" -> "ID/IP 블랙리스트"; "user_whitelist" -> "ID/IP 화이트리스트"
    "nickname_blacklist" -> "닉네임 블랙리스트"; "nickname_bypass_blacklist" -> "닉네임 우회 블랙리스트"
    "nickname_whitelist" -> "닉네임 화이트리스트"; else -> error("지원하지 않는 목록")
}
internal fun parseSimpleRemoteList(raw: String, kind: RemoteSourceKind): List<String> {
    require(raw.toByteArray(Charsets.UTF_8).size <= REMOTE_MAX_BYTES) { "목록은 256KB 이하만 지원합니다." }
    val text = raw.removePrefix("\uFEFF").trim()
    require(!text.startsWith('<') && !text.startsWith('{')) { "문자열 목록 대신 로그인 또는 오류 응답이 반환되었습니다." }
    val values = if (text.startsWith('[')) {
        val a = runCatching { JSONArray(text) }.getOrElse { throw IllegalArgumentException("문자열 배열 형식을 확인하세요.", it) }
        require(a.length() <= 10000)
        (0 until a.length()).map { require(a.get(it) is String) { "배열에는 문자열만 넣으세요." }; a.getString(it) }
    } else if (kind == RemoteSourceKind.SHEETS) {
        parseRemoteCsv(text).map { row -> require(row.size == 1) { "시트는 한 열에 문자열만 입력하세요. 종류·제목 행은 필요 없습니다." }; row.single() }
    } else text.lineSequence().toList()
    require(values.size <= 10000) { "목록은 10,000개 이하만 지원합니다." }
    return values.map { it.trim().also { value -> require(value.length <= 1000 && !value.contains('\n') && !value.contains('\r') && !value.contains('\u0000')) { "항목은 1,000자 이하의 한 줄 문자열이어야 합니다." } } }.filter { it.isNotEmpty() }.distinct()
}

/** Preserve an existing typed source until its owner explicitly saves a simple replacement. */
internal fun migrateRemoteListSnapshot(values: Map<String, Any>): Map<String, Any> {
    if (values[REMOTE_CHANNEL_VERSION_KEY] == 1) return values
    val result = values.toMutableMap()
    val kind = runCatching { RemoteSourceKind.valueOf(values["remote_lists_kind"] as? String ?: "GITHUB") }.getOrNull()
    val raw = values["remote_lists_url"] as? String ?: ""
    val source = kind?.let { runCatching { resolveRemoteUrl(raw,it) }.getOrNull() }
    val oldCache = if (source != null && values["remote_lists_cache_source"] == source)
        runCatching { parseRemoteLists(values["remote_lists_cache"] as? String ?: "") }.getOrNull() else null
    for (channel in REMOTE_CHANNEL_KEYS) {
        fun k(field:String) = remoteListPrefKey(channel,field)
        val existing = listOf("url","kind","enabled","format").any { result.containsKey(k(it)) }
        val legacy = !existing && channel in REMOTE_LIST_KEYS && raw.isNotBlank()
        if (legacy) {
            result[k("url")] = raw
            result[k("kind")] = kind?.name ?: "GITHUB"
            result[k("format")] = "LEGACY"
            result[k("enabled")] = values[REMOTE_ENABLED_KEY] == true && source != null
            result[k("interval_minutes")] = (values["remote_lists_interval_minutes"] as? Int ?: 15).coerceIn(5,1440)
            oldCache?.let { cache ->
                result[k("cache")] = JSONArray(cache.values[channel] ?: emptyList<String>()).toString()
                result[k("cache_source")] = remoteListSourceId(checkNotNull(source),checkNotNull(kind),"LEGACY")
                result[k("etag")] = values["remote_lists_etag"] as? String ?: ""
                result[k("last_success")] = values["remote_lists_last_success"] as? Long ?: 0L
                result[k("status")] = "이전 목록 연결 유지 · ${cache.values[channel]?.size ?: 0}개"
            }
        }
        result.putIfAbsent(k("enabled"),false)
        result.putIfAbsent(k("url"),"")
        result.putIfAbsent(k("kind"),"GITHUB")
        result.putIfAbsent(k("format"),"SIMPLE")
        result.putIfAbsent(k("interval_minutes"),15)
    }
    result[REMOTE_CHANNEL_VERSION_KEY] = 1
    return result
}

internal val REMOTE_CHANNEL_EXPORT_STRING_KEYS = REMOTE_CHANNEL_KEYS.flatMap { channel -> listOf("url","kind","format").map { remoteListPrefKey(channel,it) } }
internal val REMOTE_CHANNEL_EXPORT_BOOLEAN_KEYS = REMOTE_CHANNEL_KEYS.map { remoteListPrefKey(it,"enabled") }
internal val REMOTE_CHANNEL_EXPORT_INT_KEYS = REMOTE_CHANNEL_KEYS.map { remoteListPrefKey(it,"interval_minutes") }
