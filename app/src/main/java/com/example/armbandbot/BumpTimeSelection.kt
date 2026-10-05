package com.heyheyon.armbandbot

/** Immutable picker draft. Empty drafts are allowed; BumpRule validates final non-empty saves. */
internal fun updateBumpTimeSelection(minutes: List<Int>, previous: Int?, next: Int?): List<Int> {
    require(minutes.all { it in 0..1439 })
    require(previous == null || previous in minutes) { "수정할 시각을 다시 선택하세요." }
    require(next == null || next in 0..1439) { "시각을 확인하세요." }
    val result = (minutes.filterNot { it == previous } + listOfNotNull(next)).distinct().sorted()
    require(result.size <= 24) { "하루에 최대 24개 시각을 지정할 수 있습니다." }
    return result
}
