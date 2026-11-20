package com.bighelp.droidhelp.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

/**
 * Deterministic derivation tier used to compute a [HistoryEntryIdentity].
 *
 * - [IMMUTABLE_KEY]: Anchored to an explicit immutable [CanonicalHistoryEntry.canonicalKey] scoped
 *   to [CanonicalHistoryEntry.durableSessionId]. Survives paging, streaming content deltas,
 *   timestamp skew, and repeated identical message content.
 * - [POSITIONED_CONTENT_HASH]: Anchored to [CanonicalHistoryEntry.durableSessionId], global
 *   [CanonicalHistoryEntry.canonicalSequence], and canonical content digest. Survives paging,
 *   page-size/cursor changes, overlapping pages, and repeated identical messages at different turns.
 * - [CONTENT_HASH]: Content-addressed SHA-256 digest of canonical entry fields when neither an
 *   immutable key nor a global canonical sequence is present. Survives paging and streaming
 *   reconciliation regardless of transient transport, timestamp, or paging metadata.
 */
@Serializable
enum class HistoryEntryIdentityScheme {
    IMMUTABLE_KEY,
    POSITIONED_CONTENT_HASH,
    CONTENT_HASH,
}

/**
 * Transient provenance indicating whether an entry originated from canonical paged history or live
 * streaming. Excluded from identity derivation and serialization.
 */
enum class HistoryEntrySource {
    CANONICAL_HISTORY,
    STREAMED,
}

/**
 * Deterministic, stable identifier for a [CanonicalHistoryEntry] that survives paging and
 * streaming reconciliation.
 *
 * [value] is scheme-prefixed (for example `v1:key:<hex>`, `v1:seq:<hex>`, or `v1:hash:<hex>`) so
 * identities across schemes never collide and can be used directly as stable UI or persistence keys.
 */
@Serializable
data class HistoryEntryIdentity(
    val value: String,
    val scheme: HistoryEntryIdentityScheme,
) {
    init {
        ensureIdNotNormalized(value)
    }
}

/**
 * Canonical session history entry carrying durable fields and exposing its deterministic
 * [identity], [stableId], and [contentHash].
 *
 * Volatile transport/paging fields ([source], [runtimeSessionId], [pageCursor], [pageOffset],
 * [isInterim]) are marked [Transient] and excluded from both serialization and identity derivation.
 * [timestampEpochMillis] is also excluded from identity derivation so client-vs-server clock skew
 * never splits a streamed entry from its canonical history counterpart.
 */
@Serializable
data class CanonicalHistoryEntry(
    val durableSessionId: String,
    val role: String,
    val content: String,
    val kind: String = DEFAULT_KIND,
    val canonicalKey: String? = null,
    val canonicalSequence: Long? = null,
    val toolCallId: String? = null,
    val canonicalAttributes: Map<String, String> = emptyMap(),
    val timestampEpochMillis: Long? = null,
    @Transient
    val source: HistoryEntrySource = HistoryEntrySource.CANONICAL_HISTORY,
    @Transient
    val runtimeSessionId: String? = null,
    @Transient
    val pageCursor: String? = null,
    @Transient
    val pageOffset: Int? = null,
    @Transient
    val isInterim: Boolean = false,
) {
    init {
        ensureIdNotNormalized(durableSessionId)
        require(role.isNotBlank()) { "History entry role must not be empty or blank" }
        require(kind.isNotBlank()) { "History entry kind must not be empty or blank" }
        canonicalKey?.let { ensureIdNotNormalized(it) }
        canonicalSequence?.let { checkIntBounds(it, min = 0L, max = Long.MAX_VALUE) }
        toolCallId?.let { ensureIdNotNormalized(it) }
        runtimeSessionId?.let { ensureIdNotNormalized(it) }
        pageOffset?.let { checkIntBounds(it.toLong(), min = 0L, max = Int.MAX_VALUE.toLong()) }
    }

    /** Deterministic, stable identity that survives paging and streaming reconciliation. */
    val identity: HistoryEntryIdentity
        get() = HistoryReader.identityOf(this)

    /** String form of [identity] suitable for stable map, storage, or UI list keys. */
    val stableId: String
        get() = identity.value

    /** Deterministic SHA-256 hex digest of the entry's canonical content fields. */
    val contentHash: String
        get() = HistoryReader.contentHashOf(this)

    companion object {
        /** Default canonical entry kind for conversational messages. */
        const val DEFAULT_KIND: String = "message"
    }
}

