# Benchmark — cobol-springboot @ d9755887

_Runs: claude ×1, codex ×1 · Judges: self-graded — scores NOT cross-comparable_

## Headline

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Outcome | completed | completed |  |
| Total time | **4.2h ✅** | 4.5h ⚠️ | +19.0m (+7.6%) |
| Total tokens | **1.1M ✅** | 6.0M ⚠️ | +5.0M (+457.0%) |
| Output tokens | **58.7k ✅** | 177.7k ⚠️ | +118.9k (+202.5%) |
| Cached reads | 1.0M | 0 |  |
| Self-healing actions | 0 | 0 |  |
| Gate cleared | **yes ✅** | no ⚠️ | +1 (+100.0%) |
| Gate rounds | **1 ✅** | 3 ⚠️ | +2 (+200.0%) |
| Build passed | yes | yes |  |
| Tests passed | 65 ⚠️ | **121 ✅** | +56 (+46.3%) |
| Coverage % | 100.0% | 100.0% |  |
| Judge score | 95.5% | 86.4% |  |
| Est. cost (USD) | 4.41 | 34.63* |  |

## Phases

### Estate

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Duration | 0.1s ⚠️ | **0.1s ✅** | +0.0s (+9.7%) |
| Tokens | 0 | 0 |  |
| Self-healing actions | 0 | 0 |  |
| Est. cost (USD) | – | – |  |

### Extract

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Duration | **14.2m ✅** | 25.1m ⚠️ | +10.9m (+77.1%) |
| Tokens | **1.1M ✅** | 2.1M ⚠️ | +1.0M (+93.9%) |
| Self-healing actions | 0 | 0 |  |
| Est. cost (USD) | 4.41 | 12.48* |  |

### Gate

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Duration | **112.7s ✅** | 32.8m ⚠️ | +31.0m (+1648.5%) |
| Tokens | **0 ✅** | 3.7M ⚠️ | +3.7M |
| Self-healing actions | 0 | 0 |  |
| Est. cost (USD) | – | 20.88* |  |

### Plan

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Duration | 77.0s ⚠️ | **48.9s ✅** | +28.1s (+57.5%) |
| Tokens | **0 ✅** | 47.5k ⚠️ | +47.5k |
| Self-healing actions | 0 | 0 |  |
| Est. cost (USD) | – | 0.29* |  |

### Seed

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Duration | 0.1s ⚠️ | **0.1s ✅** | +0.0s (+1.6%) |
| Tokens | 0 | 0 |  |
| Self-healing actions | 0 | 0 |  |
| Est. cost (USD) | – | – |  |

### Implement

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Duration | **133.0m ✅** | 136.2m ⚠️ | +3.2m (+2.4%) |
| Tokens | 0 | 0 |  |
| Self-healing actions | 0 | 0 |  |
| Est. cost (USD) | – | – |  |

### Verify

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Duration | 8.5s ⚠️ | **6.0s ✅** | +2.5s (+41.3%) |
| Tokens | 0 | 0 |  |
| Self-healing actions | 0 | 0 |  |
| Est. cost (USD) | – | – |  |

### Score

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Duration | **68.7s ✅** | 2.0m ⚠️ | +52.1s (+75.8%) |
| Tokens | **0 ✅** | 183.4k ⚠️ | +183.4k |
| Self-healing actions | 0 | 0 |  |
| Est. cost (USD) | – | 0.99* |  |

## Quality

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Scenarios | 54 | 53 |  |
| Malformed features | 0 | 0 |  |
| Spec files | 3 | 3 |  |
| Java LOC | 1576 | 3887 |  |
| Test LOC | 987 | 4156 |  |
| Deterministic findings | 0 | 0 |  |
| Judge findings | 0 | 2 |  |

## Robustness

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Flaky retries | 0 | 0 |  |
| Transient retries | 0 | 0 |  |
| Auto-resumes | 0 | 0 |  |
| Turn-limit trips | 0 | 0 |  |
| Empty-response shrinks | 0 | 0 |  |
| Self-healing total | 0 | 0 |  |

## Projection @ 500 programs

| Metric | claude | codex | Gap (best→worst) |
| --- | --- | --- | --- |
| Tokens / program | 361.4k | 2.0M |  |
| Time / program | 83.1m | 89.4m |  |
| Est. cost / program (USD) | 1.47 | 11.54 |  |
| Projected tokens | 180.7M | 1006.4M |  |
| Projected time | 692.4h | 745.2h |  |
| Projected est. cost (USD) | 734.38 | 5772.46 |  |

_Linear extrapolation from the fixture — assumes no context growth, no quota ceilings, serial execution._

_\* Cost estimated at report time from recorded tokens × the requested model (no run-time estimate was stored; mixed-model phases are approximated at the requested model's rate)._

_Machines: Riccardos-MacBook-Pro.local — Mac OS X 26.5.2 aarch64, 14 cores, 38.7 GB, JVM 21.0.8_