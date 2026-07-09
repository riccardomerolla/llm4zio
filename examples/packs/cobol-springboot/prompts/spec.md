Write one behavioural spec per COBOL program as Markdown with exactly these sections:

# <PROGRAM> — <one-line purpose>

## Overview
What the program does, when it runs (from the JCL), and its inputs/outputs (files,
tables).

## Business rules
A numbered list (R1, R2, ...) of every rule, in the order the code applies them.
Each rule states its exact values (amounts, rates, codes) and its outcome. Validation
rules state what is checked, in what order, and which reject reason code fires.

## Error handling
Reject reason codes with trigger conditions; database error behaviour (SQLCODE
handling, rollback, abend code, return code).

## Data access
Tables read/written, per operation, including commit frequency.

## Orchestration
The JCL steps that run this program: order, condition codes, restart notes.

## Open questions
Anything ambiguous in the source. Empty section if none.

Rules must be source-grounded: every number, code, and ordering comes from the code,
not from banking domain knowledge.
