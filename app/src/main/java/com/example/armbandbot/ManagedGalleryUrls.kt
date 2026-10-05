package com.heyheyon.armbandbot

import java.net.URI
import java.net.URLDecoder

/** The same allowlisted URL interpretation is used by scanning and management automation. */
internal fun parseManagedGalleryUrl(raw: String): Pair<String,String>? = runCatching {
    val u = URI(raw.substringBefore('#').trim())
    require(u.scheme.equals("http",true) || u.scheme.equals("https",true))
    require(u.host?.lowercase() in setOf("gall.dcinside.com","m.dcinside.com") && u.userInfo == null && u.port == -1)
    val query = u.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
        val pair = it.split('=',limit=2)
        URLDecoder.decode(pair[0],"UTF-8") to URLDecoder.decode(pair.getOrElse(1){""},"UTF-8")
    }
    val ids = query.filter { it.first.equals("id",true) }
    require(ids.size <= 1)
    require(!u.rawPath.contains('%')) // Do not reinterpret encoded separators as another gallery.
    val segments = u.path.trim('/').split('/').filter { it.isNotEmpty() }
    require(segments.isNotEmpty())
    val mobile = u.host.equals("m.dcinside.com",true)
    val type = if(segments.first()=="mini") "MI" else "M"
    val prefix = if(segments.first() in setOf("mini","mgallery")) segments.drop(1) else segments
    val pathId = when {
        !mobile && prefix.size == 1 && prefix.first() != "board" -> prefix.first()
        mobile && prefix.firstOrNull() == "board" -> prefix.getOrNull(1)
        mobile && segments.first() in setOf("mini","mgallery") && prefix.firstOrNull() != "board" -> prefix.firstOrNull()
        !mobile && prefix.take(2) == listOf("board","lists") -> prefix.getOrNull(2)
        else -> null
    }
    val queryId = ids.singleOrNull()?.second
    require(pathId == null || queryId == null || pathId == queryId)
    val id = queryId ?: pathId ?: error("갤러리 ID 누락")
    require(id.matches(Regex("[A-Za-z0-9_-]+")) && id !in setOf("board","lists","view"))
    // Query forms must be gallery list/detail paths, not an arbitrary first-party endpoint.
    if(queryId != null && pathId == null) require(prefix in listOf(listOf("board","lists"),listOf("board","view")))
    type to id
}.getOrNull()
