# ADR 0001: Adopt openclaw Architectural Patterns for LLM4ZIO Phase 3

## Status

**ACCEPTED** — 2026-02-14

## Context

LLM4ZIO has successfully implemented Phases 1 and 2, establishing a functional agent tool with basic workflow orchestration. As we plan Phase 3, we need to address several architectural limitations:

1. **Lack of Run Isolation**: Parallel migrations share global state, risking interference
2. **No Progress Streaming**: Long-running migrations provide no real-time feedback
3. **Static Agent Assignment**: Workflow definitions hard-code agent mappings
4. **No Central Coordination**: Direct agent execution prevents monitoring and control
5. **Limited Extensibility**: Adding custom agents (#123, #124) requires orchestrator changes

The [openclaw](https://openclaw.ai) project provides proven patterns for LLM agent orchestration that directly address these gaps. We must decide whether to adopt these patterns and which ones to prioritize.

## Decision

We will adopt **5 high-priority openclaw patterns** in Phase 3, adapted to LLM4ZIO's ZIO-native architecture:

### Patterns to Adopt

#### 1. Gateway Control Plane → Orchestrator Control Service (#148)
**Adoption:** Full implementation with ZIO Hub event broadcasting

**Rationale:**
- Enables centralized lifecycle management
- Supports multiple event subscribers (CLI, web portal, audit logs)
- Provides command/control interface (pause, resume, cancel)

**ZIO Adaptation:**
- Replace WebSocket with ZIO Hub for type-safe event distribution
- Use ZIO Queue for command processing with backpressure
- Leverage ZLayer.scoped for resource lifecycle

---

#### 2. Session Isolation → Run-Based Workspace Service (#147)
**Adoption:** Full implementation with per-run directories

**Rationale:**
- Critical for safe parallel execution
- Simplifies cleanup and resource tracking
- Enables configuration snapshots and audit trails

**ZIO Adaptation:**
- Use ZIO managed resources for guaranteed cleanup
- Integrate with Scope for automatic finalization
- Leverage Path operations with effect tracking

---

#### 3. Multi-Agent Routing → Dynamic Agent Selection (#151)
**Adoption:** Capability-based matching with registry

**Rationale:**
- Eliminates static agent mappings in workflows
- Enables optimization based on file characteristics
- Supports custom agents without workflow changes

**ZIO Adaptation:**
- Type-safe capability matching with Scala 3 types
- ZIO-based registry with concurrent access
- Integration with existing AgentRegistry

---

#### 4. Tool/Skill Registry → Enhanced Agent Registry (#149)
**Adoption:** Database-backed registry with capabilities and health checks

**Rationale:**
- Foundation for dynamic routing
- Enables custom agent loading (#123, #124)
- Provides performance metrics for optimization

**ZIO Adaptation:**
- Use ZIO database layer for persistence
- Implement health checks as scheduledeffects
- Expose metrics via ZIO Metrics

---

#### 5. Streaming Execution → Workflow Progress Streaming (#150)
**Adoption:** ZStream-based progress events with SSE endpoint

**Rationale:**
- Immediate UX improvement for web portal
- Enables real-time cost/token tracking
- Supports multiple subscribers via control plane

**ZIO Adaptation:**
- Leverage existing ZStream usage in agents
- Integrate with control plane Hub
- Add backpressure handling for slow consumers

---

### Patterns Deferred to Phase 4+

#### 6. Agent Runtime (RPC Mode)
**Rationale for Deferral:** High complexity, no current requirement for multi-language agents or process isolation

**Revisit When:**
- External agent hosting needed
- Security isolation requirements emerge
- Multi-language agents required

---

#### 7. Security Model (Per-Session Allowlists)
**Rationale for Deferral:** Over-engineering for current single-user batch workflows

**Revisit When:**
- Multi-tenant deployment required
- Untrusted custom agents need sandboxing
- Compliance mandates access control

---

## Consequences

### Positive

**Isolation:**
- Parallel runs won't interfere
- Workspace cleanup guaranteed via ZIO finalizers
- Clear resource ownership per run

**Extensibility:**
- Add agents without modifying orchestrator
- Add event subscribers without changing producers
- Workflow templates with parameters

**Observability:**
- Real-time progress in web UI
- Per-item token/cost tracking
- Centralized event logging and metrics

**Maintainability:**
- Separation of concerns (coordination vs execution)
- Clear component boundaries
- Easier testing with scoped resources

---

### Negative

**Complexity:**
- ~600 hours of implementation effort (3.5 engineer-months)
- More moving parts to coordinate
- Event broadcasting adds latency overhead

**Migration Burden:**
- Existing runs need state migration
- Dual implementation path during transition
- Feature flags add configuration complexity

**Performance Overhead:**
- Event broadcasting adds ~5% latency (acceptable)
- Registry lookups for each agent selection
- Memory footprint increases with concurrent runs

---

### Neutral

**Learning Curve:**
- Team must understand control plane patterns
- Event-driven architecture paradigm shift
- Testing strategies for async systems

**Operational Overhead:**
- Monitoring for control plane health
- Workspace disk usage management
- Event stream debugging tools needed

---

## Implementation Strategy

### Phase 3A: Foundation (Weeks 1-4)
1. Workspace Service (#147)
2. Control Plane (#148)
3. Feature flags for gradual rollout

### Phase 3B: Streaming & Registry (Weeks 5-8)
4. Enhanced Agent Registry (#149)
5. Progress Streaming (#150)
6. SSE endpoint in web API

### Phase 3C: Dynamic Workflows (Weeks 9-12)
7. Dynamic Workflow Composition (#151)
8. Multi-Workspace Coordination (#152)
9. Workflow templates

### Backward Compatibility
- Feature flags control new behavior
- Legacy execution path preserved
- Gradual deprecation over 3 releases

---

## Alternatives Considered

### Alternative 1: Build Custom Orchestration from Scratch

**Pros:**
- Full control over design
- No adaptation effort from openclaw patterns

**Cons:**
- Reinventing proven patterns
- Higher risk of architectural mistakes
- Longer implementation time

**Verdict:** Rejected — openclaw patterns are battle-tested

---

### Alternative 2: Adopt Workflow Engines (Temporal, Cadence)

**Pros:**
- Enterprise-grade workflow orchestration
- Built-in retry, timeout, compensation
- Distributed execution support

**Cons:**
- Heavy runtime dependencies (databases, workers)
- Steep learning curve
- Overkill for current scale
- Poor ZIO integration

**Verdict:** Rejected — Too heavyweight, poor ZIO fit

---

### Alternative 3: Use Actor Systems (Akka, ZIO Actors)

**Pros:**
- Natural fit for event-driven systems
- Mature actor implementations available
- Good for distributed coordination

**Cons:**
- Akka licensing concerns (BSL)
- ZIO Actors still experimental
- Actor model overkill for current needs
- State management complexity

**Verdict:** Rejected — Control plane with Hub/Queue is simpler

---

### Alternative 4: Defer All Changes to Phase 4

**Pros:**
- Focus on current feature development
- No migration burden

**Cons:**
- Custom agents blocked (#123, #124)
- Poor UX for long-running migrations
- Parallel execution remains risky
- Technical debt accumulates

**Verdict:** Rejected — Architectural improvements enable future features

---

## Validation

### Acceptance Criteria

**Isolation:**
✅ 100 parallel runs execute without interference
✅ Workspace cleanup succeeds on cancellation
✅ <10% resource overhead per run

**Extensibility:**
✅ Add custom agent with 0 orchestrator changes
✅ Add event subscriber with <10 LOC
✅ Create workflow template with <50 LOC

**Observability:**
✅ <1s P99 latency for progress updates
✅ Per-item token/cost tracking works
✅ >1000 events/sec throughput

**Performance:**
✅ No regression in single-run throughput
✅ <5% control plane latency overhead
✅ All existing tests pass with legacy flags

---

## References

- [openclaw Pattern Analysis](./2026-02-14-openclaw-pattern-analysis.md)
- [openclaw Architecture Documentation](https://docs.openclaw.ai/concepts/architecture)
- [Issue #146: openclaw Pattern Analysis](https://github.com/riccardomerolla/llm4zio/issues/146)
- [Issue #147: Workspace Service](https://github.com/riccardomerolla/llm4zio/issues/147)
- [Issue #148: Orchestrator Control Plane](https://github.com/riccardomerolla/llm4zio/issues/148)
- [ZIO Hub Documentation](https://zio.dev/reference/concurrency/hub)
- [ZIO Layers](https://zio.dev/reference/contextual/)

---

## Decision Makers

- **Architecture Team** — Pattern selection and ZIO adaptation
- **Product Team** — Priority and timeline approval
- **Engineering Team** — Implementation feasibility review

## Review Schedule

- **Checkpoint 1:** End of Sprint 2 (Phase 3A completion)
- **Checkpoint 2:** End of Sprint 4 (Phase 3B completion)
- **Final Review:** End of Sprint 6 (Phase 3C completion)

---

**Last Updated:** 2026-02-14  
**Next Review:** 2026-03-14
