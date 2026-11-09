# Handoff (2026-11-08, end of session)

## Done
- Ports: 1.20.1 (fabric, forge) in the Stonecutter tree; 1.16.5 (forge, fabric) as the separate legacy-1.16.5/ tree (FG7, built via ./gw.sh).
- Forge nodes for every version except 1.12.2 (which is already Forge): 1.20.1, 1.21.1, 26.1.2, 26.2, 26.3. 26.x Forge has no JEI (none is published).
- Live stress tests:
  - IMDS on 1.16.5: gather lists exact, including saturated counts; a 61-craft chain consumed exactly.
  - IMDS Reborn on 1.20.1-forge: the full 69-step sword chain completed after the produced-ledger fix.
- Produced ledger (#12) fixed and reviewed (final sha ed491c2, both review_fast lanes approved).
- **main fast-forwarded to 3e178b1** (local only, NOT pushed).

## Not done / next
1. After the merge, run `review_final` (dgx + glm-5.3) on the merged range 4bcbd34..3e178b1, per CLAUDE.md, then verify and act on its findings as follow-up commits. Scope with `files` (the range is large).
2. Production matrix legs missing for the new nodes: 1.20.1-fabric/forge, 1.21.1-forge, 26.x-forge, 1.16.5, plus a modless-connect test. **Do not run the release tooling until these exist**: Get-ScNodes auto-discovers versions/*, so it would publish untested nodes.
3. In-game re-test of the #12 fix with Reborn (no unit harness covers assign()).
4. Open issues:
   - #11: the gather list treats craft-cycle members as raw.
   - #13: the ledger residual stale-count cases.
   - Check #5, #6, #7, #9 for closure.
5. Open lows:
   - JEI saturated-line wording.
   - Per-player burst budget.
   - Stray fernflower txt in the 1.16.5 jar.
   - 1.16.5 missing from update.json.
   - The Forge 26.x shortfall_v2 round trip is verified from source only.
   - The 1.12.2 name/need text overlaps.

## Open questions
- Push main and the session branch? (Not done; needs the user's go-ahead.)

## Uncommitted changes
None besides this file (committed with it).