/**
 * A single page of canonical history entries returned by a paged history fetch.
 *
 * Paging metadata ([cursor], [nextCursor], [hasMore]) never affects entry identities.
 */
data class HistoryPage(
    val entries: List<CanonicalHistoryEntry>,
    val cursor: String? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean = nextCursor != null,
)

/**
 * Thread-safe reader and reconciler for canonical session history entries.
 *
 * Provides:
 * - Deterministic, collision-resistant stable identities ([identityOf], [contentHashOf]) that are
 *   invariant to paging boundaries, cursors, page offsets, timestamps, volatile runtime session
 *   IDs, and transient streaming flags.
 * - Paged ingestion ([ingestPage]) supporting forward paging, reverse prepending, and overlapping
 *   pages without duplicating entries.
 * - Streaming reconciliation ([reconcileStreamed]) that upgrades streamed entries in place when
 *   authoritative canonical history arrives (including promoting unkeyed provisional streamed
 *   entries by [CanonicalHistoryEntry.contentHash] while preserving alias lookup).
 */
class HistoryReader {

    private val lock = ReentrantReadWriteLock(true)
    private val orderedIdentities = mutableListOf<HistoryEntryIdentity>()
    private val entriesByIdentity = mutableMapOf<HistoryEntryIdentity, CanonicalHistoryEntry>()
    private val identityAliases = mutableMapOf<HistoryEntryIdentity, HistoryEntryIdentity>()
    private val identitiesByValue = mutableMapOf<String, HistoryEntryIdentity>()

    /** Number of distinct reconciled history entries currently held. */
    val size: Int
        get() = lock.readLock().withLock { orderedIdentities.size }

    /**
     * Ingests a [HistoryPage] of canonical entries into the reader, deduplicating by stable
     * identity and upgrading any matching streamed entries in place.
     *
     * Set [prepend] to `true` when loading older history pages ahead of already-loaded newer
     * entries. Returns the full reconciled entry list in display order.
     */
    fun ingestPage(page: HistoryPage, prepend: Boolean = false): List<CanonicalHistoryEntry> {
        return lock.writeLock().withLock {
            val prependBuffer = mutableListOf<HistoryEntryIdentity>()
            for (rawEntry in page.entries) {
                val canonicalEntry = rawEntry.copy(
                    source = HistoryEntrySource.CANONICAL_HISTORY,
                    isInterim = false,
                )
                upsertCanonicalLocked(canonicalEntry, prepend, prependBuffer)
            }
            if (prepend && prependBuffer.isNotEmpty()) {
                orderedIdentities.addAll(0, prependBuffer)
            }
            orderedIdentities.mapNotNull { entriesByIdentity[it] }
        }
    }

    /**
     * Reconciles a live streamed [entry] into the reader using its deterministic stable identity.
     *
     * - If an authoritative [HistoryEntrySource.CANONICAL_HISTORY] entry already exists for the
     *   same (or aliased) identity, the canonical entry is preserved and returned unchanged.
     * - If a [HistoryEntrySource.STREAMED] entry already exists for the same identity (for example,
     *   streaming delta updates sharing a [CanonicalHistoryEntry.canonicalKey]), it is updated in
     *   place without changing its identity or position.
     * - Otherwise the streamed entry is appended.
     */
    fun reconcileStreamed(entry: CanonicalHistoryEntry): CanonicalHistoryEntry {
        return lock.writeLock().withLock {
            val streamedEntry = entry.copy(source = HistoryEntrySource.STREAMED)
            val rawIdentity = identityOf(streamedEntry)
            val resolvedIdentity = identityAliases[rawIdentity] ?: rawIdentity
            identitiesByValue[rawIdentity.value] = rawIdentity

            val existing = entriesByIdentity[resolvedIdentity]
            if (existing != null) {
                if (existing.source == HistoryEntrySource.CANONICAL_HISTORY) {
                    return@withLock existing
                }
                entriesByIdentity[resolvedIdentity] = streamedEntry
                identitiesByValue[resolvedIdentity.value] = resolvedIdentity
                return@withLock streamedEntry
            }

            entriesByIdentity[resolvedIdentity] = streamedEntry
            identitiesByValue[resolvedIdentity.value] = resolvedIdentity
            orderedIdentities.add(resolvedIdentity)
            streamedEntry
        }
    }

