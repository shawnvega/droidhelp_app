# Fencing Contract: `SignIn` / `Owner` / `SocketOwner`

This document defines the generation/epoch fencing contract used to prevent stale asynchronous operations from mutating state or sending traffic across account, workspace, or WebSocket lifecycle transitions.

## 1. Ownership Hierarchy

Defined in `com.bighelp.droidhelp.transport.Owners`:

- **`SignIn`**: Identifies an authenticated session (`canonicalEndpoint`, `verifiedPrincipal`, `authenticationGeneration`).
- **`Owner`**: Identifies a workspace connection scoped to a `SignIn` (`signIn`, `workspaceConnectionGeneration`).
- **`SocketOwner`**: Identifies a WebSocket connection scoped to an `Owner` (`owner`, `socketEpoch`).

Transitions at any level invalidate operations captured under an earlier generation or epoch at that level or below.

## 2. Fencing Protocol

Asynchronous operations that depend on the active session, workspace, or socket must follow capture-and-validate fencing:

1. **Pre-flight capture**: Before starting an async operation or enqueueing outbound work, capture the current `Owner` or `SocketOwner`.
2. **Post-flight validation**: Before sending over a socket, persisting rotated credentials, or committing state mutations, validate the captured value against the current active value via `Owners.validateOwner(...)` or `Owners.validateSocketOwner(...)`.
3. **Abort on mismatch**: If the captured and current owners differ, abort the operation and throw `StaleOwnerException` rather than applying stale results.

## 3. Scope & Current Status

- **Transport (`transport/WebSocketTransport.kt`)**: Outbound messages are wrapped in `FencedMessage` with the captured `SocketOwner` and validated via `owners.validateSocketOwner(...)` before transmission.
- **Authentication & Session (`platform/`, `session/`)**: Intended to fence credential rotation, sign-in state updates, and workspace session mutations using `SignIn` and `Owner` (not yet wired into `Auth` or `SessionCoordinator`).
