# SprawlCrafting — Design

Factorio-style deferred crafting for the vanilla inventory and crafting table: request a
final item, and the mod automatically crafts every intermediate from your raw resources,
one component every half second.

## Decisions (locked 2026-06-09)

| Area | Decision |
|---|---|
| Project structure | MultiLoader template: shared `common` source set + `fabric` + `neoforge` loader projects. No runtime library dependency. |
| Initial target | Minecraft 1.21.1 (NeoForge 21.1.x, Fabric Loader 0.16.x). MC 26.1.2 added later via Stonecutter once the design is proven. |
| Intermediates | Crafted as **real items into the player's inventory**, then consumed by later steps. Crash-safe and visible to other mods; intermediates briefly occupy slots. |
| Resource consumption | **Per step**, from the live inventory — nothing is reserved upfront. If a step's ingredients are missing when it comes due, the job fails gracefully (notification; already-crafted items remain). |
| Queue model | **Single job at a time** per player in v1. Requesting a second craft while one runs is rejected/greyed out. |
| Craft cadence | One component craft per 10 ticks (0.5 s) — `CraftJob.TICKS_PER_STEP`. |

## Core flow

1. **Recipe book / JEI / REI hook (client):** recipes whose direct ingredients are missing
   but whose *raw* cost is satisfiable get a **yellow outline** (vanilla: white = craftable,
   red = not). Clicking one sends a "request deferred craft" packet instead of failing.
2. **Planning (server, `CraftPlanner`):** walk the recipe dependency tree against the
   player's inventory; emit a topologically ordered list of `CraftStep`s (recipe × count).
   Items already held are used before any sub-craft is planned.
3. **Execution (server, `CraftQueueManager`):** every 10 ticks, re-check + consume the
   current step's ingredients from the live inventory and insert its output as real items
   (drop at feet if full). Shortfall → cancel + notify.
4. **HUD (client):** while a job runs with the inventory closed, a top-right flyout (toast
   style) shows the item currently being crafted and overall job progress.

## Grid gating and the crafting table (locked 2026-06-10)

- **Whole-chain gating at request time:** every step of a job — intermediates and the final
  recipe — must fit the grid the request was made from (`GridContext`): the inventory recipe
  book plans 2×2 chains only; the crafting table book plans with the full 3×3. A 3×3-only
  recipe simply doesn't exist as a producer for an inventory-book request.
- **Proximity pause at execution time:** steps flagged `needsFullGrid` (don't fit 2×2) only
  execute while a crafting table is within the player's *block-interaction reach* — the
  attribute-driven distance (`Attributes.BLOCK_INTERACTION_RANGE`), so reach-extending
  gear/mods are honored. Out of range → the job pauses (Factorio assembler waiting on input)
  and auto-resumes; 2×2 steps keep crafting anywhere. Vanilla `Player.canInteractWithBlock`
  semantics decide "in reach".
- **Recipe book UX:** yellow-outlined recipes are "craftable from raws". Clicking one shows a
  **preview** of the plan (computed client-side — the client has recipes + its own inventory
  mirror); a second click confirms and sends the start packet; the server re-plans
  authoritatively. Yellow recipes are included in the book's "show craftable" filter.

## Recipe ambiguity rule

When multiple recipes produce a missing intermediate, pick deterministically: the first
recipe whose raw cost is currently satisfiable, preferring fewer total steps. No player
prompt in v1. Planning is depth-limited and cycle-guarded (e.g. iron ingot ⇄ iron block).

## Scope

**v1:** vanilla crafting recipes only. Single job, real intermediates, per-step consumption,
whole-chain grid gating (see "Grid gating and the crafting table" above — supersedes the
earlier final-recipe-only rule).

**Later / open:**
- MC 26.1.2 targets via Stonecutter (multi-version, same codebase).
- JEI/REI plugins beyond what the vanilla recipe book hook gives for free.
- Persistence of an in-flight job across relog (v1: job cancels on disconnect — harmless,
  since consumption is per-step and intermediates are real items).
- Whether smelting/smithing/stonecutting count as plannable sub-recipes (v1: crafting only).
- Quantity batching and multi-job FIFO (deliberately deferred from v1).

## Known v1 limitations (reviewed and accepted)

- **Greedy planning, no cross-slot backtracking.** Slots resolve left-to-right against one
  virtual inventory; a slot never reconsiders an earlier slot's choice. Under shared-resource
  contention (two slots competing for the last unit of something with an alternative available)
  a solvable target can be reported unsolvable. Full search is exponential; rejected for v1.
- **Search budget instead of memoization.** Pathological graphs (vanilla's 16-color bed cycle
  with all dyes stocked but no wool/planks, or hostile datapacks) abort with a "too complex"
  message after `MAX_ATTEMPTS` resolution attempts rather than hanging the server thread.
- **Items planned by `Item` identity.** Components/NBT are ignored at plan time; execution
  re-checks with `Ingredient.test` and cancels gracefully on divergence. Damaged, enchanted,
  and renamed stacks are excluded from both planning and consumption (vanilla recipe-book parity).
- **Per-`Item` crafting remainders.** Recipes overriding `getRemainingItems` (and NeoForge's
  stack-sensitive remainders on modded items) are approximated by the item-level remainder.
- **NeoForge empty-tag placeholder.** NeoForge substitutes a barrier item for ingredients with
  empty tags; such plans fail at execution (gracefully) rather than at planning.
- **Play→configuration phase re-entry** (server reconfiguration mid-session) cancels the job
  on NeoForge but not on Fabric; both are safe, just inconsistent. Revisit with the HUD work.

## Module layout

- `common/` — all gameplay logic (`craft/` package), mixins into vanilla recipe book UI,
  HUD rendering, shared networking payloads. Compiles against vanilla via NeoForm.
- `neoforge/`, `fabric/` — entry points, tick/disconnect event wiring, packet registration,
  loader-specific `IPlatformHelper` implementations (`ServiceLoader`-discovered).
