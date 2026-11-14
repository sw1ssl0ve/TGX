package io.github.tgx.android.network

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

internal const val CF_DOMAIN_LIST_MAX_BYTES = 32 * 1024
internal const val CF_DOMAIN_LIST_MAX_LINE_BYTES = 256
internal const val CF_DOMAIN_LIST_MAX_LINES = 100
internal const val CF_DOMAIN_LIST_MIN_DOMAINS = 3
internal const val CF_DOMAIN_LIST_MAX_DOMAINS = 100

internal data class CfProxyDomainSnapshot(
    val generation: Long,
    val domains: List<String>
)

internal sealed interface CfProxyDomainLkgReadResult {
    data object Missing : CfProxyDomainLkgReadResult
    data class Valid(val domains: List<String>) : CfProxyDomainLkgReadResult
    data class Invalid(val reason: String) : CfProxyDomainLkgReadResult
}

/** Atomic plaintext LKG used only after the downloaded Flowseal list passes validation. */
internal class CfProxyDomainLkg(private val file: File) {
    private val temporaryFile = File(file.parentFile, file.name + ".tmp")

    fun read(): CfProxyDomainLkgReadResult {
        if (!file.exists()) return CfProxyDomainLkgReadResult.Missing
        return try {
            if (!file.isFile || file.length() !in 1..CF_DOMAIN_LIST_MAX_BYTES.toLong()) {
                throw IOException("LKG file has invalid length")
            }
            val domains = CfProxyDomainPolicy.parsePlaintext(Files.readAllBytes(file.toPath()))
            CfProxyDomainLkgReadResult.Valid(domains)
        } catch (error: Exception) {
            CfProxyDomainLkgReadResult.Invalid(error.message ?: error.javaClass.simpleName)
        }
    }

    @Throws(IOException::class)
    fun persist(domains: List<String>) {
        val normalized = CfProxyDomainPolicy.validateNormalized(domains)
        val bytes = (normalized.joinToString("\n") + "\n").toByteArray(Charsets.US_ASCII)
        val directory = file.absoluteFile.parentFile ?: throw IOException("LKG has no parent directory")
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create LKG directory")
        }
        try {
            FileOutputStream(temporaryFile, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporaryFile.isFile) temporaryFile.delete()
        }
    }
}

/** Process-wide immutable snapshot: bundled seeds -> restored LKG -> validated refresh. */
internal class CfProxyDomainStore(initialDomains: List<String>) {
    private val publicationLock = Any()
    private val seedDomains = immutable(CfProxyDomainPolicy.validateNormalized(initialDomains, 1, CF_DOMAIN_LIST_MAX_DOMAINS))
    private var persistentLkg: CfProxyDomainLkg? = null
    private val current = AtomicReference(CfProxyDomainSnapshot(0L, seedDomains))

    fun snapshot(): CfProxyDomainSnapshot = current.get()

    fun <T> withCurrentSnapshot(block: (CfProxyDomainSnapshot) -> T): T =
        synchronized(publicationLock) { block(current.get()) }

    fun restorePersistentLkg(lkg: CfProxyDomainLkg): CfProxyDomainLkgReadResult =
        synchronized(publicationLock) {
            persistentLkg = lkg
            val result = lkg.read()
            if (result is CfProxyDomainLkgReadResult.Valid) {
                publishDomainsLocked(result.domains)
            }
            result
        }

    fun publishValidated(domains: List<String>): CfProxyDomainSnapshot =
        synchronized(publicationLock) {
            val normalized = CfProxyDomainPolicy.validateNormalized(domains)
            persistentLkg?.persist(normalized)
            publishDomainsLocked(normalized)
        }

