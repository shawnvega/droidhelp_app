# Fencing Contract: `SignIn` / `Owner` / `SocketOwner`

This document defines the generation/epoch fencing contract used to prevent stale asynchronous operations from mutating state or sending traffic across account, workspace, or WebSocket lifecycle transitions.

## 1. Ownership Hierarchy

Defined in `com.bighelp.droidhelp.transport.Owners`:

- **`SignIn`**: Identifies an authenticated session (`canonicalEndpoint`, `verifiedPrincipal`, `authenticationGeneration`). Identifiers are non-empty and compared verbatim without Unicode normalization, trimming, or case folding; `authenticationGeneration` is non-negative.
- **`Owner`**: Identifies a workspace connection scoped to a `SignIn` (`signIn`, `workspaceConnectionGeneration >= 0`).
- **`SocketOwner`**: Identifies a WebSocket connection scoped to an `Owner` (`owner`, `socketEpoch >= 0`).

Transitions at any level enforce strict parent matching and monotonic generation/epoch progression, and automatically invalidate lower ownership levels:

- **`updateSignIn`**: Re-asserting the exact active `SignIn` is an idempotent no-op. Transitioning to a newer `authenticationGeneration` or a different `(canonicalEndpoint, verifiedPrincipal)` clears the active `Owner` and `SocketOwner`. Regressive or previously retired `authenticationGeneration` values for a scope are rejected with `StaleOwnerException`.
- **`updateOwner`**: Requires an active matching `SignIn` and a strictly advancing `workspaceConnectionGeneration`, and clears any active `SocketOwner`.
- **`updateSocketOwner`**: Requires an active matching `Owner` (and `SignIn`) and a strictly advancing `socketEpoch` past the highest epoch observed for that `Owner`.
- **`incrementSocketEpoch` / `incrementSocketEpoch(expectedOwner)`**: Atomically advances `socketEpoch` past the highest issued or retired epoch for the active `Owner` (preventing epoch reuse/ABA races and overflow). The `expectedOwner` overload additionally verifies parent `Owner` identity under the same lock.
- **`retireSocketOwner` / `retireSignIn`**: Clears active ownership (optionally guarded by an expected current token so late callbacks from older sockets/sessions cannot retire newer ones) while retaining high-water marks so retired generations/epochs cannot be resurrected.

## 2. Fencing Protocol

Asynchronous operations that depend on the active session, workspace, or socket must follow capture-and-validate fencing:

1. **Pre-flight capture**: Before starting an async operation or enqueueing outbound work, capture the current `SignIn`, `Owner`, or `SocketOwner`.
2. **Post-flight validation**: Before sending over a socket, persisting rotated credentials, or committing state mutations, validate the captured value against the current active value via `Owners.validateSignIn(...)`, `Owners.validateOwner(...)`, `Owners.validateSocketOwner(...)`, or `Owners.withSocketOwner(...)`.
3. **Abort on mismatch**: Validation rejects `null` captures, absent active owners, and any hierarchy/generation/epoch mismatch by throwing `StaleOwnerException` rather than applying stale results. `withSocketOwner` re-validates after both normal completion and non-cancellation failures while preserving `CancellationException`.

## 3. Scope & Current Status

- **Transport (`transport/WebSocketTransport.kt`, `transport/WebSocketOutboundPipeline.kt`, `transport/ReconnectState.kt`)**: Connection handshakes capture the active `Owner` and atomically advance the socket epoch via `owners.incrementSocketEpoch(capturedOwner)` on open (rejecting stale handshakes). Outbound messages are wrapped in `FencedMessage` with the captured `SocketOwner` and validated via `owners.validateSocketOwner(...)` before transmission. Socket generation retirement in `ReconnectState` atomically advances `Owners.incrementSocketEpoch()`.
- **Authentication & Session (`platform/`, `session/`)**: Intended to fence credential rotation, sign-in state updates, and workspace session mutations using `SignIn` and `Owner` (not yet wired into `Auth` or `SessionCoordinator`).