    /**
     * Looks up a reconciled entry by its [HistoryEntryIdentity], transparently following any
     * provisional-to-canonical reconciliation alias.
     */
    fun findByIdentity(identity: HistoryEntryIdentity): CanonicalHistoryEntry? {
        return lock.readLock().withLock {
            val resolved = identityAliases[identity] ?: identity
            entriesByIdentity[resolved]
        }
    }

    /**
     * Looks up a reconciled entry by its string [stableId] (`HistoryEntryIdentity.value`),
     * transparently following any provisional-to-canonical reconciliation alias.
     */
    fun findByStableId(stableId: String): CanonicalHistoryEntry? {
        return lock.readLock().withLock {
            val identity = identitiesByValue[stableId] ?: return@withLock null
            val resolved = identityAliases[identity] ?: identity
            entriesByIdentity[resolved]
        }
    }

    /**
     * Returns the current canonical [HistoryEntryIdentity] for [entry] within this reader,
     * resolving any provisional-to-canonical alias recorded during reconciliation.
     */
    fun identityFor(entry: CanonicalHistoryEntry): HistoryEntryIdentity {
        return resolveIdentity(identityOf(entry))
    }

    /**
     * Resolves [identity] to its canonical [HistoryEntryIdentity] if it was promoted during
     * streaming-to-history reconciliation, or returns [identity] unchanged otherwise.
     */
    fun resolveIdentity(identity: HistoryEntryIdentity): HistoryEntryIdentity {
        return lock.readLock().withLock {
            identityAliases[identity] ?: identity
        }
    }

    /** Returns an immutable snapshot of all reconciled history entries in order. */
    fun entries(): List<CanonicalHistoryEntry> {
        return lock.readLock().withLock {
            orderedIdentities.mapNotNull { entriesByIdentity[it] }
        }
    }

    /** Clears all ingested entries and reconciliation aliases from this reader. */
    fun clear() {
        lock.writeLock().withLock {
            orderedIdentities.clear()
            entriesByIdentity.clear()
            identityAliases.clear()
            identitiesByValue.clear()
        }
    }

    private fun upsertCanonicalLocked(
        canonicalEntry: CanonicalHistoryEntry,
        prepend: Boolean,
        prependBuffer: MutableList<HistoryEntryIdentity>,
    ) {
        val identity = identityOf(canonicalEntry)
        identitiesByValue[identity.value] = identity

        if (entriesByIdentity.containsKey(identity)) {
            entriesByIdentity[identity] = canonicalEntry
            return
        }

        if (identity.scheme != HistoryEntryIdentityScheme.CONTENT_HASH) {
            val incomingHash = contentHashOf(canonicalEntry)
            val provisionalIdx = orderedIdentities.indexOfFirst { existingId ->
                val existing = entriesByIdentity[existingId]
                val schemeUpgrade = existingId.scheme == HistoryEntryIdentityScheme.CONTENT_HASH ||
                    (
                        existingId.scheme == HistoryEntryIdentityScheme.POSITIONED_CONTENT_HASH &&
                            identity.scheme == HistoryEntryIdentityScheme.IMMUTABLE_KEY &&
                            existing?.canonicalSequence == canonicalEntry.canonicalSequence
                        )
                existing != null &&
                    existing.source == HistoryEntrySource.STREAMED &&
                    schemeUpgrade &&
                    existing.durableSessionId == canonicalEntry.durableSessionId &&
                    contentHashOf(existing) == incomingHash
            }
            if (provisionalIdx >= 0) {
                val provisionalId = orderedIdentities[provisionalIdx]
                entriesByIdentity.remove(provisionalId)
                entriesByIdentity[identity] = canonicalEntry
                orderedIdentities[provisionalIdx] = identity
                identityAliases[provisionalId] = identity
                identitiesByValue[provisionalId.value] = provisionalId
                return
            }
        }

        entriesByIdentity[identity] = canonicalEntry
        if (prepend) {
            prependBuffer.add(identity)
        } else {
            orderedIdentities.add(identity)
        }
    }

