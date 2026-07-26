---
match: ACCUM|WORKING-STORAGE
---
Per-account working-storage accumulators (daily transfer totals) become keyed state
stores: the accumulator is state keyed by the event key, updated transactionally
with the emit. Reset jobs (EOD accumulator reset) become punctuator/window
lifecycle, named in the spec.
