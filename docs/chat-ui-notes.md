# iOS Chat UI: Streaming, Tools, and Reasoning

This document outlines the data models and UI implementation for chat streaming, tool calls, and reasoning blocks in the iOS client, based on the `bighelp` reference implementation.

## 1. Data Models

The core model for all activity within a chat turn is `ChatActivityEvent` (`ChatActivityEvent.swift`).

### Chat Activity Kinds (`ChatActivityKind`)
- **Reasoning (`.reasoning`)**: Represents the model's internal thought process. Text is accessed via the `reasoningText` computed property, which checks `detail` and `summary`.
- **Tool Call (`.tool`)**: Represents an execution of a tool.
    - `toolCallID`: Unique identifier for the specific call.
    - `toolName`: The canonical name of the tool (e.g., `terminal`, `skill_view`).
    - `arguments`: JSON string containing the tool's input parameters.
    - `result`: JSON string containing the tool's output.
- **Subagent (`.subagent`)**: Represents work delegated to another agent, identified by `subagentID`.
- **Bot Handoff (`.botHandoff`)**: Represents a transition between agents, using `botRunID` and `memberID`.

### Lifecycle and Visual State (`ChatActivityLifecycle`)
Events transition through states: `.running`, `.succeeded`, `.failed`, `.cancelled`, and `.recorded`.
The `ChatActivityVisualState` maps these lifecycles to a `ChatActivityVisualTone` (`.neutral`, `.success`, `.failure`, `.secondary`), which dictates the color of the activity indicator/label.

## s2. Streaming and Auto-Scrolling

### Segment Projection
The `ChatActivityTurnPresentation` (`ChatActivityTurnView.swift`) transforms a flat list of `ChatActivityEvent`s into a structured list of `Segment`s. This grouping logic handles:
- **Thinking Segments**: Consecutive `.reasoning` events are grouped into a single `.thinking([ChatActivityEvent])` segment.
- **Work Trail**: Ordinary message events are collected into a `ChatActivityTurn` (the "work trail").

### Live Updates
- A turn is considered "live" if it contains events with a `.running` lifecycle.
- The `ChatWorkTrailCard` (used within `ChatActivityTurnView`) manages the appending of new tokens/events and handles auto-scrolling to the bottom of the active work trail.

## 3. Tool Call Rendering

Tool calls are rendered via `ChatActivityRow` (`ChatActivityRow.swift`) and can be expanded/collapsed.

### Collapsed View
- **Title**: Displays `presentationTitle` (e.g., "Running a command..." or "Ran a command").
- **Detail**: Shows `presentationDetail` (e.g., the executable name for `terminal` tools).
- **Summary**: Optionally shows the `summary` field.
- **Accessibility**: Provides a descriptive label including the action, detail, summary, and current status.

### Expanded View (`toolDetails`)
When expanded, the view displays:
- **Code Cells**: If a `contentReference` is present, a `ChatToolCodeCellView` is shown (representing code executed in a notebook-style cell).
- **Tool Block**: A `ChatToolBlockView` displaying the tool's `arguments` (input) and `result` (output).

## 4. Reasoning Block Styling

Reasoning is rendered using `ChatThinkingRow` (`ChatActivityRow.swift`).

- **Visual Identity**: Uses `BighelpActivityRow` with a specific phase and title.
- **Dynamic Title**: The title adapts based on the lifecycle: "Thinking" while `.running`, or "Thought for [duration]" once completed.
s- It is designed to be a "quiet" part of the transcript, similar to interim messages.
- **Persistence**: Users can configure whether thinking blocks stay open by default via `ChatReasoningPlacement`.
