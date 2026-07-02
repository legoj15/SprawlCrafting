### Remaining (cosmetic / nice-to-have)
- Variant-overlay (alternates bubble) deferred styling — cut deliberately: its craftable flag is a final ctor arg in an SRG-named inner class; fragile hook, rarely-seen UI
- Playtest pass over items 2-5 (config screen, toasts+sound, recipe-book filter/tooltips/ghost, JEI R/U on gather screen, orange gather button)
### Done (2026-07-02, items 2-5 + review hardening; committed 142c528 + 49f9282)
- Config: Forge @Config → config/sprawlcrafting.cfg + free mod-list Config screen; soundEffects / jeiIntegration / needsSystem with modern gate composition, live-read
- Translation parity: every user-facing string through the lang system (toast/preview/gather/config/recipe keys)
- Real vanilla toast (persistent per-job, progress bar, state colors) + UI_TOAST_IN step sound; HudOverlay deleted
- Recipe book: craftable-only filter keeps deferred recipes; tooltip hints (yellow deferred line, gold gather hint = discoverability); first click ghosts the recipe into the grid; gather screen is JEI-active (R/U keys)
- Review fixes: chest-pull transmutation dupe (meta-exact predicate + grow guard), place() self-heal + ghost, gate-pool alignment, modless-server EngineWatchdog notice
### Done (2026-07-02, JEI polish — tint/highlights user-verified in-game; orange gather path needs a quick look)
- JEI red missing-slot highlights restored (native drawHighlight, chain-aware slot selection: red = genuinely unobtainable, chain-craftable slots stay quiet, stock-parity fallback).
- Colored "+" buttons: yellow = deferred sprawl-craft, orange = unsolvable (and now CLICKABLE — opens the missing-resources gather screen next tick, modern's orange button), white = plain move. Tint mixin carries SRG+MCP dual names (require=0) against the prod-silent-no-op trap.
- Exact recipe identity from JEI's layout wrapper (O(1) vs full-registry scan per validation; display-collision-proof; falls back gracefully on JEI 4.15/HEI).
### Done (2026-07-02, verified first-hand in a dedicated test instance)
- Grid placement (JEI "+" AND the final-step hand-off) now sources from the open Crafting Station's bound chest as well as the player inventory — verified against stock behavior: TConstruct's own JEI integration moves chest items into the grid, so inventory-only placement was a regression at stations. Re-verified in-game post-fix: chest-fed hand-off stages the grid (READY_IN_GRID), chest-fed JEI "+" stages the grid instead of sprawl-crafting to inventory. NOTE: the placer runs server-side — dedicated servers need the updated jar too.
### Done (2026-07-01, needs playtest)
- Shift-click / JEI "+" bulk craft now behaves like vanilla: one C2S packet, atomic server-side placement (port of vanilla's own recipe-book placer, generic over modded stations). The old client-side click simulation survives only as the modless-server fallback.
- "Final crafting step in the crafting grid" on vanilla and modded tables: last craft of a deferred job is laid into the open grid as a real takeable craft (READY_IN_GRID state); 2x2 inventory screen covered via a client screen-state signal. All new packets live on a second network channel ("sprawlcrafting2") behind a hello handshake so mixed old/new versions can never be kicked.