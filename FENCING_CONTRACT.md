# Fencing Contract: SignIn / Owner / SocketOwner

## 1. Definitions

- **SignIn**: A unique identifier for an authenticated session, composed of the `canonicalEndpoint`, the `verifiedPrincipal`, and the `authenticationGeneration`. It ensures that all subsequent operations are tied to a specific instance of authentication.
- **Owner**: A unique identifier for a workspace connection, composed of the `SignIn` and the `workspaceConnectionGeneration`. It ensures that all operations are tied to a specific instance of a workspace connection.
- **SocketOwner**: A unique identifier for a WebSocket connection, composed of the `Owner` and the `socketEpoch`. It ensures that all operations are tied to a specific instance of a socket.

## 2. Fencing Requirements

Fencing must be applied to all asynchronous operations that depend on the current state of the session, workspace, or socket. This includes:

- **Sign-in/Authentication Operations**: Rotating credentials, updating the sign-in state, or any operation that modifies the `SignIn` scope.
- **Workspace Operations**: Creating new workspace connections, modifying workspace settings, or any operation that changes the `Owner` generation.
- **Socket Operations**: Sending messages through the transport, receiving responses, or any operation that depends on the current `SocketOwner` epoch.

## 3. Staleness Detection

Staleness is detected by comparing the `capturedOwner` (the owner captured at the start of an asynchronous operation) with the `currentOwner` (the active owner at the time the operation completes or is about to publish its result).

- For **Sign-in/Authentication** and **Workspace** operations: An operation is stale if `capturedOwner != currentOwner`.
- For **Socket** operations: An operation is stale if `capturedSocketOwner != currentSocketOwner`.

## 4. Expected Behavior & Exceptions

- **Pre-flight Check**: Before initiating a network request or any long-running asynchronous task, the agent must capture the current `Owner` (or `SocketOwner`) and store it as the `capturedOwner`.
- **Post-flight Validation**: Before publishing a result, saving rotated credentials, or committing a mutation, the agent must verify that `capturedOwner == currentOwner`.
- **Failure Handling**: If a mismatch is detected, the operation must be aborted. The agent should not attempt to apply the result of a stale operation to the current state.
- **Exceptions**: Implementations should throw a `StaleOwnerException` (or similar) when a fencing violation is detected during the validation step.

## 5. Acceptance Criteria

- [ ] All asynchronous operations capture the current `Owner`/`SocketOwner` before execution.
- [ ] All mutations/credential updates validate the `capturedOwner` against the `currentOwner` before completion.
- [ ] A mismatch between `capturedOwner` and `currentOwner` results in the operation being aborted and an error being reported.
- [ ] A new `SocketOwner` (new epoch) correctly fences out all pending requests from the previous socket.
- [ ] A new `Owner` (new generation) correctly fences out all pending requests from the previous workspace connection.
