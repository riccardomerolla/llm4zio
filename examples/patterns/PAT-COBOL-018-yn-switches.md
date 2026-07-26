---
match: PIC X\(0?1\).*VALUE '[YN]'
---
X(1) Y/N switches are booleans with domain names: WS-OD-TRIGGERED → boolean
odFeeDue. Keep the original flag name in a comment or mapping doc for traceability.
Trap: some are tri-state in practice (space = unknown) — check every writer first.
