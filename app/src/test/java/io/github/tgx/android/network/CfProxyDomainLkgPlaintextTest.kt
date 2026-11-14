package io.github.tgx.android.network

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfProxyDomainLkgPlaintextTest {
    private val seed = listOf("seed-one.example.com", "seed-two.example.com", "seed-three.example.com")

    private fun validLkgDomains() = listOf(
        "lkg-alpha.example.com",
        "lkg-bravo.example.com",
        "lkg-charlie.example.com"
    )

    private fun tempDir(prefix: String): File = File.createTempFile(prefix, null).apply {
        delete()
        mkdirs()
    }.also { it.deleteOnExit() }

    @Test
    fun validPlaintextListPersistsAndRestoresInNewStoreInstance() {
        val dir = tempDir("tgx-lkg-restore")
        val lkgFile = File(dir, "cfproxy_domains.lkg")

        // 1-2) Persist a valid plaintext Flowseal list as LKG.
        CfProxyDomainLkg(lkgFile).persist(validLkgDomains())
        assertTrue(lkgFile.isFile)
        assertEquals("lkg-alpha.example.com\nlkg-bravo.example.com\nlkg-charlie.example.com\n", lkgFile.readText())

        // 3-4) A new store instance (fresh app start) restores it even though
        // the Flowseal source is unavailable.
        val store = CfProxyDomainStore(seed)
        val result = store.restorePersistentLkg(CfProxyDomainLkg(lkgFile))
        assertTrue("valid LKG must be accepted", result is CfProxyDomainLkgReadResult.Valid)
        assertEquals(validLkgDomains(), store.snapshot().domains)
        assertEquals(1L, store.snapshot().generation)

        // A second fresh store restores the same persisted state.
        val second = CfProxyDomainStore(seed).restorePersistentLkg(CfProxyDomainLkg(lkgFile))
        assertTrue(second is CfProxyDomainLkgReadResult.Valid)
        assertEquals(validLkgDomains(), (second as CfProxyDomainLkgReadResult.Valid).domains)
    }

    /**
     * Server-level scenario: the LKG file exists, the server instance is new,
     * and the Flowseal source is unavailable. The restored LKG domains must be
     * the active snapshot and the LKG file must not be modified.
     */
    @Test
    fun flowsealUnavailableFallsBackToRestoredLkg() {
        TgxMtProtoProxyServer.resetCfProxyDomainConfigurationForTests()
        val dir = tempDir("tgx-lkg-server")
        val lkgFile = File(dir, "cfproxy_domains.lkg")
        CfProxyDomainLkg(lkgFile).persist(validLkgDomains())
        val lkgBytesBefore = lkgFile.readBytes()

        val fetchAttempted = CountDownLatch(1)
        val server = TgxMtProtoProxyServer(
            secretHex = "33333333333333333333333333333333",
            manifestStorageDirectory = dir,
            cfDomainFetch = { _, _ ->
                fetchAttempted.countDown()
                throw IOException("Flowseal unavailable (simulated)")
            }
        )

        try {
            assertTrue(server.start("127.0.0.1", 0))
            try {
                assertTrue("refresh must be attempted", fetchAttempted.await(5, TimeUnit.SECONDS))
                // Let the failed refresh path settle.
                Thread.sleep(200)

                val snapshot = TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot()
                assertEquals("restored LKG domains must be active", validLkgDomains(), snapshot.domains)
                assertEquals(1L, snapshot.generation)
                assertArrayEquals("failed refresh must not modify the LKG", lkgBytesBefore, lkgFile.readBytes())
            } finally {
                server.stop()
            }
        } finally {
            TgxMtProtoProxyServer.resetCfProxyDomainConfigurationForTests()
        }
    }

    @Test
    fun corruptedLkgIsIgnoredAndBundledSeedsRemainActive() {
        val dir = tempDir("tgx-lkg-corrupt")
        val lkgFile = File(dir, "cfproxy_domains.lkg")
        lkgFile.writeBytes(byteArrayOf(0, -1, 127.toByte(), 'x'.code.toByte()))

        val store = CfProxyDomainStore(seed)
        val result = store.restorePersistentLkg(CfProxyDomainLkg(lkgFile))
        assertTrue("corrupted LKG must be rejected", result is CfProxyDomainLkgReadResult.Invalid)
        assertEquals(seed, store.snapshot().domains)
        assertEquals(0L, store.snapshot().generation)
    }

    @Test
    fun emptyLkgIsIgnoredAndBundledSeedsRemainActive() {
        val dir = tempDir("tgx-lkg-empty")
        val lkgFile = File(dir, "cfproxy_domains.lkg")
        lkgFile.writeBytes(byteArrayOf())

        val store = CfProxyDomainStore(seed)
        val result = store.restorePersistentLkg(CfProxyDomainLkg(lkgFile))
        assertTrue("empty LKG must be rejected", result is CfProxyDomainLkgReadResult.Invalid)
        assertEquals(seed, store.snapshot().domains)
        assertEquals(0L, store.snapshot().generation)
    }

    @Test
    fun truncatedLkgIsIgnoredAndBundledSeedsRemainActive() {
        val dir = tempDir("tgx-lkg-truncated")
        val lkgFile = File(dir, "cfproxy_domains.lkg")
        CfProxyDomainLkg(lkgFile).persist(validLkgDomains())
        val full = lkgFile.readBytes()
        assertTrue(full.size > 8)
        lkgFile.writeBytes(full.copyOf(full.size / 2))

        val store = CfProxyDomainStore(seed)
        val result = store.restorePersistentLkg(CfProxyDomainLkg(lkgFile))
        assertTrue("truncated LKG must be rejected", result is CfProxyDomainLkgReadResult.Invalid)
        assertEquals(seed, store.snapshot().domains)
        assertEquals(0L, store.snapshot().generation)
    }

    @Test
    fun missingLkgKeepsBundledSeeds() {
        val dir = tempDir("tgx-lkg-missing")
        val store = CfProxyDomainStore(seed)
        val result = store.restorePersistentLkg(CfProxyDomainLkg(File(dir, "cfproxy_domains.lkg")))
        assertTrue(result is CfProxyDomainLkgReadResult.Missing)
        assertEquals(seed, store.snapshot().domains)
        assertEquals(0L, store.snapshot().generation)
    }

    @Test
    fun publishValidatedReplacesLkgAtomically() {
        val dir = tempDir("tgx-lkg-publish")
        val lkgFile = File(dir, "cfproxy_domains.lkg")
        CfProxyDomainLkg(lkgFile).persist(validLkgDomains())

        val next = listOf("next-one.example.com", "next-two.example.com", "next-three.example.com")
        val store = CfProxyDomainStore(seed).also {
            it.restorePersistentLkg(CfProxyDomainLkg(lkgFile))
        }
        val snapshot = store.publishValidated(next)

        assertEquals(next, snapshot.domains)
        assertEquals(next.joinToString("\n") + "\n", lkgFile.readText())
        assertFalse(File(dir, "cfproxy_domains.lkg.tmp").exists())
    }
}
