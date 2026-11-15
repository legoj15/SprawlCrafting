# Sophisticated crafting-upgrade: in-game check (agent runbook)

How the compat in `compat/sophisticated/` (design: DESIGN.md "Sophisticated crafting upgrade") was verified, so the
check can be repeated. Verified in game on `1.21.1-neoforge` (full scenario) and `26.1.2-neoforge` (yellow "+", hand-off, gather list);
`26.2` / `26.3-neoforge` are compile-checked (see the bottom).

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
