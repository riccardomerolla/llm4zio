Write one behavioural spec per screen/servlet cluster as Markdown with exactly these
sections:

# <Screen or flow> — <one-line purpose>

## Overview
What the user does here, entry url-pattern(s), where it navigates.

## Screens
What each page shows (fields, per-row rendering, status label mappings), forms and
their fields (including hidden fields), links.

## Business rules
Numbered (R1, R2, ...), in the order the servlet applies them: validation rules with
their EXACT error message texts, thresholds (with boundary semantics), confirmation
steps, and what each successful action writes (table, columns, values).

## Session & state
What lives in the session, what rides in hidden fields, timeout behaviour.

## Expected API contract
The endpoints a client-only SPA needs to replace the servlet behaviour (method,
path, request/response shape) — derived from the data access, not invented UX.

## Open questions
Anything ambiguous in the source. Empty if none.
