# Progress and delegation log

One line per delegation: agent, lane, outcome, escalations. Used to tune the model ladder.

## 2026-11-08 — ports to 1.20.1 / 1.16.5, Forge nodes, IMDS stress test (#5 #6 #7 #9)

- researcher (Haiku): Forge toolchains + versions — good; found FG7 7.0.40 on Gradle 9.5, Forge exists for 26.x, no JEI/REI Forge on 26.x.
- researcher (Haiku): IMDS mods — good; original = Forge 1.16.5 (CF 562331), Reborn = Forge 1.20.1 (Modrinth imds-reborn).
- impl (Sonnet xhigh): 1.21.1-forge node (FG7 7.0.40, Forge 52.1.21) — green first pass; reviewer (Haiku) PASS WITH NITS. Also consolidated the 26.x regex tiers into the controller.
- impl-opus: 1.20.1-fabric backport (SprawlPayload seam, RecipeHolder shim, back-port regex tiers) — green first pass, reviewer PASS WITH NITS; left work UNCOMMITTED citing its standing "orchestrator commits" rule (orchestrator committed 5bfda4a).
- impl-opus: legacy-1.16.5 tree (Forge FG7 + Fabric Loom on Gradle 9.5, Java 8 toolchain, MCP-class layered mappings) — green first pass, reviewer PASS WITH NITS.
- Orchestrator: merged the three lanes into integ-ports; resolved the duplicate sendToPlayer overload (CustomPacketPayload vs SprawlPayload) onto SprawlPayload.
- impl-opus: batched solver (solver-core) — first attempt FAILED verification (high: merged identical slots reordered stock consumption; medium: gather list not a fixed point, perf regressions) -> escalated to a second impl-opus pass (same tier: impl-opus is the top implementer) -> green, reviewer PASS WITH NITS. Both passes left work uncommitted ("orchestrator commits" rule); orchestrator committed 2b36d14. One pre-existing plan-validity bug filed as #10.
- impl (Sonnet xhigh): huge counts end to end (ShortfallV2Payload, CountFormat, burst executor) — green, reviewer findings (small-recipe pacing regression, reach gate only every 10 ticks, compact(Long.MAX) overflow) fixed by a second impl pass in 9ce15c1; third-party review_fast launched on 4408ef0..9ce15c1.
- impl (Sonnet xhigh): gather-list count overlap (dynamic cell pitch) — green first pass, 79fbc4f; found live in the 1.16.5 IMDS test.
