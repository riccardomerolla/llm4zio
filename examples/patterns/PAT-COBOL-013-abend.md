---
match: ABEND|ILBOABN0|RETURN-CODE
---
ABEND U-codes and RETURN-CODE are process-exit contract with the scheduler: map to
typed exceptions caught at the entrypoint that set the process exit status. Keep the
distinction: expected rejects are data, ABENDs are crashes.
