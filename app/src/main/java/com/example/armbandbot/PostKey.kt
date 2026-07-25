package com.heyheyon.armbandbot

/** Stable identity for a DCInside post. */
data class PostKey(
    val gallType: String,
    val gallId: String,
    val postNo: String,
) {
    init {
        require(gallType in setOf("G", "M", "MI")) { "Unsupported gallery type" }
        require(gallId.isNotBlank())
        require(postNo.isNotBlank())
    }
}
