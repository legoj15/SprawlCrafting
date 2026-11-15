package com.legoj15.sprawlcrafting.compat.sophisticated;

import java.util.List;
import java.util.Optional;

import com.legoj15.sprawlcrafting.craft.CraftingGrids;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.RecipeType;
import net.p3pp3rf1y.sophisticatedcore.common.gui.ICraftingContainer;
import net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase;
import net.p3pp3rf1y.sophisticatedcore.common.gui.UpgradeContainerBase;

/**
 * Resolves the crafting grid of a Sophisticated storage or backpack menu: the crafting upgrade's 3×3.
 *
 * <p>Uses the Core API the mods' own JEI handler uses: {@code getOpenOrFirstCraftingContainer} (the open tab's
 * upgrade, else the first one), whose {@code getRecipeSlots()} are the nine inputs and whose slot list ends
 * with the result slot. Those slots live in the upgrade container, not in {@code menu.slots}, so the
 * executor must write them through {@link Slot#set} (the grid slot's own {@code setChanged} recomputes the
 * result; a write to the wrapper's inventory underneath would not).
 *
 * <p>Which tab is open is read from {@code getOpenContainer()} (the storage wrapper's open tab id, which the
 * client's tab click updates on the server too), NOT from {@code UpgradeContainerBase.isOpen()}: on the
 * server that flag is set when the menu is constructed (and by Core's own JEI transfer), not by a tab click,
 * so a tab the player clicked open during this session reads as closed (found in the dev client).
 */
final class SophisticatedGrids implements CraftingGrids.Provider {

    @Override
    public CraftingGrids.Grid find(AbstractContainerMenu menu) {
        if (!(menu instanceof StorageContainerMenuBase<?> storage)) {
            return null;
        }
        // The open tab's crafting upgrade if that is what is open (also on the server, see above), else the
        // first crafting upgrade, as the mods' own handlers pick it.
        UpgradeContainerBase<?, ?> container = storage.getOpenContainer()
                .filter(open -> open instanceof ICraftingContainer c && c.getRecipeType() == RecipeType.CRAFTING)
                .orElse(null);
        if (container == null) {
            Optional<? extends UpgradeContainerBase<?, ?>> found =
                    storage.getOpenOrFirstCraftingContainer(RecipeType.CRAFTING);
            container = found.orElse(null);
        }
        if (!(container instanceof ICraftingContainer crafting)) {
            return null;
        }
        //? if >=1.21.11 {
        /*int width = crafting.getCraftingGridWidth();
        int height = crafting.getCraftingGridHeight();*/
        //?} else {
        int width = 3; // the 1.20.1 / 1.21.1 upgrade is always 3×3 and has no size accessor
        int height = 3;
        //?}
        List<Slot> inputs = crafting.getRecipeSlots();
        List<Slot> all = container.getSlots();
        if (inputs.size() != width * height || all.size() <= inputs.size()
                || !(all.get(inputs.size()) instanceof ResultSlot result)) {
            return null; // not the grid + result layout this adapter was written against
        }
        return new UpgradeGrid(storage, container, width, height, List.copyOf(inputs), result);
    }

    /** The crafting upgrade's grid; not a recipe-book menu, and on screen only while its tab is open. */
    private record UpgradeGrid(StorageContainerMenuBase<?> menu, UpgradeContainerBase<?, ?> container,
                               int width, int height, List<Slot> inputs, Slot result)
            implements CraftingGrids.Grid {

        @Override
        public boolean recipeBookPlacement() {
            return false;
        }

        @Override
        public boolean visible() {
            return menu.getOpenContainer().filter(open -> open == container).isPresent();
        }
    }
}
