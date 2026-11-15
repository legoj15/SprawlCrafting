# Sophisticated crafting-upgrade: in-game check (agent runbook)

How the compat in `compat/sophisticated/` (design: DESIGN.md "Sophisticated crafting upgrade") was verified, so the
check can be repeated. Verified in game on `1.21.1-neoforge` (full scenario), `26.1.2-neoforge` (yellow "+", hand-off,
gather list) and `1.20.1-forge` (full scenario, Storage and Backpacks; the node-specific steps and the production
check are in the last section); `26.2` / `26.3-neoforge` are compile-checked (see the next-to-last section).

## Launch

1. Drop `McDevBridge/versions/1.21.1/build/libs/mcdevbridge-*.jar` into `versions/1.21.1-neoforge/run/mods`.
2. Copy a survival world in `run/saves` to a name without spaces (e.g. `SophTest`).
3. `.\devclient.ps1 -Node 1.21.1-neoforge -Gradle '-Psc.sophisticated=true -Psc.world=SophTest'` in the background
   (it holds the machine-wide smoke lock; stop it with `taskkill /T /F /PID <printed cmd pid>`). The flag puts
   Core, Storage and Backpacks on the runtime with JEI `sophisticated_jei_version` (Core wants JEI >= 19.32; 19.42+
   needs NeoForge 21.1.238, above this node's pin, hence 19.39).
4. Boot without the flag too: the log must lack "Sophisticated Core found", JEI must still register, no
   `NoClassDefFoundError`. Same for `.\smoke.ps1 -Task :1.21.1-neoforge:runServer` (with and without the flag).

## Scenario (bridge: `~/.mcdevbridge/bridge.json`, endpoints in McDevBridge/README.md)

- `gamemode survival`, `clear`, then give logs / iron / cobblestone / redstone, a `sophisticatedstorage:barrel`, a
  `sophisticatedbackpacks:backpack`, and a crafting upgrade of each. Place the barrel, open it.
- **No vanilla table in reach** (the test world had two next to spawn, which silently invalidates a gate test):
  `execute as Dev at @s run fill ~-8 ~-4 ~-8 ~8 ~4 ~8 minecraft:air replace minecraft:crafting_table` must say it
  filled nothing the second time.
- Bridge limits met: `/slotclick` only addresses `menu.slots`, not Sophisticated's upgrade slots, so click those by
  screen fraction (`/screenclick` `fx`/`fy`; 854x480 screenshot, GUI scale dependent: upgrade slot about
  0.275/0.2125, its tab about 0.726/0.323 once installed). JEI's R key never reaches JEI (it listens to NeoForge's
  screen key event, the bridge calls `Screen.keyPressed`): open a recipe by left-clicking an item in JEI's list
  (oak door: about 0.966/0.454 with the crafting tab open, 0.9625/0.225 with it closed; screenshot first, the list
  reflows). The recipe screen's "+" is about 0.664/0.585; `/screenhover` on it shows the tooltip.
- Door = 3 high, so 3x3-only; logs only = deferred. Expected: yellow "+" (our handler, not the mod's red one),
  click -> job runs with the screen open and no table; the last craft lands in the open crafting tab's grid
  ("done in the crafting grid") or, with the tab closed, in the inventory ("Crafted"); close the screen right
  after the click and the job pauses at the door step, reopen and it resumes. With the 6 planks already in
  the inventory the "+" is white "Move Items" and fills the grid through the mod's own packet. With no logs the
  "+" is orange and opens the gather list; ESC returns to the storage screen.
- Grid clearing: put one cobblestone into the upgrade's grid cell (open the tab first; a right-click by fraction
  with the tab closed drops the item into the world), then click the deferred "+": the cobblestone must be back in the
  inventory and the grid then holds the door layout.
- Vanilla regression on the same build: a crafting table screen still hands off through `handlePlacement`, and
  clicking a deferred "+" with a stray item in the grid returns it to the inventory first.

## Traps found

- On the server `UpgradeContainerBase.isOpen()` is set only when the menu is built, never when a tab is opened
  later; the open tab is `StorageContainerMenuBase.getOpenContainer()`. The first hand-off attempt used `isOpen()`
  and silently fell back to the inventory for a tab opened during the session.
- Upgrade slots are not in `menu.slots` (`getSlot` is overridden to reach them), and only `Slot.set` recomputes the
  result slot.

## 26.x NeoForge nodes

`26.1.2` / `26.2` / `26.3-neoforge` compile the compat (`IRecipeType` and the size accessors are the Stonecutter
branches). On 26.x the yellow state comes from the server's craftability sync (`DeferredCraftSync.maybeSync`, gated on
`CraftingGrids.find` plus the client's open-grid report), which is why it needed its own in-game check:
`26.1.2-neoforge` with `-Psc.sophisticated=true` (its pins already satisfy Core 26.1.2; bridge jar
`mcdevbridge-1.4.3+mc26.1.2.jar`) shows the yellow "+" on the barrel's crafting tab, hands the door off into the
upgrade grid, and opens the gather list. `26.3` already satisfies Core 26.3's minimums but was not run; `26.2`'s pins
(NeoForge 26.2.0.7-beta, JEI 30.2.0.15) are below Core 26.2's (26.2.0.53-beta, JEI 30.7.0.39), so raise
`neoforge_version` and set `sophisticated_jei_version` there before using the flag on it.

