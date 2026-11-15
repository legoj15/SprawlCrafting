package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
//? if >=1.21.11 {
/*import net.minecraft.world.inventory.AbstractCraftingMenu;*/
//?} else {
import net.minecraft.world.inventory.RecipeBookMenu;
//?}

/**
 * "Which crafting grid, if any, is this menu showing?" — the one place that knows. Everything that
 * used to test {@code instanceof RecipeBookMenu} / {@code AbstractCraftingMenu} and then assume the
 * inputs are menu slots 1..w*h and the result is slot 0 (grid-size detection, the 26.x craftability
 * sync gate, the client's open-grid report, clearing the grid, the final-step hand-off, the execution
 * gate) asks here instead, so a modded menu whose grid lives elsewhere (the Sophisticated crafting
 * upgrade, whose slots are not even in {@code menu.slots}) is a registered {@link Provider}, not a
 * change at each of those sites.
 *
 * <p>The built-in resolver is vanilla's: the recipe-book menus (inventory 2×2, crafting table 3×3, and
 * any mod menu built on the same class). Compat code adds providers at mod init, and only when the mod
 * it adapts is loaded, so this class never mentions a modded type and loads without any of them.
 */
public final class CraftingGrids {

    /** One open crafting grid. Resolve it per use; do not keep it across menus. */
    public interface Grid {

        int width();

        int height();

        /** The input cells, row-major, {@code width() * height()} of them. */
        List<Slot> inputs();

        /** The output slot; reading it after the inputs change shows what the grid would craft. */
        Slot result();

        /**
         * True when the menu is a vanilla recipe-book menu, whose {@code handlePlacement} lays a recipe out
         * (with its own clear-the-grid and full-inventory rules). False means the executor lays the grid out
         * itself through {@link Slot#set}.
         */
        boolean recipeBookPlacement();

        /**
         * Whether the grid is on screen right now, so a finished craft handed to it is something the player
         * can see and take (server-side check). A grid that exists but sits behind a closed tab is still a
         * grid for planning and for the execution gate; it just must not receive the final hand-off.
         */
        boolean visible();
    }

    /** Resolves a menu to its crafting grid, or null when the menu is not one this provider knows. */
    public interface Provider {
        Grid find(AbstractContainerMenu menu);
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private CraftingGrids() {
    }

    /** Adds a resolver for a modded menu; called from mod init, after the adapted mod is known to be loaded. */
    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    /** The grid {@code menu} shows, or null when it has none. Providers first, then vanilla. */
    public static Grid find(AbstractContainerMenu menu) {
        if (menu == null) {
            return null;
        }
        for (Provider provider : PROVIDERS) {
            Grid grid = provider.find(menu);
            if (grid != null) {
                return grid;
            }
        }
        return vanilla(menu);
    }

    /** A grid big enough for a full-grid (3×3) recipe: a crafting table, vanilla or modded. */
    public static boolean isFullGrid(Grid grid) {
        return grid != null && grid.width() >= 3 && grid.height() >= 3;
    }

    private static Grid vanilla(AbstractContainerMenu menu) {
        //? if >=1.21.11 {
        /*if (menu instanceof AbstractCraftingMenu crafting) {
            return VanillaGrid.of(menu, crafting.getGridWidth(), crafting.getGridHeight());
        }*/
        //?} else {
        // RecipeBookMenu also covers the furnaces (1×1, input slot 0); VanillaGrid.of keeps only 2×2 and up.
        if (menu instanceof RecipeBookMenu<?, ?> recipeBook) {
            return VanillaGrid.of(menu, recipeBook.getGridWidth(), recipeBook.getGridHeight());
        }
        //?}
        return null;
    }

    /** Vanilla layout, identical in InventoryMenu and CraftingMenu on every version: result slot 0, inputs 1..w*h. */
    private record VanillaGrid(AbstractContainerMenu menu, int width, int height) implements Grid {

        private static final int RESULT_SLOT = 0;

        static Grid of(AbstractContainerMenu menu, int width, int height) {
            if (width < 2 || height < 2 || menu.slots.size() < 1 + width * height) {
                return null;
            }
            return new VanillaGrid(menu, width, height);
        }

        @Override
        public List<Slot> inputs() {
            List<Slot> inputs = new ArrayList<>(width * height);
            for (int i = 0; i < width * height; i++) {
                inputs.add(menu.getSlot(RESULT_SLOT + 1 + i));
            }
            return Collections.unmodifiableList(inputs);
        }

        @Override
        public Slot result() {
            return menu.getSlot(RESULT_SLOT);
        }

        @Override
        public boolean recipeBookPlacement() {
            return true;
        }

        @Override
        public boolean visible() {
            return true;
        }
    }
}
