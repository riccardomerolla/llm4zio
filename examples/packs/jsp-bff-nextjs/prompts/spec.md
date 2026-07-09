Write one behavioural spec per screen/servlet cluster as Markdown with exactly these
sections:

# <Screen or flow> — <one-line purpose>

## Overview
What the user does here, entry url-pattern(s), where it navigates.

## Screens
What each page shows, forms and their fields (including hidden fields), links.

## Business rules
Numbered (R1, R2, ...), in the order the servlet applies them: validation rules with
their EXACT error message texts, thresholds (with boundary semantics), confirmation
steps, and what each successful action writes (table, columns, values).

## BFF API contract
The endpoints the BFF exposes to the SPA for this flow (method, path,
request/response shape, error responses carrying the rule's message), and which
rules the BFF enforces authoritatively.

## Session & state
What the legacy app kept in session/hidden fields, and whether it becomes BFF
session state or SPA client state.

## Open questions
Anything ambiguous in the source. Empty if none.
