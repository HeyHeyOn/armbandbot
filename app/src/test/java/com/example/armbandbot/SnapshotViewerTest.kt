package com.heyheyon.armbandbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SnapshotViewerTest {
    @Test
    fun parseSnapshotKeepsBodyAndCommentDcconsAsImageUrls() {
        val html = """
            <html><body>
                <div class="title_subject">제목</div>
                <div class="gall_writer" data-nick="작성자" data-uid="user1"></div>
                <span class="gall_date" title="2026.06.28 12:00:00"></span>
                <span class="gall_count">조회 1</span>
                <div class="write_div">
                    <p>본문 앞</p>
                    <p><img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=BODYTOKEN" alt="본문콘"><img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=BODYTOKEN" alt="본문콘"></p>
                    <p>본문 뒤</p>
                </div>
                <ul class="cmt_list">
                    <li id="comment_li_1" class="ub-content">
                        <div class="cmt_info">
                            <span class="gall_writer" data-nick="댓글러" data-uid="u2"></span>
                            <span class="date_time">12:01</span>
                            <p class="usertxt">댓글</p>
                            <img class="written_dccon" src="https://dcimg5.dcinside.com/dccon.php?no=COMMENTTOKEN" alt="댓글콘">
                        </div>
                    </li>
                </ul>
            </body></html>
        """.trimIndent()
        val file = File.createTempFile("snapshot_dccon", ".html").apply { writeText(html) }

        val parsed = parseSnapshot(file.absolutePath)

        val bodyDcconRow = parsed.bodyElements.filterIsInstance<BodyElement.DcconRowElement>().single()
        assertEquals(2, bodyDcconRow.urls.size)
        assertTrue(bodyDcconRow.urls.all { it == "https://dcimg5.dcinside.com/dccon.php?no=BODYTOKEN" })
        assertEquals(listOf("https://dcimg5.dcinside.com/dccon.php?no=COMMENTTOKEN"), parsed.comments.single().dcconUrls)
    }
}
