---
match: COMP-3|COMPUTE .* ROUNDED
---
COBOL packed-decimal money (COMP-3) maps to BigDecimal with scale 2; COMPUTE ROUNDED
is RoundingMode.HALF_UP. Never float/double for amounts, rates, or balances; scale is
part of the contract (S9(5)V99 means exactly 2 decimals). Trap: intermediate COMPUTE
results keep COBOL truncation rules — round only where the source says ROUNDED.
