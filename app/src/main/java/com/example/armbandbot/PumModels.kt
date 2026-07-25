package com.heyheyon.armbandbot

enum class PumDetectionStatus {
    NOT_PUM,
    PUM_CONFIRMED,
    PUM_MARKER_ONLY,
    PUM_LOADER_ONLY,
}

data class PumLoaderRequest(
    val endpoint: String,
    val outerPost: PostKey,
    val formData: Map<String, String>,
)

data class PumDetection(
    val status: PumDetectionStatus,
    val loader: PumLoaderRequest? = null,
) {
    val isPum: Boolean get() = status == PumDetectionStatus.PUM_CONFIRMED || status == PumDetectionStatus.PUM_LOADER_ONLY
    val hasSignalMismatch: Boolean get() = status == PumDetectionStatus.PUM_MARKER_ONLY || status == PumDetectionStatus.PUM_LOADER_ONLY
}

enum class PumCardStatus { RESOLVED, MISSING, INVALID }

data class PumCard(
    val status: PumCardStatus,
    val sourceKey: PostKey? = null,
    val sourceUrl: String? = null,
)

enum class PumSourceStatus { RESOLVED, MISSING, TEMPORARY_FAILURE, INVALID_SOURCE, UNSUPPORTED_SOURCE }

data class PumResolution(
    val status: PumSourceStatus,
    val sourceKey: PostKey? = null,
    val sourceUrl: String? = null,
    val title: String = "",
    val bodyText: String = "",
    val imageAlts: List<String> = emptyList(),
    val sanitizedHtml: String = "",
    val mediaSources: List<String> = emptyList(),
    val contentHash: String? = null,
) {
    /** Sanitized source-body HTML retained for moderation and later snapshot rendering. */
    val rawHtml: String get() = sanitizedHtml
}
