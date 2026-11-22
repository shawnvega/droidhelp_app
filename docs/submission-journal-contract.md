# Submission Journal Contract

This document defines the contract for the `SubmissionJournal`, which ensures that user prompt submissions are durably recorded before being dispatched to the host.

## States and Transitions

The journal manages the lifecycle of a submission through the following states:

| State | Description |
| :--- | :--- |
| `PREPARED` | The submission intent has been recorded in the journal but not yet sent to the host. |
| `DISPATCHED` | The submission has been sent to the host and is awaiting a response. |
| `ACKNOWLEDGED` | The host has acknowledged the submission (e.g., via a response or receipt). |
| `UNCERTAIN` | A connection failure or error occurred during dispatch, leaving the submission status unknown. |

### Valid Transitions

- `PREPARED` $\rightarrow$ `DISPATCHED` (on successful RPC request initiation)
- `DISPATCHED` $\rightarrow$ `ACKNOWLEDGED` (on successful receipt of a response)
- `DISPATCHED` $\rightarrow$ `UNCERTAIN` (on connection failure or error during dispatch)

## Persistence and Flow

### Persistence Store
- **Android**: Uses a `VersionedDatabase` (SQLite-based) to store `Entry` objects.
- **iOS**: Uses a `DirectHermesDraftStore` (file-based) to store `Submission` objects.

### Dispatch/Ack Flow
1. **Prepare**: A new entry is created with state `PREPARED` and persisted.
2. **Dispatch**: The entry state is updated to `DISPATCHED` and the RPC request is initiated.
3. **Acknowledge**: Upon receiving a successful response from the host, the entry state is updated to `ACKNOWLEDGED` and the entry is retired from the active journal.

### Uncertain Recovery
If a dispatch fails due to a connection issue or an unhandled error, the entry is marked as `UNCERTAIN`. This allows the system to attempt recovery (e.g., by re-syncing with the host's current state) before attempting a new submission.

## Parity Gaps (iOS vs. Android)

While both implementations follow the same core state machine, several functional differences exist:

1.  **Metadata Support**: The Android `Entry` includes a `metadata` map (used for storing reasons for `UNCERTAIN` states), whereas the iOS `Submission` model does not support metadata.
2.  **History Mechanism**: Android provides an explicit `appendHistory` method to create `HISTORY` state entries. iOS uses `replaceSubmission` to update existing entries (e.g., when expanding a command), which is a different mechanism.
3.  **Pruning**: The Android `Journal` interface includes a `prune` method for cleaning up old entries; the iOS implementation lacks an explicit pruning mechanism in the provided source.
4.  **State Representation**: Android's `Entry` explicitly stores the `State` enum, while the iOS implementation relies on the presence of the entry in the `unresolved` collection and manual tracking of the `dispatchedSubmissionID` and `admitted` flag.
