### Parity with modern versions
- Translation string Parity (36 missing keys + ~15 hardcoded English strings: HUD, preview, gather screen, JEI error tooltip)
- Config system (Forge @Config + GuiConfig; sound/JEI/needs toggles + modern gate composition for the yellow/orange buttons)
- Real vanilla toasts (GuiToast exists on 1.12.2) replacing the custom HUD card + step sound (UI_TOAST_IN)
- Recipe book refinements: craftable-only filter still hides deferred recipes; variant-overlay styling; ghost-recipe preview into the grid; tooltip hint on red recipes ("click to see what you need" — the gather screen has zero discoverability today)
- Gather screen: JEI R/U keys on its items (IAdvancedGuiHandler)
### Done (2026-07-02, JEI polish — tint/highlights user-verified in-game; orange gather path needs a quick look)
- JEI red missing-slot highlights restored (native drawHighlight, chain-aware slot selection: red = genuinely unobtainable, chain-craftable slots stay quiet, stock-parity fallback).
- Colored "+" buttons: yellow = deferred sprawl-craft, orange = unsolvable (and now CLICKABLE — opens the missing-resources gather screen next tick, modern's orange button), white = plain move. Tint mixin carries SRG+MCP dual names (require=0) against the prod-silent-no-op trap.
- Exact recipe identity from JEI's layout wrapper (O(1) vs full-registry scan per validation; display-collision-proof; falls back gracefully on JEI 4.15/HEI).
### Done (2026-07-02, verified first-hand in a dedicated test instance)
- Grid placement (JEI "+" AND the final-step hand-off) now sources from the open Crafting Station's bound chest as well as the player inventory — verified against stock behavior: TConstruct's own JEI integration moves chest items into the grid, so inventory-only placement was a regression at stations. Re-verified in-game post-fix: chest-fed hand-off stages the grid (READY_IN_GRID), chest-fed JEI "+" stages the grid instead of sprawl-crafting to inventory. NOTE: the placer runs server-side — dedicated servers need the updated jar too.
### Done (2026-07-01, needs playtest)
- Shift-click / JEI "+" bulk craft now behaves like vanilla: one C2S packet, atomic server-side placement (port of vanilla's own recipe-book placer, generic over modded stations). The old client-side click simulation survives only as the modless-server fallback.
- "Final crafting step in the crafting grid" on vanilla and modded tables: last craft of a deferred job is laid into the open grid as a real takeable craft (READY_IN_GRID state); 2x2 inventory screen covered via a client screen-state signal. All new packets live on a second network channel ("sprawlcrafting2") behind a hello handshake so mixed old/new versions can never be kicked.