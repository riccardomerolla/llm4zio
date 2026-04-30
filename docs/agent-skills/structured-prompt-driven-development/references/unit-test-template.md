# Unit Test Generation Template

Unit tests are generated **from** the Canvas, not invented from scratch. Every Operation, every Safeguard, every numeric AC must have at least one test. Tests are *deduplicated* against the existing suite — if a scenario is already covered, do not regenerate it.

The pipeline is two-step:

1. Build a **Test Prompt** from `(Canvas Operation, existing test inventory)`.
2. Generate test code from the Test Prompt.

## Test Prompt format

File-name: `[ID]-[YYYYMMDDHHMM]-[Test]-Title.md`.

```markdown
# [Test] <feature title>

**Canvas:** <link>
**Existing test inventory scanned:** <path patterns, last commit sha>

## op-001 calculate

### Scenarios in scope (deduplicated)

| ID | Kind | Description | Existing? |
|---|---|---|---|
| t-001 | normal | within-quota only | new |
| t-002 | boundary | exact-quota single token | new |
| t-003 | boundary | overage starts mid-event | new |
| t-004 | error | unknown model | new |
| t-005 | property | sum of (fromQuota + overage) == requested tokens | new |
| t-006 | safeguard SG-1 | replay same eventId twice = idempotent | new |
| t-007 | safeguard SG-2 | rounding: 333 tokens at $0.01/1K rounds HALF_UP | new |
| — | normal | quota fully consumed, fully overage | EXISTS at `BillingCalculatorSpec L142`, skip |

### Skeleton per scenario

For each scenario, the generator produces:

- **Arrange:** the entities and their starting state, lifted from the Canvas E section.
- **Act:** the Operation invocation, with literal values from the Story.
- **Assert:** the post-condition, including all fields from the AC's Then clause.

For property tests (`Kind: property`), the generator emits the invariant statement and a generator strategy (e.g. ZIO `Gen.int` ranges), not a parametrized example.
```

## Code generation language map

| Project signal | Test framework | Style |
|---|---|---|
| Scala 3 + ZIO | `zio-test`, `ZIOSpecDefault` | `test("…") { for { … } yield assertTrue(…) }` |
| TypeScript | `vitest` / `jest` | `it("…", async () => { … })` |
| Python | `pytest` | `def test_…(): …` |

The Canvas's Norms section pins the framework; do not pick one based on personal preference.

## Example: generated zio-test

```scala
object BillingCalculatorSpec extends ZIOSpecDefault:
  def spec = suite("BillingCalculator.calculate")(
    test("t-001 within-quota — all tokens billed at zero") {
      for
        result <- BillingCalculator.calculate(
                    UsageEvent("evt-1", "cust-1", 30_000, "fast-model"),
                    Plan.Standard,
                    UsageLedger.empty.withConsumed(90_000)
                  )
      yield assertTrue(
        result.fromQuota == 10_000,
        result.overage == 20_000,
        result.charged == BigDecimal("0.20")
      )
    },
    test("t-006 SG-1 replay — same eventId is idempotent") {
      for
        first  <- BillingCalculator.calculate(usage, plan, ledger0)
        ledger1 = ledger0.append(usage)
        second <- BillingCalculator.calculate(usage, plan, ledger1)
      yield assertTrue(first == second, ledger1.totalCharged == first.charged)
    }
  )
```

## Deduplication rule

A scenario is "already covered" iff:
- An existing test asserts the **same post-conditions** on the **same Operation** with **equivalent inputs** (same partition).
- Equivalence is by behaviour, not by literal values: a test for "30k tokens at quota=10k remaining" and one for "300k at quota=100k remaining" are the **same partition** unless the Canvas calls out a separate scale boundary.

When in doubt, **skip generating** and add a comment in the Test Prompt: `# t-NNN potentially covered by <path:line>; verify before adding`.

## Pass criteria for the gate

- Every Canvas Operation has ≥ 1 normal + 1 error test in either the new tests or the existing suite (with citation).
- Every numeric Safeguard has at least one negative test that proves the invariant cannot be violated.
- All tests pass on first run; flakes block the gate.
