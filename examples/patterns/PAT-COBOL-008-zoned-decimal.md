---
match: PIC S9\([0-9]+\)V99(?! +COMP)
---
Zoned-decimal DISPLAY amounts (overpunched sign) are a wire format: parse at the
boundary into BigDecimal, serialize back only at the edge. Trap: sign overpunch
conventions differ (EBCDIC { A-I / } J-R); never let the raw bytes leak inland.
