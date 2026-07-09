# Meridian Savings Bank — ESB integration slice (ACE / IIB)

The integration layer of the Meridian estate: an IBM App Connect Enterprise
(formerly Integration Bus) message flow that sits on the ESB between the
channel front ends (branch teller, web banking) and the mainframe. As with
the rest of the fixture, the business rules live **only in the code** —
here, the ESQL. This README stays at inventory level.

## Source inventory

```
ace/
  PaymentRouting.msgflow   the flow: 1 MQ input, 2 compute nodes, 3 MQ outputs
  PaymentRouting.esql      ESQL modules backing the two compute nodes
```

Deployed from BAR `MERID_ESB_PAYMENTS.bar` to broker `MERIDBRK`,
execution group `PAYHUB`. The BAR and broker config are not retained in
this extract.

## Message flow: PaymentRouting

| Node | Type | Detail |
|------|------|--------|
| Payment Input | MQInput | reads `MERID.PAY.IN`, XMLNSC domain |
| Validate Payment | Compute | module `ValidatePayment_Compute` |
| Route Payment | Compute | module `RoutePayment_Compute` |
| Mainframe Request | MQOutput | writes `MERID.CICS.REQ` |
| SEPA Output | MQOutput | writes `MERID.SEPA.OUT` |
| Reject Output | MQOutput | writes `MERID.PAY.REJ` |

Wiring: input → validate → route; validate and route each also feed the
reject leg (route additionally wires its failure terminal there).

## Queues

| Queue | Direction | Purpose |
|-------|-----------|---------|
| `MERID.PAY.IN` | input | channel payment requests (branch, web) |
| `MERID.CICS.REQ` | output | CICS bridge — internal transfers, `XFER_REQUEST` layout (see `cobol/copybooks/XFERREC.cpy`) |
| `MERID.SEPA.OUT` | output | SEPA gateway leg |
| `MERID.PAY.REJ` | output | rejected payments, reject envelope with reason code |
| `MERID.CICS.RESP` | reply-to | set as `ReplyToQ` on CICS-bound requests; not read by this flow |

## ESQL modules

| Module | Node |
|--------|------|
| `ValidatePayment_Compute` | Validate Payment |
| `RoutePayment_Compute` | Route Payment |

Schema-level items in `PaymentRouting.esql`: helper function
`IsWellFormedIban`, shared row `RoutingTable`, shared counter `XferSeqNo`.