    companion object {
        private const val HASH_ALGORITHM = "SHA-256"
        private const val DOMAIN_SEPARATOR = "droidhelp.history.identity.v1"
        private const val KEY_PREFIX = "v1:key:"
        private const val SEQUENCE_PREFIX = "v1:seq:"
        private const val CONTENT_HASH_PREFIX = "v1:hash:"
        private const val PRESENCE_NULL: Byte = 0
        private const val PRESENCE_PRESENT: Byte = 1

        /**
         * Deterministically computes the stable [HistoryEntryIdentity] for [entry].
         *
         * The result depends strictly on immutable canonical identity fields and never on paging
         * offsets/cursors, volatile runtime session IDs, timestamps, or interim streaming state.
         */
        fun identityOf(entry: CanonicalHistoryEntry): HistoryEntryIdentity {
            val canonicalKey = entry.canonicalKey
            val canonicalSequence = entry.canonicalSequence
            return when {
                canonicalKey != null -> {
                    val digest = sha256Hex { md ->
                        md.updateField(DOMAIN_SEPARATOR)
                        md.updateField(HistoryEntryIdentityScheme.IMMUTABLE_KEY.name)
                        md.updateField(entry.durableSessionId)
                        md.updateField(canonicalKey)
                    }
                    HistoryEntryIdentity(
                        value = "$KEY_PREFIX$digest",
                        scheme = HistoryEntryIdentityScheme.IMMUTABLE_KEY,
                    )
                }
                canonicalSequence != null -> {
                    val canonicalContentHash = contentHashOf(entry)
                    val digest = sha256Hex { md ->
                        md.updateField(DOMAIN_SEPARATOR)
                        md.updateField(HistoryEntryIdentityScheme.POSITIONED_CONTENT_HASH.name)
                        md.updateField(entry.durableSessionId)
                        md.updateField(canonicalSequence.toString())
                        md.updateField(canonicalContentHash)
                    }
                    HistoryEntryIdentity(
                        value = "$SEQUENCE_PREFIX$digest",
                        scheme = HistoryEntryIdentityScheme.POSITIONED_CONTENT_HASH,
                    )
                }
                else -> HistoryEntryIdentity(
                    value = "$CONTENT_HASH_PREFIX${contentHashOf(entry)}",
                    scheme = HistoryEntryIdentityScheme.CONTENT_HASH,
                )
            }
        }

        /**
         * Deterministically computes the lowercase SHA-256 hex content digest for [entry] using
         * length-prefixed UTF-8 framing over its canonical content fields.
         */
        fun contentHashOf(entry: CanonicalHistoryEntry): String {
            return sha256Hex { md ->
                md.updateField(DOMAIN_SEPARATOR)
                md.updateField(HistoryEntryIdentityScheme.CONTENT_HASH.name)
                md.updateField(entry.durableSessionId)
                md.updateField(entry.role)
                md.updateField(entry.kind)
                md.updateField(entry.content)
                md.updateField(entry.toolCallId)
                val sortedAttributes = entry.canonicalAttributes.toSortedMap()
                md.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(sortedAttributes.size).array())
                for ((attrKey, attrValue) in sortedAttributes) {
                    md.updateField(attrKey)
                    md.updateField(attrValue)
                }
            }
        }

        private fun sha256Hex(buildPayload: (MessageDigest) -> Unit): String {
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            buildPayload(digest)
            return digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(Locale.US, byte)
            }
        }

        private fun MessageDigest.updateField(value: String?) {
            if (value == null) {
                update(PRESENCE_NULL)
                return
            }
            update(PRESENCE_PRESENT)
            val bytes = value.toByteArray(Charsets.UTF_8)
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            update(bytes)
        }
    }
}