## 1.20.1 Forge node (`1.20.1-forge`)

Same scenario as above, with these node differences. Dev runtime: `.\devclient.ps1 -Node 1.20.1-forge -Gradle
'-Psc.sophisticated=true -Psc.world=SophTest'` (JEI 15.74 already meets Core's JEI >= 15.20, so there is no
`sophisticated_jei_version`; the three jars come through Renamer, see DESIGN.md). Drop
`McDevBridge/versions/1.20.1-forge/build/libs/mcdevbridge-*.jar` into `versions/1.20.1-forge/run/mods` (a
dev-workspace jar: Mojang names, so it only works in the dev run, never in an installed Forge 47).

- **World.** The node has no saves. Boot `.\smoke.ps1 -Task :1.20.1-forge:runServer` once, copy
  `versions/1.20.1-forge/run-server/world` to `run/saves/SophTest` (a flat world: no crafting table anywhere, which
  the gate test needs). Do not reuse a world saved with the Sophisticated mods for the no-Sophisticated boot
  (Forge asks to confirm the missing registry entries): move it aside and use a fresh copy (`PlainTest`).
- **Bridge on this node.** `/input {"interactive":false}` first, else the unfocused game sits on the pause screen.
  `gamemode survival @p`, `difficulty peaceful`, `gamerule keepInventory true` (a slime killed the dev player
  mid-session). `/command` has no entity, so address the player as `@p`. Item ids are
  `sophisticatedstorage:barrel` / `sophisticatedstorage:crafting_upgrade` and `sophisticatedbackpacks:backpack` /
  `sophisticatedbackpacks:crafting_upgrade`; the backpack opens by right-clicking the sky (`/look` pitch -80) with
  it selected (`key.hotbar.N`). In the storage and backpack menus the hotbar is `/slotclick` slots 54 to 62.
  Screen fractions (854x480): upgrade slot 0.275/0.2125, its tab 0.726/0.318, JEI search box 0.834/0.931 (type
  `oak door`), first result 0.882/0.265 with the tab open and 0.796/0.265 closed, the recipe's "+" 0.664/0.585,
  the upgrade's result slot 0.773/0.783 (shift-click moves the doors out and empties the grid).
- **Traps.** The vanilla movement-tutorial toast covers the grid's top row, so a stray item in the top-left cell
  is invisible: judge by `/inventory` counts (the cobblestone must return to 16). The first JEI item clicked in
  the unfiltered list is a spruce / dark-oak door; those are 6 planks of a wood you lack, i.e. the orange gather
  button, which is a free test of that state. JEI logs "Received invalid gui properties for screen:
  MissingIngredientsScreen" (guiXSize 0 and the like: the handler reads the screen's size before its first init)
  when the gather screen opens; it also happens at a vanilla table with no Sophisticated on the classpath, so it
  is not part of this compat.
- **Result, with `-Psc.sophisticated=true`.** Barrel and backpack both: yellow "+" with the tab open and closed;
  open tab -> "Oak Door done in the crafting grid" and the door layout sits in the upgrade's grid; closed tab ->
  "Crafted Oak Door" into the inventory; closing the screen right after the click leaves the planks crafted and
  the door step pending, reopening the barrel finishes it; a stray cobblestone in the grid is returned first;
  planks in the inventory make the "+" white "Move Items" and the mod's own packet fills the grid; no logs -> orange
  and the gather list. Without the flag the same client boots with no Sophisticated line in the log and the
  vanilla table path unchanged; `smoke.ps1 -Task :1.20.1-forge:runServer` passes with and without the flag.

### Production check (no 1.20.1 Forge dev-workspace bridge exists for an installed game)

An installed Forge 47 runs SRG names, so the bridge stays off there and only boots and static linking can be
checked. Three layers were used on the shipped `build/libs/sprawlcrafting-<ver>+mc1.20.1-forge.jar`:

1. `javap -c -p` on `compat/sophisticated/*`: Minecraft members are SRG (`RecipeType.f_44107_`), Sophisticated's own
   members (`getOpenContainer`, `getOpenOrFirstCraftingContainer`, `getRecipeSlots`, ...) are unchanged.
2. A static linkage pass over every class in the jar (3422 member and class references): each must resolve in a
   production classpath made of Forge 47.4.29's SRG client jar and universal jar, the libraries of the Forge
   version JSON, JEI 15.56, Core, Storage, Backpacks and the nested solver / MixinExtras jars (a one-off ASM
   program, not committed; JDK references are not checked; counts are from the #32 run). The Mojmap `-slim` jar fails the same pass with 330 unresolved
   references, the shipped jar with none.
3. A real SRG Forge 47.4.29 client booted offline in a scratch game directory (the Modrinth app's `meta/libraries`
   and its Java 17 are read only; the installer-processor libraries the version JSON appends must be left off
   the classpath, and duplicate jars removed) with the jar plus the user's mods (JEI 15.56, AE2, GuideME, IMDS
   Reborn, Sophisticated Core and Storage), then again with Backpacks: "SprawlCrafting init on Forge (production)",
   "Sophisticated Core found", JEI's transfer-handler registration completes, no linkage error. Errors in that log
   are not ours: Storage's cross-mod advancements name Backpacks items when Backpacks is absent, and IMDS Reborn's
   void sword breaks JEI's anvil helper.

What production did not cover: clicking through the screens (no bridge), which the dev client did.
