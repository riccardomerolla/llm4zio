Write one behavioural spec per message flow as Markdown with exactly these sections:

# <Flow> — <one-line purpose>

## Overview
What the flow does, its trigger (input queue), and where messages can end up.

## Interfaces
Every queue (name, direction, who is on the other end) and the message format on
each — PLUS the target event contract: a queue→topic mapping table (queue, topic
name, event key = the correlation identity, payload fields). Per-key ordering on
the key you name becomes part of the contract.

## Routing rules
Numbered (R1, R2, ...), in evaluation order: the predicate (exact values —
currencies, prefixes, thresholds with boundary semantics) and the destination.
Static routing tables reproduced AS TABLES.

## Validation & rejects
Each validation in order, its reject code, and the reject envelope structure.

## Message mappings
Field-by-field source→target mappings for each output (e.g. the CICS request built
from the inbound payment), naming the exact target fields.

## Thresholds & flags
Every amount threshold and the flag/value it sets (priority, regulatory reporting),
with inclusive/exclusive semantics.

## Open questions
Anything ambiguous in the source. Empty if none.
