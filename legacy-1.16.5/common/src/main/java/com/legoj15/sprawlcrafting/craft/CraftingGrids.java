package com.legoj15.sprawlcrafting.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.RecipeBookContainer;
import net.minecraft.inventory.container.Slot;

/**
 * "Which crafting grid, if any, is this menu showing?" — the one place that knows (port of the root
 * tree's class). Grid-size detection, the client's open-grid report, clearing the grid and the
 * final-step hand-off ask here instead of testing {@code instanceof RecipeBookContainer} and assuming
 * the inputs are menu slots 1..w*h, so a modded grid laid out elsewhere (the AE2 Crafting Terminal's,
 * which is no recipe-book menu) is a registered {@link Provider}, not a change at each of those sites.
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
         * can see and take.
         */
        boolean visible();
    }

    /** Resolves a menu to its crafting grid, or null when the menu is not one this provider knows. */
    public interface Provider {
        Grid find(Container menu);
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private CraftingGrids() {
    }

    /** Adds a resolver for a modded menu; called from mod init, after the adapted mod is known to be loaded. */
    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    /** The grid {@code menu} shows, or null when it has none. Providers first, then vanilla. */
    public static Grid find(Container menu) {
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

    private static Grid vanilla(Container menu) {
        // RecipeBookContainer also covers the furnaces (1×1, input slot 0); VanillaGrid.of keeps only 2×2 and up.
        if (menu instanceof RecipeBookContainer) {
            RecipeBookContainer<?> recipeBook = (RecipeBookContainer<?>) menu;
            return VanillaGrid.of(menu, recipeBook.getGridWidth(), recipeBook.getGridHeight());
        }
        return null;
    }

    /** Vanilla layout, identical in PlayerContainer and WorkbenchContainer: result slot 0, inputs 1..w*h. */
    private static final class VanillaGrid implements Grid {

        private static final int RESULT_SLOT = 0;

        private final Container menu;
        private final int width;
        private final int height;

        private VanillaGrid(Container menu, int width, int height) {
            this.menu = menu;
            this.width = width;
            this.height = height;
        }

        static Grid of(Container menu, int width, int height) {
            if (width < 2 || height < 2 || menu.slots.size() < 1 + width * height) {
                return null;
            }
            return new VanillaGrid(menu, width, height);
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
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