    fun replaceIfValid(domains: List<String>, minimumCount: Int, maximumCount: Int): CfProxyDomainSnapshot? =
        try {
            val normalized = CfProxyDomainPolicy.validateNormalized(domains, minimumCount, maximumCount)
            synchronized(publicationLock) { publishDomainsLocked(normalized) }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun publishDomainsLocked(domains: List<String>): CfProxyDomainSnapshot {
        val previous = current.get()
        if (previous.domains == domains) return previous
        return CfProxyDomainSnapshot(previous.generation + 1L, immutable(domains)).also(current::set)
    }

    private fun immutable(domains: List<String>): List<String> =
        Collections.unmodifiableList(ArrayList(domains))
}

internal object CfProxyDomainPolicy {
    val supportedDcIds: IntRange = 1..203

    fun parsePlaintext(bytes: ByteArray): List<String> {
        require(bytes.isNotEmpty()) { "Domain list is empty" }
        require(bytes.size <= CF_DOMAIN_LIST_MAX_BYTES) { "Domain list exceeds byte limit" }
        require(bytes.none { it == 0.toByte() || (it.toInt() and 0xff) > 0x7f }) { "Domain list must be ASCII" }
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        require(lines.size <= CF_DOMAIN_LIST_MAX_LINES + 1) { "Domain list has too many lines" }
        val domains = lines.map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
        require(domains.all { it.length <= CF_DOMAIN_LIST_MAX_LINE_BYTES && it == it.trim() }) {
            "Domain list contains malformed lines"
        }
        return validateNormalized(domains.map(String::lowercase))
    }

    /**
     * Decodes an obfuscated Flowseal cfproxy domain entry into the real
     * base domain. Fully compatible with `_dd()` in Flowseal/tg-ws-proxy's
     * proxy/config.py: an entry ending in `.com` shifts every letter of its
     * prefix back by the number of letters in that prefix, wrapping within
     * a-z / A-Z, and the `.com` suffix is replaced with `.co.uk`. Entries
     * that do not end in `.com` (for example already-decoded domains from a
     * future Flowseal list update) are returned unchanged.
     *
     * Call with raw lines that already passed the pipeline's ASCII and
     * whitespace checks.
     */
    fun decodeFlowsealDomain(entry: String): String {
        if (!entry.endsWith(".com")) return entry
        val prefix = entry.dropLast(4)
        val shift = prefix.count(Char::isLetter)
        val decoded = StringBuilder(prefix.length)
        for (c in prefix) {
            if (c.isLetter()) {
                val base = if (c > '`') 97 else 65
                decoded.append(((c.code - base - shift).mod(26) + base).toChar())
            } else {
                decoded.append(c)
            }
        }
        return decoded.toString() + ".co.uk"
    }

    fun validateNormalized(
        domains: List<String>,
        minimumCount: Int = CF_DOMAIN_LIST_MIN_DOMAINS,
        maximumCount: Int = CF_DOMAIN_LIST_MAX_DOMAINS
    ): List<String> {
        require(minimumCount >= 1 && maximumCount >= minimumCount)
        require(domains.size in minimumCount..maximumCount) { "Domain count is outside the allowed range" }
        require(domains.distinct().size == domains.size) { "Duplicate domains are not allowed" }
        val normalized = domains.map(String::lowercase)
        require(normalized.all(::isValidBaseDomain)) { "Invalid domain syntax" }
        return normalized
    }

    fun isValidBaseDomain(domain: String): Boolean =
        isCanonicalDnsName(domain) && domain.length + maxRoutePrefixLength(supportedDcIds) <= 253

    fun finalRouteHostname(baseDomain: String, dcId: Int): String? {
        if (dcId !in supportedDcIds || !isCanonicalDnsName(baseDomain)) return null
        return "kws$dcId.$baseDomain".takeIf { it.length <= 253 && isCanonicalDnsName(it) }
    }

    private fun maxRoutePrefixLength(dcIds: IntRange): Int =
        if (dcIds.isEmpty()) Int.MAX_VALUE else dcIds.maxOf { "kws$it.".length }

    private fun isCanonicalDnsName(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253 || domain.endsWith('.') || domain.any { it.code > 0x7f }) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 || label.startsWith("xn--") ||
                    label.first() == '-' || label.last() == '-' ||
                    label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
            }) return false
        val tld = labels.last()
        return tld.length in 2..63 && tld.all { it in 'a'..'z' }
    }
}
