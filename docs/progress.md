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
- third-party review_fast (shop26 + glm-flash) on 4408ef0..9ce15c1: both approve; one note (craftsDone saturating add) folded into the enchant-ledger fix.
- workflow phase 2: impl-opus 1.20.1-forge (FG7 + Renamer SRG path, Forge 47.4.29) — green, reviewer PASS WITH NITS (medium: out-of-lane 1.20.1-fabric loader-floor drop, reverted in 34944bc); impl 26.1.2/26.2/26.3-forge (no JEI) — green, reviewer PASS WITH NITS.
- impl-opus: resolved the two-lane Forge merge (7a860fc) + registered shortfall_v2 on Forge 26.x and fixed a silent 5->6-arg compile break (34944bc) — green first pass, all 14 nodes build, 4 boot smokes pass.
- Live test 1.16.5 + IMDS 1.2.9: chaos ingot 61-craft chain executed exactly (54-craft burst <1 s); top-tier gather list saturates correctly; found count-overlap bug (fixed 79fbc4f, verified in-game).
- Live test 1.20.1-forge + IMDS Reborn 1.4.9: 69-step sword job cancelled "Chaotic Sword went missing" — Reborn outputs enchanted gear and the executor refuses enchanted stacks; fix delegated to impl-opus. Gather list treats crafting-cycle members as raw (#11).
