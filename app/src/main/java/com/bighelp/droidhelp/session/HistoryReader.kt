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
 * Type of change detected for a single history entry during reconciliation.
 */
enum class HistoryChangeKind {
    INSERT,
    UPDATE,
    DELETE,
}

/**
 * A single insert, update, or deletion detected when reconciling paged canonical history with
 * streaming rows.
 *
 * - [HistoryChangeKind.INSERT]: [previous] and [previousIndex] are `null`; [current] and
 *   [currentIndex] describe the newly merged entry.
 * - [HistoryChangeKind.UPDATE]: [previous] and [current] describe the prior and reconciled entries
 *   at [previousIndex] and [currentIndex]; [previousIdentity] is non-null when a provisional
 *   streamed identity was promoted to a canonical identity.
 * - [HistoryChangeKind.DELETE]: [previous] and [previousIndex] describe the removed entry;
 *   [current] and [currentIndex] are `null`.
 */
data class HistoryReconciliationChange(
    val kind: HistoryChangeKind,
    val identity: HistoryEntryIdentity,
    val previousIdentity: HistoryEntryIdentity? = null,
    val previous: CanonicalHistoryEntry? = null,
    val current: CanonicalHistoryEntry? = null,
    val previousIndex: Int? = null,
    val currentIndex: Int? = null,
)

/**
 * Structured diff produced by history reconciliation, separating inserts, updates, and deletions.
 */
data class HistoryReconciliationDiff(
    val changes: List<HistoryReconciliationChange> = emptyList(),
) {
    val inserts: List<HistoryReconciliationChange>
        get() = changes.filter { it.kind == HistoryChangeKind.INSERT }

    val updates: List<HistoryReconciliationChange>
        get() = changes.filter { it.kind == HistoryChangeKind.UPDATE }

    val deletes: List<HistoryReconciliationChange>
        get() = changes.filter { it.kind == HistoryChangeKind.DELETE }

    val isEmpty: Boolean
        get() = changes.isEmpty()
}

/**
 * Unified output of reconciling paged canonical history with streaming rows.
 *
 * - [entries]: Duplicate-free merged sequence in deterministic chronological/display order.
 * - [diff]: Detected inserts, updates, and deletions relative to the pre-reconciliation baseline.
 * - [identityAliases]: Provisional-to-canonical identity aliases active after reconciliation.
 * - [ignoredDuplicates]: Number of duplicate incoming rows safely ignored without duplication.
 * - [ignoredOutOfOrder]: Number of stale or out-of-order streaming rows safely ignored without
 *   regressing newer or canonical state.
 */
data class HistoryReconciliationResult(
    val entries: List<CanonicalHistoryEntry>,
    val diff: HistoryReconciliationDiff,
    val identityAliases: Map<HistoryEntryIdentity, HistoryEntryIdentity> = emptyMap(),
    val ignoredDuplicates: Int = 0,
    val ignoredOutOfOrder: Int = 0,
)

/**
 * Operation represented by an incoming [StreamingHistoryRow].
 */
enum class StreamingRowOperation {
    UPSERT,
    DELETE,
}

/**
 * Incoming streaming row event to reconcile against paged canonical history.
 *
 * - For [StreamingRowOperation.UPSERT], [entry] must be provided.
 * - For [StreamingRowOperation.DELETE], either [targetIdentity] or [entry] must be provided.
 * - Optional [streamSequence] carries a monotonic stream frame sequence (`>= 0`) so out-of-order
 *   or replayed frames never overwrite newer state or resurrect deleted rows.
 */
data class StreamingHistoryRow(
    val operation: StreamingRowOperation = StreamingRowOperation.UPSERT,
    val entry: CanonicalHistoryEntry? = null,
    val targetIdentity: HistoryEntryIdentity? = null,
    val streamSequence: Long? = null,
) {
    init {
        streamSequence?.let { checkIntBounds(it, min = 0L, max = Long.MAX_VALUE) }
        when (operation) {
            StreamingRowOperation.UPSERT -> {
                val upsertEntry = requireNotNull(entry) {
                    "UPSERT streaming row must provide an entry"
                }
                if (targetIdentity != null) {
                    require(targetIdentity == HistoryReader.identityOf(upsertEntry)) {
                        "UPSERT targetIdentity must match entry identity"
                    }
                }
            }
            StreamingRowOperation.DELETE -> {
                require(targetIdentity != null || entry != null) {
                    "DELETE streaming row must provide targetIdentity or entry"
                }
            }
        }
    }

    /** Stable [HistoryEntryIdentity] targeted by this streaming row. */
    val resolvedIdentity: HistoryEntryIdentity
        get() = targetIdentity ?: HistoryReader.identityOf(requireNotNull(entry))

    companion object {
        /** Creates an [StreamingRowOperation.UPSERT] streaming row for [entry]. */
        fun upsert(
            entry: CanonicalHistoryEntry,
            streamSequence: Long? = null,
        ): StreamingHistoryRow = StreamingHistoryRow(
            operation = StreamingRowOperation.UPSERT,
            entry = entry.copy(source = HistoryEntrySource.STREAMED),
            targetIdentity = HistoryReader.identityOf(entry),
            streamSequence = streamSequence,
        )

        /** Creates a [StreamingRowOperation.DELETE] streaming row targeting [identity]. */
        fun delete(
            identity: HistoryEntryIdentity,
            streamSequence: Long? = null,
        ): StreamingHistoryRow = StreamingHistoryRow(
            operation = StreamingRowOperation.DELETE,
            entry = null,
            targetIdentity = identity,
            streamSequence = streamSequence,
        )

        /** Creates a [StreamingRowOperation.DELETE] streaming row targeting [entry]. */
        fun delete(
            entry: CanonicalHistoryEntry,
            streamSequence: Long? = null,
        ): StreamingHistoryRow = StreamingHistoryRow(
            operation = StreamingRowOperation.DELETE,
            entry = entry,
            targetIdentity = HistoryReader.identityOf(entry),
            streamSequence = streamSequence,
        )
    }
}

