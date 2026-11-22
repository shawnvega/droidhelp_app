# Home Shell and Tab Structure Specification

This document describes the home shell and tab structure for both iOS and Android platforms, ensuring alignment between the two.

## 1. Tab Order, Labels, and Symbols

The home screen consists of three primary tabs.

### iOS (SwiftUI)
Tabs are defined by `HomeTab` and represented by `HomeTabItem`.

| Tab | Label (Localized) | Unselected Symbol | Selected Symbol |
| :--- | :--- | :--- | :--- |
| `.conversations` | Depends on `HomeConversationsView` | `bubble.left.and.bubble.right` (Bots) or `bubble.left` (Chats) | `.fill` version of above |
| `.today` | "Today" | `sun.max` | `sun.max.fill` |
| `.work` | "Work" | `hammer` | `hammer.fill` |

**Note:** The `.today` tab can display a small dot (`showsDot`) if `todayHasNew` is true, indicating new content.

### Android (Kotlin)
Tabs are defined by `HomeTab`.

| Tab | Label |
| :--- | :--- |
| `CONVERSATIONS` | (Determined by `HomeConversationsView`) |
| `TODAY` | "Today" |
| `WORK` | "Work" |

---

## 2. Default Launch Behavior

The app uses a `land(on:view:)` method in `HomeSelection` to initialize the starting state.

### iOS (`HomeSelection.swift`)
`func land(on landing: HomeLanding, view defaultView: HomeConversationsView)`

- `.bots`: Sets tab to `.conversations` and view to `.bots`.
- `.chats`: Sets tab to `.conversations` and view to `.chats`.
- `.today`: Sets tab to `.today` and view to `defaultView`.
- `.work`: Sets tab to `.work` and view to `defaultView`.

### Android (`HomeSelection.kt`)
`fun land(landing: Landing, defaultView: HomeConversationsView)`

- `Landing.BOTS`: Sets tab to `HomeTab.CONVERSATIONS` and view to `HomeConversationsView.BOTS`.
- `Landing.CHATS`: Sets tab to `HomeTab.CONVERSATIONS` and view to `HomeConversationsView.CHATS`.
- `Landing.TODAY`: Sets tab to `HomeTab.TODAY` and view to `defaultView`.
- `Landing.WORK`: Sets tab to `HomeTab.WORK` and view to `defaultView`.

---

## 3. Tab Bar Behavior and Layout

### Layout (`HomeTabBar.swift`)
- **Structure**: An `HStack` containing a `NavigationRowLayout` for the tabs and a `RootComposeButton` for creating a new chat.
- **Visuals**: The tab bar is styled as a glass capsule (`bighelpNavigationGlass(in: Capsule())`).
- **Theming**:
    - **Unselected**: Uses `theme.secondaryText`.
    - **Selected**: Uses `theme.actionForeground` for text/icon and a `Capsule` filled with `theme.action` as a background.
- **Interaction**: Tapping a tab dismisses the keyboard and updates the selection with a `.snappy` animation (unless `reduceMotion` is enabled).

### New Chat Button
- **Identifier**: `home.new-chat`
- **Symbol**: `square.and.pencil`
- **Position**: Placed at the end of the `HStack`.

---

## 4. Platform Alignment Summary

Both platforms implement a consistent three-tab architecture:
1.  **Conversations**: The primary entry point, which can be toggled between "Bots" and "Chats".
2.  **Today**: A destination for new/relevant items.
3.  **Work**: A destination for ongoing tasks.

The `HomeSelection` class on both platforms acts as the source of truth for the current `tab` and the specific `view` within the first tab.
