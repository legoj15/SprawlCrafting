# SprawlCrafting — Design

Factorio-style deferred crafting for the vanilla inventory and crafting table: request a
final item, and the mod automatically crafts every intermediate from your raw resources,
one component every half second. Intermediates are crafted as **real items** into the
player's inventory and consumed by later steps, so a craft is crash-safe and visible to
other mods.

This document describes the current design across all shipped targets. The core model was
locked early (2026-06-09/06-10) and still holds; the architecture has since grown a shared
solver core, Stonecutter multi-versioning, JEI/REI integration, a config system, and a
separate 1.12.2 Forge port.

## Targets and toolchain

| Minecraft | Loaders | Build | JDK |
|---|---|---|---|
| 1.21.1 | Fabric, NeoForge | Stonecutter node | 21 |
| 26.1.2 | Fabric, NeoForge | Stonecutter node | 25 |
| 26.2 | Fabric, NeoForge | Stonecutter node | 25 |
| 1.12.2 | Forge | separate RFG build | 25 host / Java 8 output |

The modern loaders (1.21.1 / 26.x) share one source set via **Stonecutter**: `src/` is
preprocessed per node into `versions/<node>/`, six nodes total. Cross-version API
differences are handled inline with Stonecutter comment directives (e.g.
`//? if >=1.21.11 { … }`). The **1.12.2 Forge** target is a separate, self-contained Gradle
build (RetroFuturaGradle) because the legacy and modern toolchains can't coexist; see
[`forge-1.12.2/README.md`](forge-1.12.2/README.md). Both trees compile in the same
Minecraft-free planner (below).

## Core model

*(originally locked 2026-06-09/06-10, still in force)*

- **Intermediates are real items.** Each sub-craft's output goes into the player's inventory
  (dropped at their feet if full) and is consumed by later steps. Crash-safe, visible to
  other mods, and an item going missing mid-craft cancels the job.
- **Per-step consumption from the live inventory.** Nothing is reserved upfront; a step
  re-checks and consumes its ingredients when it comes due. If they're gone, the job fails
  gracefully — already-crafted items remain.
- **One job per player at a time.** Requesting a second craft while one runs is rejected.
- **Cadence: one component craft per 10 ticks (0.5 s)** — `CraftJob.TICKS_PER_STEP`.
- **Deterministic recipe ambiguity.** When several recipes make a needed intermediate, pick
  the first whose raw cost is currently satisfiable, preferring fewer total steps. Planning
  is depth-limited and cycle-guarded (e.g. iron ingot ⇄ block). No player prompt.
- **Crafting recipes only.** Smelting / smithing / stonecutting aren't planned as sub-recipes.

## The shared solver (`solver-core/`)

The planning brain is a pure, **Minecraft-free** Java module in `solver-core/`
(`com.legoj15.sprawlcrafting.craft.solver`: `RecipeGraphSolver` + `PlannedStep`). It's
compiled directly into every build — the modern Stonecutter nodes and the 1.12.2 jar — as
one source of truth with no separate artifact, and is covered by a loader-agnostic JUnit
suite that runs on the Java 8 toolchain.

Each loader wraps it with a `CraftPlanner` that adapts Minecraft recipes and a player
inventory onto the solver:

- **Server:** plans authoritatively from the real `ServerPlayer` state.
- **Client (1.21.1 / 1.12.2):** opens a planning `Session` over the client's synced recipe
  manager + inventory mirror, computing yellow-outline solvability and click previews with
  **no server round-trips**.

The solver is greedy with a bounded backtracking budget (`MAX_DEPTH`, `MAX_ATTEMPTS`) rather
than a full exponential search — see "Known limitations".

## Core flow

1. **Trigger (client).** In the recipe book / JEI / REI, a recipe whose direct ingredients
   are missing but whose *raw* cost is satisfiable gets a **yellow** outline (vanilla: white
   = craftable now, red = missing). Acting on it sends a "request deferred craft" packet
   instead of failing.
