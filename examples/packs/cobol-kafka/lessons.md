- COBOL COMP-3 (packed decimal) money fields are exact decimals: port them as
  BigDecimal with explicit scale 2 and RoundingMode.HALF_UP (COBOL COMPUTE ROUNDED);
  never float/double.
