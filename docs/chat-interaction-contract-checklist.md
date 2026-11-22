# iOS Chat Interaction Contract Checklist

## 1. Transcript Identity & Deduping
- Use semantic `ChatCanvasRow.id` combined with conversation and model-owner identity.
- Avoid using array positions, fresh UUIDs, or streaming text as identity.
- When changing agents, clear existing draft/submission records for that host/profile to prevent cross-agent interference.

## 2. Message Ordering
- Preserve request/session/agent validation, ordering, reconciliation, and terminal results.
- For growing responses, follow the actual laid-out tail continuously without replaying animations or jumping over tools.

## 3. Hydration
- Project work-trail headers and individual tool events as sibling native rows.
- Completed-turn folds and running turn blocks retain disclosure headers and project expanded contents separately.
- Use `ChatNativeTextStorageUpdater` to compare canonical text and edit the changed suffix while preserving the unchanged prefix.

## 4. Grouping
- Project work-trail headers and individual tool events as sibling native rows (grouping tools within a turn).

## 5. Scroll Position & Anchoring
- Follow the laid-out tail continuously during growth.
- Yield to reader interaction (dragging/deceleration) immediately; show newest rows once settled.
- Resume following when the reader reaches the bottom, taps "Return to Latest", or sends a message.

## 6. Draft Save/Restore Lifecycle
- Own explicit tool/fold choices in a disclosure store.
- Own clarification drafts by item, session, and request via `DashboardModel`.
- Write plain drafts to `ChatDraftJournal` on typing pauses; these are restored upon relaunch if they were seen by the journal.
- Handle "Sending..." state outside the transcript for messages waiting for connection.
- Drafts/submissions are stored as JSON files scoped by host/profile identity.
- When changing agents, remove all local draft/submission records for the current host/profile to ensure a clean state.
