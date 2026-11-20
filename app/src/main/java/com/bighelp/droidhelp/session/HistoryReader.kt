package com.bighelp.droidhelp.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
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
 * Traversal direction for cursor-based canonical history paging.
 *
 * - [FORWARD]: Pages chronologically from oldest to newest entries.
 * - [BACKWARD]: Pages from newest toward oldest entries while preserving chronological order within
 *   each returned [HistoryPage.entries] slice so pages can be prepended cleanly.
 */
enum class HistoryPageDirection {
    FORWARD,
    BACKWARD,
}

/**
 * Thrown when a caller supplies a malformed, tampered, scope-mismatched, or unresolvable paging
 * cursor to [HistoryReader.readPage].
 */
class InvalidHistoryCursorException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

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
 * - Cursor-based canonical history paging ([readPage]) with fixed-size subsets, opaque and stable
 *   keyset cursors, and duplicate-free traversal over static histories.
 * - Paged ingestion ([ingestPage]) supporting forward paging, reverse prepending, and overlapping
 *   pages without duplicating entries.
 * - Streaming reconciliation ([reconcileStreamed]) that upgrades streamed entries in place when
 *   authoritative canonical history arrives (including promoting unkeyed provisional streamed
 *   entries by [CanonicalHistoryEntry.contentHash] while preserving alias lookup).
 */
