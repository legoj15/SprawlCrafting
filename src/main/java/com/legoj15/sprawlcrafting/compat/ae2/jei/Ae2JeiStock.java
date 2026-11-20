package com.legoj15.sprawlcrafting.compat.ae2.jei;

// AE2 15 (the 1.20.1 line) and AE2 26.x each ship their own JEI transfer handler for the Crafting
// Terminal, which StationTransferHandler fronts (see its javadoc); AE2 26 moved it from
// appeng.integration.modules.jei.transfer to appeng.client.integrations.jei.transfer. AE2 19 (the
// 1.21.1 line) has no JEI module, so this class compiles to nothing there (like the craft/legacy
// RecipeHolder shim, the whole body is gated off) and StationJeiPlugin passes a null stock delegate
// instead. This is the only class in the package that names one of AE2's JEI-internals types, keeping
// the plugin itself free of AE2 linkage; it is reached only from the plugin's 1.20.1 and 26.x
// branches, inside its StationStorages.menus() loop — non-empty only with AE2 loaded, so the AE2
// reference is safe.

//? if >=1.21.11 {
/*import appeng.client.integrations.jei.transfer.UseCraftingRecipeTransfer;
import appeng.menu.me.items.CraftingTermMenu;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

final class Ae2JeiStock {

    private Ae2JeiStock() {
    }

    // Same shape as the 1.20.1 body below: both station menus are CraftingTermMenus (the wireless one
    // subclasses it), so the asSubclass always succeeds; the unchecked casts only bridge the plugin's
    // menu generics to AE2's own bound, never a wrong runtime type. AE2's JEI plugin registers the
    // identical handler for both menus; the optional AFTER dependency on ae2 in the mod metadata makes
    // ours the one JEI keeps.
    @SuppressWarnings("unchecked")
    static <C extends AbstractContainerMenu> IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> stock(
            Class<C> menuClass, MenuType<C> menuType, IRecipeTransferHandlerHelper helper) {
        return (IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>>) new UseCraftingRecipeTransfer<>(
                (Class<CraftingTermMenu>) menuClass.asSubclass(CraftingTermMenu.class),
                (MenuType<CraftingTermMenu>) menuType, helper);
    }
}*/
//?}
//? if <1.20.5 {
/*import appeng.integration.modules.jei.transfer.UseCraftingRecipeTransfer;
import appeng.menu.me.items.CraftingTermMenu;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.CraftingRecipe;

final class Ae2JeiStock {

    private Ae2JeiStock() {
    }

    // Both station menus are CraftingTermMenus (the wireless one subclasses it), so the asSubclass
    // always succeeds; the unchecked casts only bridge the plugin's menu generics to AE2's own bound,
    // never a wrong runtime type. AE2's JEIPlugin registers the identical handler for both menus; the
    // optional AFTER dependency on ae2 in the mod metadata makes ours the one JEI keeps.
    @SuppressWarnings("unchecked")
    static <C extends AbstractContainerMenu> IRecipeTransferHandler<C, CraftingRecipe> stock(
            Class<C> menuClass, MenuType<C> menuType, IRecipeTransferHandlerHelper helper) {
        return (IRecipeTransferHandler<C, CraftingRecipe>) new UseCraftingRecipeTransfer<>(
                (Class<CraftingTermMenu>) menuClass.asSubclass(CraftingTermMenu.class),
                (MenuType<CraftingTermMenu>) menuType, helper);
    }
}*/
//?}
