---
match: ADD 1 TO WS-.*(COUNT|CNT)
---
Run counters and totals (read/posted/rejected/fees) are the batch run report: map to
an immutable RunSummary the use case returns, not logging. The summary is observable
behaviour when the source DISPLAYs it — keep field-for-field parity.
