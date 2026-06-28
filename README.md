# Sprawl Crafting

<sub>*You have your ingredients sprawled out, you just need to put them together.* Or something. IDK</sub>

Factorio-style crafting for the vanilla inventory and crafting table. Request a final item and SprawlCrafting automatically crafts every intermediate from your raw resources, then the final result.

Works with the vanilla recipe book and with JEI/REI.

**Minecraft 1.21.1, 26.1.2, and 26.2 · Fabric and NeoForge.**

## How it works

In the recipe book, the outline color tells you the state of each recipe:

- **White:** Craftable right now (vanilla, unchanged)
- **Red:** Required resources missing (vanilla, with added functionality)
- **Yellow (Sprawl Crafting):** You possess the raw materials to craft the end result (what this mod adds)

Yellow recipes are included in the recipe book's "show craftable" filter, so everything you can actually make shows up together.

While a craft runs you can close your inventory, or leave the screen on the crafting grid. A flyout in the top-right corner (the same toast style as advancements and tutorial hints) shows the item currently being crafted and the overall progress.

Intermediates are crafted as **real items** into your inventory and consumed by later steps, so a craft is crash-safe and visible to other mods. An item going missing mid-craft cancels the operation.

> [!NOTE]
> If you leave the crafting GUI up the entire time, the final result is placed into the crafting grid, matching what clicking a recipe in the recipe book or the + button in JEI/REI would do. Don't forget to grab the results!

### Crafting tables

Crafting from your inventory plans **2×2** chains; crafting from a table plans the full **3×3**. Steps that need the 3×3 grid can only be started from a crafting table, but you can close the screen and it will continue as long as you stay within interaction distance of any table. 2×2 steps craft anywhere.

### Finding what you're missing

Right-click a **red** recipe (or left-click the **orange** gather button in JEI/REI) to open a list of the raw materials you still need to gather for it.

## Compatibility

- **Minecraft:** 1.21.1, 26.1.2, or 26.2
- **Loaders:** Fabric or NeoForge
- **Fabric** also requires **Fabric API** (like most mods)
- **Optional integrations:**
  - **JEI** - adds the deferred-craft and gather buttons (all Minecraft versions)
  - **REI** - same buttons (1.21.1 only)
  - **Mod Menu** - in-game config button (Fabric only; all Minecraft versions)

Multiplayer is supported; install the mod on both the client and the server. A vanilla client can still join a SprawlCrafting server (it just won't see any of the features), and a SprawlCrafting client on a vanilla server simply gets no deferred-craft offers.

## Configuration

Settings live in `config/sprawlcrafting.json` and can also be changed in-game (Mod Menu on Fabric, the mod-config screen on NeoForge); edits apply immediately. All four default to on:

| Setting | What it does |
|---|---|
| `sound_effects` | the per-step craft "pop" sound |
| `jei_integration` | master switch for all JEI buttons |
| `rei_integration` | master switch for all REI buttons |
| `needs_system` | the "what do I still need" screen, right-clicking red recipes, the tooltip hint, and the orange gather button |

## Building from source

SprawlCrafting uses Stonecutter: one shared source set in `src/`, preprocessed per version into `versions/<node>/`. The six nodes are `1.21.1-fabric`, `1.21.1-neoforge`, `26.1.2-fabric`, `26.1.2-neoforge`, `26.2-fabric`, and `26.2-neoforge`.

**The required JDK depends on the Minecraft version:**

- **1.21.1** nodes need **JDK 21**
- **26.1.2** and **26.2** nodes need **JDK 25** (Minecraft 26.1+ requires the Java 25 toolchain)

Build or run a single node with its node-prefixed task:

```
./gradlew :1.21.1-fabric:build        # jar -> versions/1.21.1-fabric/build/libs
./gradlew :26.1.2-neoforge:build      # jar -> versions/26.1.2-neoforge/build/libs

./gradlew :1.21.1-fabric:runClient    # dev client, Fabric 1.21.1
./gradlew :26.1.2-neoforge:runClient  # dev client, NeoForge 26.1.2
```

Swap the prefix for any of the four nodes.

## License

Licensed under the [MIT License](LICENSE).
