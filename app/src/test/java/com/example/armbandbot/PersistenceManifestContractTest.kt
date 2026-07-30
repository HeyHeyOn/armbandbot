package com.heyheyon.armbandbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersistenceManifestContractTest {
    private fun manifest(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("AndroidManifest.xml not found; cwd=${File(".").absolutePath}")
    }

    @Test
    fun botServiceUsesSpecialUseInsteadOfTimeLimitedDataSync() {
        val manifest = manifest()

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifest.contains("android:foregroundServiceType=\"specialUse\""))
        assertFalse(manifest.contains("android:foregroundServiceType=\"dataSync\""))
        assertFalse(manifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
    }

    @Test
    fun specialUseServiceDeclaresItsContinuousModerationPurpose() {
        val manifest = manifest()

        assertTrue(manifest.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
        assertTrue(manifest.contains("사용자가 실행한 커뮤니티 관리 봇의 지속적인 게시글 및 댓글 감시"))
    }
}