class HistoryReader(
    initialEntries: List<CanonicalHistoryEntry> = emptyList(),
) {

    private val lock = ReentrantReadWriteLock(true)
    private val orderedIdentities = mutableListOf<HistoryEntryIdentity>()
    private val entriesByIdentity = mutableMapOf<HistoryEntryIdentity, CanonicalHistoryEntry>()
    private val identityAliases = mutableMapOf<HistoryEntryIdentity, HistoryEntryIdentity>()
    private val identitiesByValue = mutableMapOf<String, HistoryEntryIdentity>()

    init {
        if (initialEntries.isNotEmpty()) {
            ingestPage(HistoryPage(initialEntries))
        }
    }

    /** Number of distinct reconciled history entries currently held. */
    val size: Int
        get() = lock.readLock().withLock { orderedIdentities.size }

    /**
     * Reads a fixed-size page of canonical history entries using opaque, stable keyset cursors.
     *
     * - Returns up to [pageSize] entries in chronological order.
     * - [HistoryPage.hasMore] is `true` if and only if additional entries remain beyond this page,
     *   in which case [HistoryPage.nextCursor] carries an opaque, deterministic cursor anchored to
     *   the boundary entry's stable identity; otherwise [HistoryPage.nextCursor] is `null`.
     * - Iterating from `cursor = null` while [HistoryPage.hasMore] is `true` over a static history
     *   visits every matching entry exactly once without duplicates or gaps.
     *
     * @param pageSize Maximum number of entries in the returned page (must be >= 1).
     * @param cursor Opaque cursor from a prior [HistoryPage.nextCursor], or `null` for the first page.
     * @param durableSessionId Optional verbatim session filter bound into the returned cursor.
     * @param direction Traversal order ([HistoryPageDirection.FORWARD] or [HistoryPageDirection.BACKWARD]).
     * @param includeStreamed When `false` (default), includes only [HistoryEntrySource.CANONICAL_HISTORY]
     *   entries; when `true`, also includes live [HistoryEntrySource.STREAMED] entries.
     * @throws IllegalArgumentException if [pageSize] < 1 or [durableSessionId] is blank.
     * @throws InvalidHistoryCursorException if [cursor] is invalid, tampered, mismatched, or stale.
     */
    fun readPage(
        pageSize: Int = DEFAULT_PAGE_SIZE,
        cursor: String? = null,
        durableSessionId: String? = null,
        direction: HistoryPageDirection = HistoryPageDirection.FORWARD,
        includeStreamed: Boolean = false,
    ): HistoryPage {
        checkIntBounds(pageSize.toLong(), min = 1L, max = Int.MAX_VALUE.toLong())
        durableSessionId?.let { ensureIdNotNormalized(it) }

        val anchorIdentity = cursor?.let {
            HistoryCursorCodec.decode(
                cursor = it,
                expectedDirection = direction,
                expectedSessionId = durableSessionId,
                expectedIncludeStreamed = includeStreamed,
            )
        }

        return lock.readLock().withLock {
            val snapshot = filteredSnapshotLocked(durableSessionId, includeStreamed)
            val anchorIndex = anchorIdentity?.let { anchor ->
                resolveAnchorIndexLocked(snapshot, anchor)
            }
            val (pageItems, nextAnchor) = sliceSnapshot(snapshot, pageSize, anchorIndex, direction)
            val nextCursor = nextAnchor?.let { anchor ->
                HistoryCursorCodec.encode(anchor, direction, durableSessionId, includeStreamed)
            }
            HistoryPage(
                entries = pageItems.map { it.second },
                cursor = cursor,
                nextCursor = nextCursor,
                hasMore = nextCursor != null,
            )
        }
    }

    private fun filteredSnapshotLocked(
        durableSessionId: String?,
        includeStreamed: Boolean,
    ): List<Pair<HistoryEntryIdentity, CanonicalHistoryEntry>> {
        return orderedIdentities.mapNotNull { id ->
            val entry = entriesByIdentity[id]
            val matchesSource = includeStreamed || entry?.source == HistoryEntrySource.CANONICAL_HISTORY
            val matchesSession = durableSessionId == null || entry?.durableSessionId == durableSessionId
            if (entry != null && matchesSource && matchesSession) id to entry else null
        }
    }

    private fun resolveAnchorIndexLocked(
        snapshot: List<Pair<HistoryEntryIdentity, CanonicalHistoryEntry>>,
        anchor: HistoryEntryIdentity,
    ): Int {
        val resolved = identityAliases[anchor] ?: anchor
        val idx = snapshot.indexOfFirst { (id, _) -> id == resolved }
        if (idx < 0) {
            throw InvalidHistoryCursorException("History cursor anchor entry was not found")
        }
        return idx
    }

    private fun sliceSnapshot(
        snapshot: List<Pair<HistoryEntryIdentity, CanonicalHistoryEntry>>,
        pageSize: Int,
        anchorIndex: Int?,
        direction: HistoryPageDirection,
    ): Pair<List<Pair<HistoryEntryIdentity, CanonicalHistoryEntry>>, HistoryEntryIdentity?> {
        return when (direction) {
            HistoryPageDirection.FORWARD -> {
                val start = anchorIndex?.plus(1) ?: 0
                val end = minOf(start.toLong() + pageSize.toLong(), snapshot.size.toLong()).toInt()
                val items = if (start < snapshot.size) snapshot.subList(start, end) else emptyList()
                val anchor = if (end < snapshot.size && items.isNotEmpty()) items.last().first else null
                items to anchor
            }
            HistoryPageDirection.BACKWARD -> {
                val end = anchorIndex ?: snapshot.size
                val start = maxOf(0L, end.toLong() - pageSize.toLong()).toInt()
                val items = if (end > 0) snapshot.subList(start, end) else emptyList()
                val anchor = if (start > 0 && items.isNotEmpty()) items.first().first else null
                items to anchor
            }
        }
    }

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
        /** Default page size used by [readPage] when the caller does not specify one. */
        const val DEFAULT_PAGE_SIZE: Int = 50

        internal const val HASH_ALGORITHM = "SHA-256"
        private const val DOMAIN_SEPARATOR = "droidhelp.history.identity.v1"
        internal const val KEY_PREFIX = "v1:key:"
        internal const val SEQUENCE_PREFIX = "v1:seq:"
        internal const val CONTENT_HASH_PREFIX = "v1:hash:"
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

        internal fun MessageDigest.updateField(value: String?) {
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

private object HistoryCursorCodec {
    private const val CURSOR_VERSION_1: Byte = 1
    private const val SCOPE_DOMAIN = "droidhelp.history.cursor.scope.v1"
    private const val CHECKSUM_DOMAIN = "droidhelp.history.cursor.checksum.v1"
    private const val FLAG_BACKWARD: Int = 1 shl 0
    private const val FLAG_INCLUDE_STREAMED: Int = 1 shl 1
    private const val FLAG_HAS_SESSION_SCOPE: Int = 1 shl 2
    private const val KNOWN_FLAGS_MASK: Int = FLAG_BACKWARD or FLAG_INCLUDE_STREAMED or FLAG_HAS_SESSION_SCOPE
    private const val RESERVED_FLAGS_MASK: Int = KNOWN_FLAGS_MASK.inv()
    private const val SCHEME_IMMUTABLE_KEY: Byte = 0
    private const val SCHEME_POSITIONED_HASH: Byte = 1
    private const val SCHEME_CONTENT_HASH: Byte = 2
    private const val SCOPE_DIGEST_BYTES: Int = 16
    private const val IDENTITY_DIGEST_BYTES: Int = 32
    private const val CHECKSUM_BYTES: Int = 16
    private const val PAYLOAD_BYTES: Int = 1 + 1 + SCOPE_DIGEST_BYTES + 1 + IDENTITY_DIGEST_BYTES
    private const val TOTAL_BYTES: Int = PAYLOAD_BYTES + CHECKSUM_BYTES
    private const val HEX_RADIX: Int = 16
    private const val HEX_NIBBLE_SHIFT: Int = 4
    private const val BYTE_MASK: Int = 0xFF

    private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(
        anchor: HistoryEntryIdentity,
        direction: HistoryPageDirection,
        durableSessionId: String?,
        includeStreamed: Boolean,
    ): String {
        val flags = (if (direction == HistoryPageDirection.BACKWARD) FLAG_BACKWARD else 0) or
            (if (includeStreamed) FLAG_INCLUDE_STREAMED else 0) or
            (if (durableSessionId != null) FLAG_HAS_SESSION_SCOPE else 0)

        val (schemeByte, prefix) = when (anchor.scheme) {
            HistoryEntryIdentityScheme.IMMUTABLE_KEY -> SCHEME_IMMUTABLE_KEY to HistoryReader.KEY_PREFIX
            HistoryEntryIdentityScheme.POSITIONED_CONTENT_HASH ->
                SCHEME_POSITIONED_HASH to HistoryReader.SEQUENCE_PREFIX
            HistoryEntryIdentityScheme.CONTENT_HASH -> SCHEME_CONTENT_HASH to HistoryReader.CONTENT_HASH_PREFIX
        }
        val hex = anchor.value.removePrefix(prefix)
        require(anchor.value.startsWith(prefix) && hex.length == IDENTITY_DIGEST_BYTES * 2) {
            "Anchor identity does not match expected digest format"
        }

        val identityDigest = ByteArray(IDENTITY_DIGEST_BYTES) { index ->
            val hi = Character.digit(hex[index * 2], HEX_RADIX)
            val lo = Character.digit(hex[index * 2 + 1], HEX_RADIX)
            require(hi >= 0 && lo >= 0) { "Anchor identity digest contains non-hex characters" }
            ((hi shl HEX_NIBBLE_SHIFT) or lo).toByte()
        }

        val payload = ByteBuffer.allocate(PAYLOAD_BYTES)
            .put(CURSOR_VERSION_1)
            .put(flags.toByte())
            .put(scopeDigest(durableSessionId))
            .put(schemeByte)
            .put(identityDigest)
            .array()

        val full = ByteBuffer.allocate(TOTAL_BYTES)
            .put(payload)
            .put(checksum(payload))
            .array()

        return urlEncoder.encodeToString(full)
    }

    fun decode(
        cursor: String,
        expectedDirection: HistoryPageDirection,
        expectedSessionId: String?,
        expectedIncludeStreamed: Boolean,
    ): HistoryEntryIdentity {
        ensureValid(cursor.isNotBlank() && !cursor.contains('=')) {
            "History cursor must be a non-empty opaque token"
        }
        val raw = try {
            urlDecoder.decode(cursor)
        } catch (e: IllegalArgumentException) {
            throw InvalidHistoryCursorException("Malformed history cursor encoding", e)
        }
        ensureValid(raw.size == TOTAL_BYTES && urlEncoder.encodeToString(raw) == cursor) {
            "Malformed or non-canonical history cursor"
        }

        val payload = raw.copyOfRange(0, PAYLOAD_BYTES)
        val actualChecksum = raw.copyOfRange(PAYLOAD_BYTES, TOTAL_BYTES)
        ensureValid(MessageDigest.isEqual(checksum(payload), actualChecksum)) {
            "History cursor failed integrity verification"
        }

        val buffer = ByteBuffer.wrap(payload)
        val version = buffer.get()
        ensureValid(version == CURSOR_VERSION_1) { "Unsupported history cursor version: $version" }

        val flags = buffer.get().toInt() and BYTE_MASK
        ensureValid((flags and RESERVED_FLAGS_MASK) == 0) { "History cursor contains unsupported flags" }

        val cursorDirection = if ((flags and FLAG_BACKWARD) != 0) {
            HistoryPageDirection.BACKWARD
        } else {
            HistoryPageDirection.FORWARD
        }
        val cursorIncludeStreamed = (flags and FLAG_INCLUDE_STREAMED) != 0
        val cursorHasSessionScope = (flags and FLAG_HAS_SESSION_SCOPE) != 0

        ensureValid(cursorDirection == expectedDirection) {
            "History cursor direction ($cursorDirection) does not match requested direction ($expectedDirection)"
        }
        ensureValid(cursorIncludeStreamed == expectedIncludeStreamed) {
            "History cursor streamed-entry scope does not match request"
        }
        ensureValid(cursorHasSessionScope == (expectedSessionId != null)) {
            "History cursor session scope does not match request"
        }

        val actualScopeDigest = ByteArray(SCOPE_DIGEST_BYTES)
        buffer.get(actualScopeDigest)
        ensureValid(MessageDigest.isEqual(scopeDigest(expectedSessionId), actualScopeDigest)) {
            "History cursor does not belong to the requested session"
        }

        val schemeByte = buffer.get()
        val identityDigest = ByteArray(IDENTITY_DIGEST_BYTES)
        buffer.get(identityDigest)

        val (scheme, prefix) = when (schemeByte) {
            SCHEME_IMMUTABLE_KEY -> HistoryEntryIdentityScheme.IMMUTABLE_KEY to HistoryReader.KEY_PREFIX
            SCHEME_POSITIONED_HASH ->
                HistoryEntryIdentityScheme.POSITIONED_CONTENT_HASH to HistoryReader.SEQUENCE_PREFIX
            SCHEME_CONTENT_HASH -> HistoryEntryIdentityScheme.CONTENT_HASH to HistoryReader.CONTENT_HASH_PREFIX
            else -> throw InvalidHistoryCursorException("Unsupported history cursor identity scheme: $schemeByte")
        }

        val hex = identityDigest.joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte)
        }
        return HistoryEntryIdentity(value = "$prefix$hex", scheme = scheme)
    }

    private fun scopeDigest(durableSessionId: String?): ByteArray {
        val md = MessageDigest.getInstance(HistoryReader.HASH_ALGORITHM)
        with(HistoryReader) {
            md.updateField(SCOPE_DOMAIN)
            md.updateField(durableSessionId)
        }
        return md.digest().copyOf(SCOPE_DIGEST_BYTES)
    }

    private fun checksum(payload: ByteArray): ByteArray {
        val md = MessageDigest.getInstance(HistoryReader.HASH_ALGORITHM)
        with(HistoryReader) {
            md.updateField(CHECKSUM_DOMAIN)
        }
        md.update(payload)
        return md.digest().copyOf(CHECKSUM_BYTES)
    }

    private fun ensureValid(condition: Boolean, message: () -> String) {
        if (!condition) {
            throw InvalidHistoryCursorException(message())
        }
    }
}
