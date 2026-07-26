---
match: ACCT
---
Rules scoped per account (limits, balances, validation order) require the account
id as the partition key: per-key ordering is the only ordering Kafka guarantees, and
it is exactly the ordering the batch had after its sort step. Repartitioning away
from the account key mid-topology breaks the contract.
