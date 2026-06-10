A mod that streamlines in-inventory and crafting table crafting by crafting all dependent intermediate recipe items, then the final result, Factorio style.

Components are crafted in half-second intervals.

Works with vanilla crafting book and JEI/REI.

1.21.1 and 26.1.2, Neoforge and Fabric.

In the Recipe Book, recipes with yellow outlines (in vanilla, red means not enough resources, white means craftable) mean you have all of the required raw resources, but not the exact intermediates. While crafting, you can close your inventory, and you will see the progress/currently crafted item in the top right (like one of those potion, debuff, advancement, tutorial box flyout things)

## Development

MultiLoader layout: shared code lives in `common/`, loader entry points in `fabric/` and `neoforge/`. Currently targets Minecraft 1.21.1; see [DESIGN.md](DESIGN.md) for architecture and decisions.

Requires Java 21 (e.g. `C:\Program Files\Java\jdk-21.0.2`).

```
./gradlew build                 # builds both loader jars into */build/libs
./gradlew :neoforge:runClient   # dev client on NeoForge
./gradlew :fabric:runClient     # dev client on Fabric
```