2. **Planning (server, `CraftPlanner` → `RecipeGraphSolver`).** Walk the recipe dependency
   tree against the player's inventory; emit a topologically ordered list of steps
   (recipe × count). Held items are used before any sub-craft is planned.
3. **Execution (server, `CraftQueueManager` / `CraftExecutor`).** Every 10 ticks, re-check
   and consume the current step's ingredients from the live inventory and insert its output
   as real items. Shortfall → cancel + notify.
4. **Progress toast (client).** While a job runs with the inventory closed, a vanilla toast
   (persistent per job, progress bar, state colors) shows the current item and overall
   progress, alongside a per-step craft sound.

## Grid gating and the crafting table

- **Whole-chain gating at request time.** Every step — intermediates and final — must fit
  the grid the request came from (`GridContext`): the inventory recipe book plans **2×2**
  chains only; a crafting table plans the full **3×3**. A 3×3-only recipe simply isn't
  offered for a 2×2 request.
- **Grid-size detection.** `GridContext` decides 2×2 vs 3×3 from the open menu's
  crafting-grid size rather than a hardcoded table class, so a modded recipe-book menu that
  exposes a 3×3 grid plans correctly. On 1.12.2 (no generic grid-size accessor) this is done
  by inspecting the container's crafting matrix, which additionally recognizes modded
  stations like the Tinkers' Construct Crafting Station and its slab-mod variants.
- **Proximity pause at execution time.** Steps that need the full grid only run while a
  crafting table is within the player's *block-interaction reach* — the attribute-driven
  distance (`blockInteractionRange()` / vanilla `canInteractWithBlock`), so reach-extending
  gear and mods are honored. Out of range, the job pauses (a Factorio assembler waiting on
  input) and auto-resumes; 2×2 steps craft anywhere. The reach scan skips empty chunk
  sections via the block palette, so a 64-block reach doesn't cost millions of lookups.

## Client craftability across versions

Whether the client can compute "is this yellow?" itself depends on the version:

- **1.21.1 & 1.12.2:** the client reads recipes, so solvability and previews are computed
  **client-side** with no round-trip.
- **26.x:** the client **can't read recipes**, so the server computes craftability and
  **syncs it** to the client (`DeferredCraftSync`) to keep feature parity. This path is
  gated so it doesn't regress 1.21.1's local solving. (The recipe book only repaints
  craftability during its `selectRecipes` pass, so the sync nudges `recipesUpdated()` to
  force a repaint.)

## Triggers and player-facing helpers

- **Recipe book.** Yellow outline on raws-craftable recipes; a first click previews the
  plan, a second confirms and sends the start packet (the server re-plans authoritatively).
  Yellow recipes are included in the book's "show craftable" filter and carry tooltip hints.
- **JEI / REI.** The "+" transfer button routes a deferred-craftable recipe into the engine;
  colored buttons (yellow = deferred, orange = unsolvable, white = plain move) and red
  missing-slot highlights mirror the recipe book. REI is 1.21.1 only.
- **Needs / gather screen.** Right-clicking a red recipe or the orange JEI/REI button opens
  a list of the raw materials still needed to make it. Gated by the `needs_system` config.
- **Final-step grid hand-off.** If the player leaves the crafting GUI open, the last craft
  is laid into the open grid as a real, takeable result — matching what vanilla "+"/
  recipe-book placement would do — rather than auto-inserting to the inventory.
- **Config.** `sound_effects`, `jei_integration`, `rei_integration`, `needs_system`, all
  defaulting on; a client config file plus an in-game screen (Mod Menu on Fabric, the
  mod-config screen on NeoForge, Forge's mod-list Config button on 1.12.2 — which drops the
  REI toggle).

## The 1.12.2 (Forge) port

A full port of the engine onto 1.12.2's APIs on top of the same `solver-core`, at feature
parity with the modern loaders. It lives in its own build for the toolchain reasons above.
Design points specific to it:

