package com.heyheyon.armbandbot

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SnapshotSurvivingReferencesTest {
    private fun withPair(test: (File, File, File) -> Unit) {
        val root = Files.createTempDirectory("snapshot_survivors").toFile()
        try {
            val dir = File(root, "snapshots_original").apply { mkdirs() }
            val initial = File(dir, "gall_10_initial_2.html").apply { writeText("initial") }
            val latest = File(dir, "gall_10_latest_2.html").apply { writeText("latest") }
            test(root, initial, latest)
        } finally { root.deleteRecursively() }
    }

    @Test fun survivingLatestReferenceProtectsEntireInitialLatestPair() = withPair { root, initial, latest ->
        assertEquals(0, deleteSnapshotFiles(initial.path, listOf(root), survivingSnapshotPaths = listOf(latest.path)))
        assertEquals("initial", initial.readText())
        assertEquals("latest", latest.readText())
    }

    @Test fun survivingCanonicalAliasProtectsPair() = withPair { root, initial, latest ->
        val alias = File(initial.parentFile, "../${initial.parentFile.name}/${initial.name}")
        assertEquals(0, deleteSnapshotFiles(latest.path, listOf(root), survivingSnapshotPaths = listOf(alias.path)))
        assertTrue(initial.exists())
        assertTrue(latest.exists())
    }

    @Test fun unreferencedPairIsRemovedWithoutRemovingUnrelatedSnapshot() = withPair { root, initial, latest ->
        val other = File(initial.parentFile, "gall_11_blocked_1.html").apply { writeText("history") }
        assertEquals(2, deleteSnapshotFiles(initial.path, listOf(root), survivingSnapshotPaths = listOf(other.path)))
        assertFalse(initial.exists())
        assertFalse(latest.exists())
        assertEquals("history", other.readText())
    }

    @Test fun missingReferenceInventoryFailsClosed() = withPair { root, initial, latest ->
        assertEquals(0, deleteSnapshotFiles(initial.path, listOf(root), survivingSnapshotPaths = null))
        assertTrue(initial.exists())
        assertTrue(latest.exists())
    }

    @Test fun directoryDeletionPreservesReferencedPairAndIndependentHistory() = withPair { root, initial, latest ->
        val history = File(initial.parentFile, "gall_20_blocked_1.html").apply { writeText("history") }
        val orphan = File(initial.parentFile, "gall_30_blocked_1.html").apply { writeText("orphan") }
        assertEquals(1, deleteTrustedSnapshotDirectoryFiles(root, initial.parentFile,
            survivingSnapshotPaths = listOf(latest.path, history.path)))
        assertTrue(initial.exists())
        assertTrue(latest.exists())
        assertEquals("history", history.readText())
        assertFalse(orphan.exists())
    }

    @Test fun directoryDeletionWithoutInventoryFailsClosed() = withPair { root, initial, latest ->
        assertEquals(0, deleteTrustedSnapshotDirectoryFiles(root, initial.parentFile, survivingSnapshotPaths = null))
        assertTrue(initial.exists())
        assertTrue(latest.exists())
    }
}
