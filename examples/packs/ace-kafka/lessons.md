- ESQL routing ladders are business policy tables in disguise: extract them into the
  spec AS TABLES and keep them as declarative data in the target service — once they
  dissolve into nested ifs, nobody can verify them against the source again.
- MQ failure terminals and reject queues are part of the contract: every failure
  path in the msgflow needs a spec rule and a scenario, or it silently disappears in
  the port.