- **Item identity is `(Item, metadata)`** (`ItemKey`), not `Item`: 1.12.2 packs distinct
  craftables into one `Item` via metadata (all six planks are `minecraft:planks`).
- **Requires MixinBooter (client).** It provides the SpongePowered Mixin runtime and loads
  SprawlCrafting's recipe-book / JEI mixins from the jar's `MixinConfigs` manifest entry.
  Without it those mixins silently no-op, so the client hard-stops at a friendly error
  screen. A dedicated server doesn't need it.
- **Networking** is a `SimpleNetworkWrapper` channel; grid-hand-off and screen-state
  messages ride a **second** channel (`sprawlcrafting2`) behind a hello handshake, so mixed
  old/new client and server versions can never desync or kick. The main channel's message
  ids are frozen.
- **Chest sourcing.** Grid placement can also draw from a modded station's bound chest (e.g.
  a TConstruct Crafting Station), matching those stations' own JEI behavior.
- **One deliberate cosmetic cut:** the recipe book's variant-overlay ("alternates") bubble
  isn't given the yellow deferred styling — a fragile SRG-named inner-class hook for a
  rarely-seen bit of UI.

## Multiplayer and compatibility

Install on both client and server. The mod **never gates server access on its own
presence**: payloads are registered optional and every send is channel-guarded (1.12.2 uses
`acceptableRemoteVersions = "*"`), so:

- a modless / vanilla client can join a SprawlCrafting server (it just gets no offers or
  toast), and
- a SprawlCrafting client on a vanilla server sees no yellow offers (its start packet is
  ignored).

The server only sends in response to a client-initiated craft, so a peer lacking the mod
never receives an unsolicited packet.

## Module layout

- `src/` — modern shared gameplay logic (`craft/` package), recipe-book / JEI / REI mixins,
  toast rendering, networking; preprocessed per node into `versions/<node>/` by Stonecutter
  (`stonecutter.gradle.kts`, `build.<loader>.gradle.kts`).
- `solver-core/` — the Minecraft-free planner + its JUnit suite; compiled into every build.
- `forge-1.12.2/` — the separate legacy Forge build (RFG); also compiles in `solver-core`.
- `release/` — the publish pipeline (GitHub / Modrinth / CurseForge / `update.json`).
- `testing/` — the boot-and-connect verification matrix per node.
- `branding/` — mod logo and icon art.

## Known limitations (reviewed and accepted)

**Planning**

- **Greedy, no cross-slot backtracking.** Slots resolve left-to-right against one virtual
  inventory; under shared-resource contention a solvable target can be reported unsolvable.
  Full search is exponential — rejected.
- **Search budget instead of memoization.** Pathological graphs (the 16-color bed cycle with
  all dyes stocked but no wool/planks, or hostile datapacks) abort with a "too complex"
  message after `MAX_ATTEMPTS` rather than hanging the server thread.
- **Planned by item identity.** Components / NBT are ignored at plan time; execution
  re-checks with `Ingredient.test` and cancels gracefully on divergence. Damaged, enchanted,
  and renamed stacks are excluded from both planning and consumption (recipe-book parity).
- **Per-item crafting remainders.** Recipes overriding `getRemainingItems` (and NeoForge's
  stack-sensitive remainders on modded items) are approximated at the item level.
- **NeoForge empty-tag placeholder.** Ingredients with empty tags fail at execution
  (gracefully) rather than at planning.

**UI**

- **Variant-overlay popup** buttons render vanilla-white, not yellow (restyling
  JEI-internal / recipe-book overlay buttons isn't API-exposed). The main grid buttons color
  correctly.
- **Modded crafting-table reach.** Grid *size* detection is generic, but the execution-time
  proximity pause looks for a vanilla `minecraft:crafting_table` block, so a full-grid job
  pauses indefinitely next to a *modded-only* table. Tracked for a future "blocks that open
  a 3×3 crafting menu" registry.

**Lifecycle**

- **Play→configuration re-entry** (server reconfiguration mid-session) cancels the job on
  NeoForge but not on Fabric; both are safe, just inconsistent.
