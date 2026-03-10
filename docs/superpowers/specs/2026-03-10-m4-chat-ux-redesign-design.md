# M4 Chat & Workspace UX Redesign

**Date:** 2026-03-10
**Status:** Approved
**Milestone:** M4: Chat & Streaming

## Problem

The current chat detail view is functional but dense and utilitarian. Header badges are text-heavy, the Memory Search sidebar is always visible (consuming space), tool calls and git changes live in separate panels outside the conversation flow, and there is no progressive disclosure pattern. The UI needs to move closer to Claude Code and Codex app aesthetics: clean on first look, deep on interaction.

## Goals

1. **Clean surface** — minimal icons, badges, and whitespace instead of label buttons and dense panels
2. **Progressive disclosure** — simple first impression, click to go deeper
3. **Unified timeline** — non-conversational events (git, tools, reports) live in the conversation flow
4. **Dynamic sidebar** — context-aware right panel replaces fixed Memory Search
5. **Design system** — standardized Lit web components for consistency
6. **Keyboard-first** — command palette and shortcuts for power users

## Architecture

**Stack (unchanged):** Scalatags SSR + Tailwind CSS 4 (CDN) + HTMX 2.0 + Lit 3 Web Components + WebSocket

**New components (Phase 1):**
- `<ab-icon-button>` — icon button with tooltip, badge, variants
- `<ab-badge-select>` — pill/badge dropdown selector
- `<ab-side-panel>` — dynamic right panel with slide animation
- `<ab-timeline-event>` — compact non-conversational event card

**Modified views (Phase 2):**
- `ChatView.scala` — header, message rendering, sidebar, composer
- `chat-message-stream.js` — timeline integration, activity indicator
- `message-composer.js` — minimal redesign

## Phases

### Phase 1: Design System Foundation (4 issues)
Build the reusable Lit components that enable all subsequent work.

### Phase 2: Chat Detail View Redesign (5 issues)
Apply the new components to transform the chat view: icon toolbar, dynamic sidebar, unified timeline, cleaner messages, minimal composer.

### Phase 3: Progressive Disclosure & Polish (5 issues)
Add depth features: command palette, context window gauge, tool call waterfall, inline git diffs, thread list summary cards.

### Phase 4: Advanced Enhancements (4 issues)
Power user features: agent activity indicator, keyboard shortcuts, conversation search, branching UI.

## Issue Summary

| # | Title | Phase | Files |
|---|-------|-------|-------|
| 1 | `<ab-icon-button>` component | 1 | `ab-icon-button.js` |
| 2 | `<ab-badge-select>` component | 1 | `ab-badge-select.js` |
| 3 | `<ab-side-panel>` component | 1 | `ab-side-panel.js` |
| 4 | `<ab-timeline-event>` component | 1 | `ab-timeline-event.js` |
| 5 | Chat header icon toolbar + badge selectors | 2 | `ChatView.scala` |
| 6 | Dynamic context sidebar | 2 | `ChatView.scala`, Layout |
| 7 | Unified conversation timeline | 2 | `ChatView.scala`, `chat-message-stream.js` |
| 8 | Cleaner message bubbles | 2 | `ChatView.scala` |
| 9 | Chat composer redesign | 2 | `ChatView.scala`, `message-composer.js` |
| 10 | Command palette (Ctrl+K) | 3 | `ab-command-palette.js` |
| 11 | Context window gauge | 3 | `ChatView.scala`, `chat-message-stream.js` |
| 12 | Tool call waterfall | 3 | `ab-tool-waterfall.js` |
| 13 | Git change summary in timeline | 3 | `ChatView.scala`, `git-panel.js` |
| 14 | Thread list summary cards | 3 | `ChatView.scala`, `Layout.scala` |
| 15 | Agent activity indicator | 4 | `chat-message-stream.js` |
| 16 | Keyboard shortcuts system | 4 | `ab-keyboard-shortcuts.js` |
| 17 | Conversation search | 4 | `ChatController.scala`, search UI |
| 18 | Conversation branching UI | 4 | `ChatView.scala`, `ab-branch-point.js` |

## Dependencies

- Issues 5-9 depend on Issues 1-4 (design system components)
- Issue 11 connects to existing M4 issues #325 and #332
- Issue 18 connects to existing M4 issue #333
- Issues 10, 15, 16 are independent and can be done in any order
