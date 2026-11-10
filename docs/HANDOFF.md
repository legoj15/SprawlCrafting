# Handoff (2026-11-09, end of session)

Branch `claude/sprawlcrafting-bot-mod-prep-e681d0` (worktree `version-26-3-testing-26ac36`), on top of
main `e39879c`. Local only, NOT pushed, NOT merged to main.

## Done
- **Solver library (MegaBlockBot#2):** `solver-core/` is now the standalone plain-Java library
  `com.legoj15:solver` (package `com.legoj15.solver`, currently 1.0.1), consumed by every tree through a
  Gradle composite build and bundled jar-in-jar (Fabric include, NeoForge/Forge jarJar `[v,2.0.0)`) or
  shaded (1.16.5 Forge, 1.12.2). Its JUnit suite runs only in `./gradlew -p solver-core build`; the
  release gate runs it first. See `docs/solver-library.md` for the consumer recipe and executor contract.
- Issues fixed on this branch (closed with `done` notes once merged/pushed): #4 (1.12.2 alternates
  bubble), #10 + #18 (SlotAssignment + PlanSimulator), #11 (cycle members in gather list), #13
  (LedgerTally), #15 (fernflower txt, release legacyTrees), #16 (saturated wording, 1.12.2 gather width),
  #17 (shared per-tick burst budget). #19 closed as not-a-bug. #5 #6 #7 #9 #12 closed (shipped earlier).
- Full rebuild green at the last merge: solver-core (66 tests), 14 root nodes, legacy-1.16.5, forge-1.12.2.
- review_final on the old ports range done; review_fast on c90e178..d825b8f done (adjudicated, see
  progress.md).

## Not done / next
1. Collect the review_fast on d825b8f..HEAD (group `solver-exec-fixes-20261109-195006-b6a5`) and the
   Haiku review of the three hand-merges; fix anything real, re-gate the final sha.
2. Merge this branch to main (user's call), then `done` the fixed issues, then push (user's go-ahead).
3. In-game: #8 (1.12.2 playtest, now also covers #4's bubble and #20), #3 (26.3 untakeable result,
   re-check), and the executor change from #10/#18/#13 on at least one modern node (no unit harness
   covers the Minecraft side of assign()).
4. #14 production matrix legs (new nodes, modless connect, Fabric two-mod solver dedupe). Do not run
   the release tooling before this.
5. Open follow-ups: #20 #21 #22 #23 #24. #2 is ON HOLD (owner undecided; design rule: every source
   item visible in the open screen).

## Uncommitted changes
None besides this file (committed with it).
