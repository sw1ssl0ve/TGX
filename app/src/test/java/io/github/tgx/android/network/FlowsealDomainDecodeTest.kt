package io.github.tgx.android.network

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the Flowseal cfproxy domain decoding step.
 *
 * Flowseal's cfproxy-domains.txt publishes obfuscated entries (for example
 * "virkgj.com"); the real production domain is obtained by decoding them
 * with the same algorithm as proxy/config.py (`_dd`). The refresh pipeline
 * must decode before normalizing/validating, so the snapshot and the LKG
 * only ever contain real ".co.uk" base domains.
 */
class FlowsealDomainDecodeTest {

    /** Current raw (obfuscated) Flowseal cfproxy-domains.txt lines. */
    private val rawFlowsealList = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com",
        "awzwsldi.com",
        "clngqrflngqin.com",
        "tjacxbqtj.com",
        "bxaxtxmrw.com",
        "dmohrsgmohcrwb.com",
        "vwbmtmoi.com",
        "khgrre.com",
        "ulihssf.com",
        "tmhqsdqmfpmk.com",
        "xwuwoqbm.com",
        "orgcnunpj.com",
        "zhkuldz.com",
        "zypoljnslxa.com",
        "efabnxaowuzs.com",
        "zaftuzsftqdq.com"
    )

    /** Real base domains that the current raw list decodes to. */
    private val decodedFlowsealList = listOf(
        "pclead.co.uk",
        "offshor.co.uk",
        "cakeisalie.co.uk",
        "noskomnadzor.co.uk",
        "lovetrue.co.uk",
        "sorokdva.co.uk",
        "pyatdesyatdva.co.uk",
        "kartoshka.co.uk",
        "sorokodin.co.uk",
        "pyatdesyatodin.co.uk",
        "notelega.co.uk",
        "ebally.co.uk",
        "nebally.co.uk",
        "havegreatday.co.uk",
        "pomogite.co.uk",
        "fixtelega.co.uk",
        "sadnews.co.uk",
        "onedaychamp.co.uk",
        "stopblocking.co.uk",
        "nothingthere.co.uk"
    )

    @Before
    fun resetProcessWideCfConfiguration() {
        TgxMtProtoProxyServer.resetCfProxyDomainConfigurationForTests()
    }

    // Required reference decodings.

    @Test
    fun virkgjDecodesToPclead() {
        assertEquals("pclead.co.uk", CfProxyDomainPolicy.decodeFlowsealDomain("virkgj.com"))
    }

    @Test
    fun vmmzovyDecodesToOffshor() {
        assertEquals("offshor.co.uk", CfProxyDomainPolicy.decodeFlowsealDomain("vmmzovy.com"))
    }

    @Test
    fun mkuosckvsoDecodesToCakeisalie() {
        assertEquals("cakeisalie.co.uk", CfProxyDomainPolicy.decodeFlowsealDomain("mkuosckvso.com"))
    }

    // Full current Flowseal list.

    @Test
    fun fullCurrentFlowsealListDecodesToRealDomains() {
        assertEquals(decodedFlowsealList, rawFlowsealList.map(CfProxyDomainPolicy::decodeFlowsealDomain))
    }

    // All decoded values pass CfProxyDomainPolicy.

    @Test
    fun allDecodedValuesPassCfProxyDomainPolicy() {
        val decoded = rawFlowsealList.map(CfProxyDomainPolicy::decodeFlowsealDomain)
        assertTrue(
            "every decoded domain must be a valid base domain",
            decoded.all { CfProxyDomainPolicy.isValidBaseDomain(it) }
        )
        assertEquals(
            "the decoded list must pass whole-list validation",
            decoded,
            CfProxyDomainPolicy.validateNormalized(decoded)
        )
    }

    // Future list updates that already contain decoded entries pass through
    // unchanged, so TGX keeps working if Flowseal stops obfuscating.

    @Test
    fun alreadyDecodedEntriesPassThroughUnchanged() {
        for (domain in decodedFlowsealList) {
            assertEquals(domain, CfProxyDomainPolicy.decodeFlowsealDomain(domain))
        }
    }

    // Malformed encoded input is rejected: the invalid entry must not
    // produce a published snapshot or an LKG file.

    @Test
    fun malformedEncodedEntryIsRejectedAndCurrentDomainsPreserved() {
        val dir = tempDir("tgx-cf-decode-malformed")
        val lkgFile = File(dir, "cfproxy_domains.lkg")
        val fetchStarted = CountDownLatch(1)
        val malformedRaw = rawFlowsealList + "....com"

        val server = TgxMtProtoProxyServer(
            secretHex = "88888888888888888888888888888888",
            manifestStorageDirectory = dir,
            cfDomainFetch = { _, _ ->
                fetchStarted.countDown()
                (malformedRaw.joinToString("\n") + "\n").toByteArray(Charsets.US_ASCII)
            }
        )

        try {
            assertTrue(server.start("127.0.0.1", 0))
            val before = TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot()
            assertTrue("refresh must fetch the Flowseal list", fetchStarted.await(5, TimeUnit.SECONDS))
            assertTrue(
                "a list whose decoded entry is invalid must not publish a new snapshot",
                snapshotUnchangedDuring(before, 2_000)
            )
            assertEquals(0L, TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot().generation)
            assertFalse("rejected refresh must not create the LKG", lkgFile.exists())
        } finally {
            stopQuietly(server)
        }
    }

    // After a successful refresh the snapshot contains the decoded ".co.uk"
    // domains, never the raw ".com" entries, and the LKG persists exactly
    // the decoded domains.

    @Test
    fun refreshPublishesDecodedDomainsToSnapshotAndLkg() {
        val dir = tempDir("tgx-cf-decode-refresh")
        val lkgFile = File(dir, "cfproxy_domains.lkg")
        val fetchStarted = CountDownLatch(1)

        val server = TgxMtProtoProxyServer(
            secretHex = "55555555555555555555555555555555",
            manifestStorageDirectory = dir,
            cfDomainFetch = { _, _ ->
                fetchStarted.countDown()
                (rawFlowsealList.joinToString("\n") + "\n").toByteArray(Charsets.US_ASCII)
            }
        )

        try {
            assertTrue(server.start("127.0.0.1", 0))
            assertTrue("refresh must fetch the Flowseal list", fetchStarted.await(5, TimeUnit.SECONDS))
            assertTrue(
                "decoded domains must reach the snapshot",
                awaitSnapshotDomains(decodedFlowsealList.sorted(), 5_000)
            )

            val snapshot = TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot()
            assertEquals(decodedFlowsealList.sorted(), snapshot.domains)
            assertEquals(1L, snapshot.generation)
            assertTrue("snapshot must contain decoded .co.uk domains", snapshot.domains.all { it.endsWith(".co.uk") })
            assertFalse("snapshot must not contain raw .com entries", snapshot.domains.any { it.endsWith(".com") })

            assertEquals(
                "LKG must persist exactly the decoded domains",
                decodedFlowsealList.sorted().joinToString("\n") + "\n",
                lkgFile.readText()
            )
            assertFalse("LKG must not contain raw .com entries", lkgFile.readText().contains(".com"))
        } finally {
            stopQuietly(server)
        }
    }

    // After a restart (new process, new store) the decoded domains are
    // restored from the LKG even when the Flowseal source is unavailable.

    @Test
    fun restartRestoresDecodedDomainsFromLkg() {
        val dir = tempDir("tgx-cf-decode-restart")
        val lkgFile = File(dir, "cfproxy_domains.lkg")

        // First run: the raw obfuscated list is refreshed and the decoded
        // domains are published and persisted to the LKG.
        val first = TgxMtProtoProxyServer(
            secretHex = "66666666666666666666666666666666",
            manifestStorageDirectory = dir,
            cfDomainFetch = { _, _ ->
                (rawFlowsealList.joinToString("\n") + "\n").toByteArray(Charsets.US_ASCII)
            }
        )
        assertTrue(first.start("127.0.0.1", 0))
        try {
            assertTrue(
                "first run must publish the decoded domains",
                awaitSnapshotDomains(decodedFlowsealList.sorted(), 5_000)
            )
        } finally {
            stopQuietly(first)
        }
        assertTrue(lkgFile.isFile)

        // Simulate a process restart: the process-wide store resets to the
        // bundled seeds, then a new server instance must restore the LKG.
        TgxMtProtoProxyServer.resetCfProxyDomainConfigurationForTests()

        val fetchAttempted = CountDownLatch(1)
        val restarted = TgxMtProtoProxyServer(
            secretHex = "77777777777777777777777777777777",
            manifestStorageDirectory = dir,
            cfDomainFetch = { _, _ ->
                fetchAttempted.countDown()
                throw IOException("Flowseal unavailable (simulated)")
            }
        )
        try {
            assertTrue(restarted.start("127.0.0.1", 0))
            assertTrue("refresh must be attempted", fetchAttempted.await(5, TimeUnit.SECONDS))
            // Let the failed refresh path settle.
            Thread.sleep(200)

            val snapshot = TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot()
            assertEquals(
                "restart must restore the decoded domains from the LKG",
                decodedFlowsealList.sorted(),
                snapshot.domains
            )
            assertEquals(1L, snapshot.generation)
            assertTrue("restored domains must be decoded .co.uk", snapshot.domains.all { it.endsWith(".co.uk") })
            assertFalse("restored domains must not be raw .com", snapshot.domains.any { it.endsWith(".com") })
        } finally {
            stopQuietly(restarted)
        }
    }

    private fun tempDir(prefix: String): File = File.createTempFile(prefix, null).apply {
        delete()
        mkdirs()
    }.also { it.deleteOnExit() }

    private fun stopQuietly(server: TgxMtProtoProxyServer) {
        try { server.stop() } catch (_: RuntimeException) { }
    }

    private fun awaitSnapshotDomains(expected: List<String>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot().domains == expected) return true
            Thread.sleep(50)
        }
        return TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot().domains == expected
    }

    private fun snapshotUnchangedDuring(expected: CfProxyDomainSnapshot, durationMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + durationMs
        while (System.currentTimeMillis() < deadline) {
            if (TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot() != expected) return false
            Thread.sleep(50)
        }
        return true
    }
}