/**
 * Thread-safe reader and reconciler for canonical session history entries.
 *
 * Provides:
 * - Deterministic, collision-resistant stable identities ([identityOf], [contentHashOf]) that are
 *   invariant to paging boundaries, cursors, page offsets, timestamps, volatile runtime session
 *   IDs, and transient streaming flags.
 * - Cursor-based canonical history paging ([readPage]) with fixed-size subsets, opaque and stable
 *   keyset cursors, and duplicate-free traversal over static histories.
 * - Paged ingestion ([ingestPage], [reconcilePage]) supporting forward paging, reverse prepending,
 *   overlapping pages, authoritative snapshot pruning, and structured diff detection.
 * - Streaming reconciliation ([reconcileStreamed], [reconcileStream], [reconcile]) matching paged
 *   canonical history rows to incoming streaming rows by stable identity, detecting inserts,
 *   updates, and deletions while handling duplicates and out-of-order rows gracefully.
 */
class HistoryReader(
    initialEntries: List<CanonicalHistoryEntry> = emptyList(),
) {

    private val lock = ReentrantReadWriteLock(true)
    private val ledger = HistoryLedger()

    init {
        if (initialEntries.isNotEmpty()) {
            ingestPage(HistoryPage(initialEntries))
        }
    }

    /** Number of distinct reconciled history entries currently held. */
    val size: Int
        get() = lock.readLock().withLock { ledger.size() }

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
            val snapshot = ledger.filteredSnapshot(durableSessionId, includeStreamed)
            val anchorIndex = anchorIdentity?.let { anchor ->
                ledger.resolveAnchorIndex(snapshot, anchor)
            }
            val (pageItems, nextAnchor) = HistoryDiffEngine.sliceSnapshot(
                snapshot = snapshot,
                pageSize = pageSize,
                anchorIndex = anchorIndex,
                direction = direction,
            )
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

    /**
     * Ingests a [HistoryPage] of canonical entries into the reader, deduplicating by stable
     * identity and upgrading any matching streamed entries in place.
     *
     * Set [prepend] to `true` when loading older history pages ahead of already-loaded newer
     * entries. Returns the full reconciled entry list in display order.
     */
    fun ingestPage(page: HistoryPage, prepend: Boolean = false): List<CanonicalHistoryEntry> {
        return reconcilePage(page = page, prepend = prepend).entries
    }

    /**
     * Reconciles a canonical [HistoryPage] into the reader and returns the unified merged sequence
     * along with the detected [HistoryReconciliationDiff] (inserts, updates, and deletions).
     *
     * - Set [prepend] to `true` when loading older pages ahead of already-loaded newer entries.
     * - Set [pruneMissingInScope] to `true` when [page] represents an authoritative canonical
     *   snapshot (for example after rollback or server-side deletion) so missing entries within
     *   [durableSessionId] (or all sessions if `null`) are removed and reported as
     *   [HistoryChangeKind.DELETE].
     * - When [pruneMissingInScope] is `true`, [retainUnmatchedStreamed] controls whether live
     *   [HistoryEntrySource.STREAMED] rows not yet present in canonical history are preserved.
     */
    fun reconcilePage(
        page: HistoryPage,
        prepend: Boolean = false,
        pruneMissingInScope: Boolean = false,
        durableSessionId: String? = null,
        retainUnmatchedStreamed: Boolean = true,
    ): HistoryReconciliationResult {
        durableSessionId?.let { ensureIdNotNormalized(it) }
        val options = PageReconciliationOptions(
            prepend = prepend,
            pruneMissingInScope = pruneMissingInScope,
            durableSessionId = durableSessionId,
            retainUnmatchedStreamed = retainUnmatchedStreamed,
        )
        return lock.writeLock().withLock {
            HistoryReconciler.reconcilePage(
                ledger = ledger,
                page = page,
                options = options,
            )
        }
    }

    /**
     * Reconciles a live streamed [entry] into the reader using its deterministic stable identity.
     *
     * - If an authoritative [HistoryEntrySource.CANONICAL_HISTORY] entry already exists for the
     *   same (or aliased) identity, the canonical entry is preserved and returned unchanged.
     * - If a [HistoryEntrySource.STREAMED] entry already exists for the same identity (for example,
     *   streaming delta updates sharing a [CanonicalHistoryEntry.canonicalKey]), it is updated in
     *   place without changing its identity or position unless out-of-order.
     * - Otherwise the streamed entry is inserted in canonical sequence order (or appended).
     */
    fun reconcileStreamed(entry: CanonicalHistoryEntry): CanonicalHistoryEntry {
        return lock.writeLock().withLock {
            val streamedEntry = entry.copy(source = HistoryEntrySource.STREAMED)
            val rawIdentity = identityOf(streamedEntry)
            val counters = ReconciliationCounters()
            HistoryReconciler.applyStreamUpsert(
                ledger = ledger,
                request = StreamUpsertRequest(
                    entry = streamedEntry,
                    streamSequence = null,
                    allowCanonicalUpdateFromStream = false,
                ),
                counters = counters,
            )
            val resolvedIdentity = ledger.resolveAlias(rawIdentity)
            ledger.entriesByIdentity[resolvedIdentity] ?: streamedEntry
        }
    }

    /**
     * Reconciles a batch of incoming [StreamingHistoryRow] events (upserts and deletions) against
     * the reader's current canonical and streamed history, returning the unified merged sequence
     * and the net [HistoryReconciliationDiff] relative to the pre-reconciliation state.
     *
     * Duplicate rows and stale/out-of-order stream frames (by [StreamingHistoryRow.streamSequence],
     * finalized-to-interim regression, or timestamp regression) are handled gracefully without
     * duplication or state regression.
     */
    fun reconcileStream(rows: List<StreamingHistoryRow>): HistoryReconciliationResult {
        return lock.writeLock().withLock {
            HistoryReconciler.reconcileStreamBatch(
                ledger = ledger,
                rows = rows,
                allowCanonicalUpdateFromStream = true,
            )
        }
    }

    /**
     * Looks up a reconciled entry by its [HistoryEntryIdentity], transparently following any
     * provisional-to-canonical reconciliation alias.
     */
    fun findByIdentity(identity: HistoryEntryIdentity): CanonicalHistoryEntry? {
        return lock.readLock().withLock {
            ledger.findByIdentity(identity)
        }
    }

    /**
     * Looks up a reconciled entry by its string [stableId] (`HistoryEntryIdentity.value`),
     * transparently following any provisional-to-canonical reconciliation alias.
     */
    fun findByStableId(stableId: String): CanonicalHistoryEntry? {
        return lock.readLock().withLock {
            ledger.findByStableId(stableId)
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
            ledger.resolveAlias(identity)
        }
    }

    /** Returns an immutable snapshot of all reconciled history entries in order. */
    fun entries(): List<CanonicalHistoryEntry> {
        return lock.readLock().withLock {
            ledger.entries()
        }
    }

    /** Clears all ingested entries, stream watermarks, and reconciliation aliases from this reader. */
    fun clear() {
        lock.writeLock().withLock {
            ledger.clear()
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

        /**
         * Reconciles [pagedHistory] canonical rows with incoming [streamingRows] using stable
         * identities, producing a unified duplicate-free merged sequence and structured diff
         * (inserts, updates, and deletions) relative to [pagedHistory].
         */
        fun reconcile(
            pagedHistory: List<CanonicalHistoryEntry>,
            streamingRows: List<StreamingHistoryRow>,
        ): HistoryReconciliationResult {
            val reader = HistoryReader()
            val pageResult = reader.reconcilePage(HistoryPage(pagedHistory))
            val streamResult = reader.reconcileStream(streamingRows)
            return streamResult.copy(
                ignoredDuplicates = pageResult.ignoredDuplicates + streamResult.ignoredDuplicates,
            )
        }

        /**
         * Convenience overload of [reconcile] for raw [CanonicalHistoryEntry] streaming rows
         * treated as [StreamingRowOperation.UPSERT] events.
         */
        fun reconcileEntries(
            pagedHistory: List<CanonicalHistoryEntry>,
            streamingEntries: List<CanonicalHistoryEntry>,
        ): HistoryReconciliationResult {
            return reconcile(
                pagedHistory = pagedHistory,
                streamingRows = streamingEntries.map { StreamingHistoryRow.upsert(it) },
            )
        }

        /**
         * Computes the structured [HistoryReconciliationDiff] (inserts, updates, and deletions)
         * transforming [previous] into [current] using deterministic stable identities and
         * optional provisional-to-canonical [identityAliases].
         */
        fun diff(
            previous: List<CanonicalHistoryEntry>,
            current: List<CanonicalHistoryEntry>,
            identityAliases: Map<HistoryEntryIdentity, HistoryEntryIdentity> = emptyMap(),
        ): HistoryReconciliationDiff {
            val beforeSnapshot = previous.map { identityOf(it) to it }
            val afterSnapshot = current.map { identityOf(it) to it }
            return HistoryDiffEngine.computeDiff(beforeSnapshot, afterSnapshot, identityAliases)
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

private class ReconciliationCounters(
    var ignoredDuplicates: Int = 0,
    var ignoredOutOfOrder: Int = 0,
)

private enum class StreamOrderDecision {
    ACCEPT,
    DUPLICATE,
    OUT_OF_ORDER,
}

private class HistoryLedger {
    val orderedIdentities = mutableListOf<HistoryEntryIdentity>()
    val entriesByIdentity = mutableMapOf<HistoryEntryIdentity, CanonicalHistoryEntry>()
    val identityAliases = mutableMapOf<HistoryEntryIdentity, HistoryEntryIdentity>()
    val identitiesByValue = mutableMapOf<String, HistoryEntryIdentity>()
    val lastStreamSequence = mutableMapOf<HistoryEntryIdentity, Long>()
    val deletedIdentities = mutableSetOf<HistoryEntryIdentity>()

    fun size(): Int = orderedIdentities.size

    fun entries(): List<CanonicalHistoryEntry> = orderedIdentities.mapNotNull { entriesByIdentity[it] }

    fun snapshot(): List<Pair<HistoryEntryIdentity, CanonicalHistoryEntry>> {
        return orderedIdentities.mapNotNull { id ->
            entriesByIdentity[id]?.let { id to it }
        }
    }

    fun filteredSnapshot(
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

    fun resolveAnchorIndex(
        snapshot: List<Pair<HistoryEntryIdentity, CanonicalHistoryEntry>>,
        anchor: HistoryEntryIdentity,
    ): Int {
        val resolved = resolveAlias(anchor)
        val idx = snapshot.indexOfFirst { (id, _) -> id == resolved }
        if (idx < 0) {
            throw InvalidHistoryCursorException("History cursor anchor entry was not found")
        }
        return idx
    }

    fun resolveAlias(identity: HistoryEntryIdentity): HistoryEntryIdentity {
        return HistoryDiffEngine.resolveAliasChain(identity, identityAliases)
    }

    fun recordAlias(provisionalId: HistoryEntryIdentity, canonicalId: HistoryEntryIdentity) {
        if (provisionalId == canonicalId) return
        identityAliases[provisionalId] = canonicalId
        identitiesByValue[provisionalId.value] = provisionalId
        identitiesByValue[canonicalId.value] = canonicalId
        for ((aliasKey, aliasTarget) in identityAliases.entries.toList()) {
            if (aliasTarget == provisionalId) {
                identityAliases[aliasKey] = canonicalId
            }
        }
    }

    fun findByIdentity(identity: HistoryEntryIdentity): CanonicalHistoryEntry? {
        return entriesByIdentity[resolveAlias(identity)]
    }

    fun findByStableId(stableId: String): CanonicalHistoryEntry? {
        val identity = identitiesByValue[stableId]
        return identity?.let { entriesByIdentity[resolveAlias(it)] }
    }

    fun insertOrdered(identity: HistoryEntryIdentity, entry: CanonicalHistoryEntry) {
        val sequence = entry.canonicalSequence
        val insertIndex = if (sequence != null) {
            orderedIdentities.indexOfFirst { existingId ->
                val existing = entriesByIdentity[existingId]
                existing != null &&
                    existing.durableSessionId == entry.durableSessionId &&
                    existing.canonicalSequence != null &&
                    existing.canonicalSequence > sequence
            }
        } else {
            -1
        }
        if (insertIndex >= 0) {
            orderedIdentities.add(insertIndex, identity)
        } else {
            orderedIdentities.add(identity)
        }
    }

    fun repositionIfOutOfOrder(identity: HistoryEntryIdentity, entry: CanonicalHistoryEntry) {
        val sequence = entry.canonicalSequence ?: return
        val currentIndex = orderedIdentities.indexOf(identity)
        if (currentIndex < 0) return

        val hasGreaterBefore = (0 until currentIndex).any { idx ->
            val other = entriesByIdentity[orderedIdentities[idx]]
            other != null &&
                other.durableSessionId == entry.durableSessionId &&
                other.canonicalSequence != null &&
                other.canonicalSequence > sequence
        }
        val hasSmallerAfter = ((currentIndex + 1) until orderedIdentities.size).any { idx ->
            val other = entriesByIdentity[orderedIdentities[idx]]
            other != null &&
                other.durableSessionId == entry.durableSessionId &&
                other.canonicalSequence != null &&
                other.canonicalSequence < sequence
        }
        if (hasGreaterBefore || hasSmallerAfter) {
            orderedIdentities.removeAt(currentIndex)
            insertOrdered(identity, entry)
        }
    }

    fun findMatchingProvisionalStreamedIndex(
        canonicalEntry: CanonicalHistoryEntry,
        identity: HistoryEntryIdentity,
    ): Int {
        if (identity.scheme == HistoryEntryIdentityScheme.CONTENT_HASH) {
            return -1
        }
        val incomingHash = HistoryReader.contentHashOf(canonicalEntry)
        return orderedIdentities.indexOfFirst { existingId ->
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
                HistoryReader.contentHashOf(existing) == incomingHash
        }
    }

    fun findMatchingCanonicalIdentity(
        streamedEntry: CanonicalHistoryEntry,
        streamedIdentity: HistoryEntryIdentity,
    ): HistoryEntryIdentity? {
        if (streamedIdentity.scheme == HistoryEntryIdentityScheme.IMMUTABLE_KEY) {
            return null
        }
        val incomingHash = HistoryReader.contentHashOf(streamedEntry)
        val matchIdx = orderedIdentities.indexOfLast { existingId ->
            val existing = entriesByIdentity[existingId]
            val schemeDowngrade = when (streamedIdentity.scheme) {
                HistoryEntryIdentityScheme.CONTENT_HASH ->
                    existingId.scheme != HistoryEntryIdentityScheme.CONTENT_HASH
                HistoryEntryIdentityScheme.POSITIONED_CONTENT_HASH ->
                    existingId.scheme == HistoryEntryIdentityScheme.IMMUTABLE_KEY &&
                        existing?.canonicalSequence == streamedEntry.canonicalSequence
                HistoryEntryIdentityScheme.IMMUTABLE_KEY -> false
            }
            existing != null &&
                existing.source == HistoryEntrySource.CANONICAL_HISTORY &&
                schemeDowngrade &&
                existing.durableSessionId == streamedEntry.durableSessionId &&
                HistoryReader.contentHashOf(existing) == incomingHash
        }
        return if (matchIdx >= 0) orderedIdentities[matchIdx] else null
    }

    fun clear() {
        orderedIdentities.clear()
        entriesByIdentity.clear()
        identityAliases.clear()
        identitiesByValue.clear()
        lastStreamSequence.clear()
        deletedIdentities.clear()
    }
}

private data class PageReconciliationOptions(
    val prepend: Boolean,
    val pruneMissingInScope: Boolean,
    val durableSessionId: String?,
    val retainUnmatchedStreamed: Boolean,
)

private class PageReconciliationState(
    val prepend: Boolean,
    val prependBuffer: MutableList<HistoryEntryIdentity> = mutableListOf(),
    val seenInPage: MutableSet<HistoryEntryIdentity> = mutableSetOf(),
    val counters: ReconciliationCounters = ReconciliationCounters(),
)

private data class StreamUpsertRequest(
    val entry: CanonicalHistoryEntry,
    val streamSequence: Long?,
    val allowCanonicalUpdateFromStream: Boolean,
)

private object HistoryReconciler {

    fun reconcilePage(
        ledger: HistoryLedger,
        page: HistoryPage,
        options: PageReconciliationOptions,
    ): HistoryReconciliationResult {
        val before = ledger.snapshot()
        val state = PageReconciliationState(prepend = options.prepend)

        for (rawEntry in page.entries) {
            val canonicalEntry = rawEntry.copy(
                source = HistoryEntrySource.CANONICAL_HISTORY,
                isInterim = false,
            )
            upsertCanonicalEntry(ledger, canonicalEntry, state)
        }
        if (options.prepend && state.prependBuffer.isNotEmpty()) {
            ledger.orderedIdentities.addAll(0, state.prependBuffer)
        }
        if (options.pruneMissingInScope) {
            pruneMissingEntries(ledger, state.seenInPage, options)
        }

        val after = ledger.snapshot()
        val diff = HistoryDiffEngine.computeDiff(before, after, ledger.identityAliases)
        return HistoryReconciliationResult(
            entries = after.map { it.second },
            diff = diff,
            identityAliases = ledger.identityAliases.toMap(),
            ignoredDuplicates = state.counters.ignoredDuplicates,
            ignoredOutOfOrder = state.counters.ignoredOutOfOrder,
        )
    }

    private fun upsertCanonicalEntry(
        ledger: HistoryLedger,
        canonicalEntry: CanonicalHistoryEntry,
        state: PageReconciliationState,
    ) {
        val identity = HistoryReader.identityOf(canonicalEntry)
        ledger.identitiesByValue[identity.value] = identity
        val isRepeatInPage = !state.seenInPage.add(identity)
        ledger.deletedIdentities.remove(identity)
        ledger.lastStreamSequence.remove(identity)

        val existing = ledger.entriesByIdentity[identity]
        val provisionalIdx = if (existing == null) {
            ledger.findMatchingProvisionalStreamedIndex(canonicalEntry, identity)
        } else {
            -1
        }

        when {
            existing != null -> {
                if (existing.source == HistoryEntrySource.CANONICAL_HISTORY &&
                    HistoryDiffEngine.sameDurableFields(existing, canonicalEntry)
                ) {
                    state.counters.ignoredDuplicates++
                }
                ledger.entriesByIdentity[identity] = canonicalEntry
                ledger.repositionIfOutOfOrder(identity, canonicalEntry)
            }
            provisionalIdx >= 0 -> {
                val provisionalId = ledger.orderedIdentities[provisionalIdx]
                ledger.entriesByIdentity.remove(provisionalId)
                ledger.entriesByIdentity[identity] = canonicalEntry
                ledger.orderedIdentities[provisionalIdx] = identity
                ledger.recordAlias(provisionalId, identity)
                ledger.lastStreamSequence.remove(provisionalId)
                ledger.deletedIdentities.remove(provisionalId)
                ledger.repositionIfOutOfOrder(identity, canonicalEntry)
            }
            isRepeatInPage -> state.counters.ignoredDuplicates++
            else -> {
                ledger.entriesByIdentity[identity] = canonicalEntry
                if (state.prepend) {
                    state.prependBuffer.add(identity)
                } else {
                    ledger.insertOrdered(identity, canonicalEntry)
                }
            }
        }
    }

    private fun pruneMissingEntries(
        ledger: HistoryLedger,
        seenInPage: Set<HistoryEntryIdentity>,
        options: PageReconciliationOptions,
    ) {
        val toRemove = ledger.orderedIdentities.filter { id ->
            val entry = ledger.entriesByIdentity[id]
            val inSession = options.durableSessionId == null ||
                entry?.durableSessionId == options.durableSessionId
            val removableSource = !options.retainUnmatchedStreamed ||
                entry?.source == HistoryEntrySource.CANONICAL_HISTORY
            entry != null && inSession && removableSource && id !in seenInPage
        }
        for (id in toRemove) {
            ledger.orderedIdentities.remove(id)
            ledger.entriesByIdentity.remove(id)
            ledger.deletedIdentities.add(id)
        }
    }

    fun reconcileStreamBatch(
        ledger: HistoryLedger,
        rows: List<StreamingHistoryRow>,
        allowCanonicalUpdateFromStream: Boolean,
    ): HistoryReconciliationResult {
        val before = ledger.snapshot()
        val counters = ReconciliationCounters()
        for (row in rows) {
            when (row.operation) {
                StreamingRowOperation.UPSERT -> applyStreamUpsert(
                    ledger = ledger,
                    request = StreamUpsertRequest(
                        entry = requireNotNull(row.entry).copy(source = HistoryEntrySource.STREAMED),
                        streamSequence = row.streamSequence,
                        allowCanonicalUpdateFromStream = allowCanonicalUpdateFromStream,
                    ),
                    counters = counters,
                )
                StreamingRowOperation.DELETE -> applyStreamDelete(ledger, row, counters)
            }
        }
        val after = ledger.snapshot()
        val diff = HistoryDiffEngine.computeDiff(before, after, ledger.identityAliases)
        return HistoryReconciliationResult(
            entries = after.map { it.second },
            diff = diff,
            identityAliases = ledger.identityAliases.toMap(),
            ignoredDuplicates = counters.ignoredDuplicates,
            ignoredOutOfOrder = counters.ignoredOutOfOrder,
        )
    }

    fun applyStreamUpsert(
        ledger: HistoryLedger,
        request: StreamUpsertRequest,
        counters: ReconciliationCounters,
    ) {
        val rawIdentity = HistoryReader.identityOf(request.entry)
        ledger.identitiesByValue[rawIdentity.value] = rawIdentity
        var resolvedId = ledger.resolveAlias(rawIdentity)

        if (!ledger.entriesByIdentity.containsKey(resolvedId)) {
            val canonicalMatch = ledger.findMatchingCanonicalIdentity(request.entry, resolvedId)
            if (canonicalMatch != null) {
                ledger.recordAlias(resolvedId, canonicalMatch)
                resolvedId = canonicalMatch
            }
        }

        val existing = ledger.entriesByIdentity[resolvedId]
        if (existing != null) {
            applyStreamUpsertExisting(ledger, resolvedId, existing, request, counters)
            return
        }

        val lastSeq = ledger.lastStreamSequence[resolvedId]
        val seqDecision = checkStreamSequence(lastSeq, request.streamSequence, isDuplicateContent = false)
        if (seqDecision != StreamOrderDecision.ACCEPT) {
            recordIgnored(seqDecision, counters)
            return
        }

        ledger.deletedIdentities.remove(resolvedId)
        ledger.entriesByIdentity[resolvedId] = request.entry
        ledger.identitiesByValue[resolvedId.value] = resolvedId
        ledger.insertOrdered(resolvedId, request.entry)
        if (request.streamSequence != null) {
            ledger.lastStreamSequence[resolvedId] = request.streamSequence
        }
    }

    private fun applyStreamUpsertExisting(
        ledger: HistoryLedger,
        resolvedId: HistoryEntryIdentity,
        existing: CanonicalHistoryEntry,
        request: StreamUpsertRequest,
        counters: ReconciliationCounters,
    ) {
        val incoming = request.entry
        val lastSeq = ledger.lastStreamSequence[resolvedId]
        val sameContent = HistoryReader.contentHashOf(existing) == HistoryReader.contentHashOf(incoming)
        val isCanonical = existing.source == HistoryEntrySource.CANONICAL_HISTORY
        val isDuplicate = if (isCanonical) {
            sameContent && !incoming.isInterim
        } else {
            sameContent &&
                existing.isInterim == incoming.isInterim &&
                (incoming.canonicalSequence == null || incoming.canonicalSequence == existing.canonicalSequence)
        }

        val seqDecision = checkStreamSequence(lastSeq, request.streamSequence, isDuplicate)
        val decision = if (seqDecision != StreamOrderDecision.ACCEPT) {
            seqDecision
        } else {
            evaluateExistingUpsertDecision(existing, request, isDuplicate)
        }

        if (decision != StreamOrderDecision.ACCEPT) {
            if (decision == StreamOrderDecision.DUPLICATE && request.streamSequence != null) {
                ledger.lastStreamSequence[resolvedId] = request.streamSequence
            }
            recordIgnored(decision, counters)
            return
        }

        val merged = incoming.copy(
            source = HistoryEntrySource.STREAMED,
            canonicalSequence = incoming.canonicalSequence ?: existing.canonicalSequence,
            timestampEpochMillis = incoming.timestampEpochMillis ?: existing.timestampEpochMillis,
        )
        ledger.entriesByIdentity[resolvedId] = merged
        ledger.identitiesByValue[resolvedId.value] = resolvedId
        ledger.repositionIfOutOfOrder(resolvedId, merged)
        if (request.streamSequence != null) {
            ledger.lastStreamSequence[resolvedId] = request.streamSequence
        }
    }

    private fun evaluateExistingUpsertDecision(
        existing: CanonicalHistoryEntry,
        request: StreamUpsertRequest,
        isDuplicate: Boolean,
    ): StreamOrderDecision {
        val incoming = request.entry
        val isCanonical = existing.source == HistoryEntrySource.CANONICAL_HISTORY
        val isTimestampRegression = request.streamSequence == null &&
            existing.timestampEpochMillis != null &&
            incoming.timestampEpochMillis != null &&
            incoming.timestampEpochMillis < existing.timestampEpochMillis
        return when {
            isCanonical && incoming.isInterim -> StreamOrderDecision.OUT_OF_ORDER
            isDuplicate -> StreamOrderDecision.DUPLICATE
            isCanonical && !request.allowCanonicalUpdateFromStream -> StreamOrderDecision.OUT_OF_ORDER
            !existing.isInterim && incoming.isInterim -> StreamOrderDecision.OUT_OF_ORDER
            isTimestampRegression -> StreamOrderDecision.OUT_OF_ORDER
            else -> StreamOrderDecision.ACCEPT
        }
    }

    private fun applyStreamDelete(
        ledger: HistoryLedger,
        row: StreamingHistoryRow,
        counters: ReconciliationCounters,
    ) {
        val rawIdentity = row.resolvedIdentity
        var resolvedId = ledger.resolveAlias(rawIdentity)
        if (!ledger.entriesByIdentity.containsKey(resolvedId) && row.entry != null) {
            val canonicalMatch = ledger.findMatchingCanonicalIdentity(row.entry, resolvedId)
            if (canonicalMatch != null) {
                ledger.recordAlias(resolvedId, canonicalMatch)
                resolvedId = canonicalMatch
            }
        }

        val lastSeq = ledger.lastStreamSequence[resolvedId]
        val alreadyDeleted = resolvedId in ledger.deletedIdentities &&
            !ledger.entriesByIdentity.containsKey(resolvedId)
        val seqDecision = checkStreamSequence(lastSeq, row.streamSequence, isDuplicateContent = alreadyDeleted)
        if (seqDecision != StreamOrderDecision.ACCEPT) {
            recordIgnored(seqDecision, counters)
            return
        }

        val removed = ledger.entriesByIdentity.remove(resolvedId)
        if (removed != null) {
            ledger.orderedIdentities.remove(resolvedId)
            ledger.deletedIdentities.add(resolvedId)
        } else if (alreadyDeleted) {
            counters.ignoredDuplicates++
        }
        if (row.streamSequence != null) {
            ledger.lastStreamSequence[resolvedId] = row.streamSequence
        }
    }

    private fun checkStreamSequence(
        lastSeq: Long?,
        incomingSeq: Long?,
        isDuplicateContent: Boolean,
    ): StreamOrderDecision {
        return when {
            incomingSeq == null || lastSeq == null -> StreamOrderDecision.ACCEPT
            incomingSeq < lastSeq -> StreamOrderDecision.OUT_OF_ORDER
            incomingSeq == lastSeq -> if (isDuplicateContent) {
                StreamOrderDecision.DUPLICATE
            } else {
                StreamOrderDecision.OUT_OF_ORDER
            }
            else -> StreamOrderDecision.ACCEPT
        }
    }

    private fun recordIgnored(decision: StreamOrderDecision, counters: ReconciliationCounters) {
        when (decision) {
            StreamOrderDecision.DUPLICATE -> counters.ignoredDuplicates++
            StreamOrderDecision.OUT_OF_ORDER -> counters.ignoredOutOfOrder++
            StreamOrderDecision.ACCEPT -> Unit
        }
    }
}

private object HistoryDiffEngine {

    fun sliceSnapshot(
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

    fun computeDiff(
        before: List<Pair<HistoryEntryIdentity, CanonicalHistoryEntry>>,
        after: List<Pair<HistoryEntryIdentity, CanonicalHistoryEntry>>,
        aliases: Map<HistoryEntryIdentity, HistoryEntryIdentity>,
    ): HistoryReconciliationDiff {
        val beforeByEffectiveId = LinkedHashMap<HistoryEntryIdentity, IndexedBeforeEntry>(before.size)
        before.forEachIndexed { index, (beforeId, entry) ->
            val effectiveId = resolveAliasChain(beforeId, aliases)
            beforeByEffectiveId[effectiveId] = IndexedBeforeEntry(index, beforeId, entry)
        }
        val afterIds = after.map { it.first }.toSet()
        val changes = mutableListOf<HistoryReconciliationChange>()

        for ((effectiveId, prev) in beforeByEffectiveId) {
            if (effectiveId !in afterIds) {
                changes.add(
                    HistoryReconciliationChange(
                        kind = HistoryChangeKind.DELETE,
                        identity = effectiveId,
                        previousIdentity = prev.originalId.takeIf { it != effectiveId },
                        previous = prev.entry,
                        current = null,
                        previousIndex = prev.index,
                        currentIndex = null,
                    ),
                )
            }
        }

        after.forEachIndexed { afterIndex, (afterId, afterEntry) ->
            val prev = beforeByEffectiveId[afterId]
            if (prev == null) {
                changes.add(
                    HistoryReconciliationChange(
                        kind = HistoryChangeKind.INSERT,
                        identity = afterId,
                        previousIdentity = null,
                        previous = null,
                        current = afterEntry,
                        previousIndex = null,
                        currentIndex = afterIndex,
                    ),
                )
            } else if (hasSemanticChange(prev.originalId, afterId, prev.entry, afterEntry)) {
                changes.add(
                    HistoryReconciliationChange(
                        kind = HistoryChangeKind.UPDATE,
                        identity = afterId,
                        previousIdentity = prev.originalId.takeIf { it != afterId },
                        previous = prev.entry,
                        current = afterEntry,
                        previousIndex = prev.index,
                        currentIndex = afterIndex,
                    ),
                )
            }
        }

        return HistoryReconciliationDiff(changes)
    }

    private fun hasSemanticChange(
        beforeId: HistoryEntryIdentity,
        afterId: HistoryEntryIdentity,
        beforeEntry: CanonicalHistoryEntry,
        afterEntry: CanonicalHistoryEntry,
    ): Boolean {
        return beforeId != afterId ||
            beforeEntry.source != afterEntry.source ||
            beforeEntry.isInterim != afterEntry.isInterim ||
            !sameDurableFields(beforeEntry, afterEntry)
    }

    fun sameDurableFields(
        first: CanonicalHistoryEntry,
        second: CanonicalHistoryEntry,
    ): Boolean {
        return first.durableSessionId == second.durableSessionId &&
            first.role == second.role &&
            first.kind == second.kind &&
            first.content == second.content &&
            first.canonicalKey == second.canonicalKey &&
            first.canonicalSequence == second.canonicalSequence &&
            first.toolCallId == second.toolCallId &&
            first.canonicalAttributes == second.canonicalAttributes &&
            first.timestampEpochMillis == second.timestampEpochMillis
    }

    fun resolveAliasChain(
        identity: HistoryEntryIdentity,
        aliases: Map<HistoryEntryIdentity, HistoryEntryIdentity>,
    ): HistoryEntryIdentity {
        var current = identity
        val visited = mutableSetOf<HistoryEntryIdentity>()
        while (visited.add(current)) {
            val next = aliases[current] ?: break
            current = next
        }
        return current
    }

    private data class IndexedBeforeEntry(
        val index: Int,
        val originalId: HistoryEntryIdentity,
        val entry: CanonicalHistoryEntry,
    )
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
