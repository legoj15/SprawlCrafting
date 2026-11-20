package com.legoj15.sprawlcrafting.compat.ae2.jei;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.craft.StationStorages;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * Registers {@link StationTransferHandler} for every crafting-station menu a provider registered
 * ({@code StationStorages.menus()}; empty when AE2 is not loaded, so this plugin then does nothing and
 * never touches an AE2 class). A separate {@code @JeiPlugin} from {@code SprawlCraftingJeiPlugin}
 * because this package compiles only on nodes that pin {@code ae2_version}. JEI keys transfer handlers
 * by exact menu class, so each class (AE2's wired and wireless Crafting Terminal) gets its own. AE2 15
 * (1.20.1) and AE2 26.x ship their own JEI handler, so each of ours fronts it for the same menu
 * ({@code Ae2JeiStock}, built here); AE2 19 (1.21.1) does not, so the engine takes every recipe there.
 */
@JeiPlugin
public class StationJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "jei_station_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        for (StationStorages.StationMenu station : StationStorages.menus()) {
            registration.addRecipeTransferHandler(handler(station, registration), RecipeTypes.CRAFTING);
        }
    }

    @SuppressWarnings("unchecked")
    private static <C extends AbstractContainerMenu> StationTransferHandler<C> handler(
            StationStorages.StationMenu station, IRecipeTransferRegistration registration) {
        Class<C> menuClass = (Class<C>) station.menuClass();
        MenuType<C> menuType = (MenuType<C>) station.menuType();
        //? if >=1.21.11 {
        /*// AE2 26.x ships its own JEI handler again (a new package): front it, as on 1.20.1.
        return new StationTransferHandler<>(menuClass, menuType, registration.getTransferHelper(),
                Ae2JeiStock.stock(menuClass, menuType, registration.getTransferHelper()));*/
        //?} else if >=1.20.5 {
        // AE2 19 has no JEI module: no stock handler to front, the engine takes every recipe.
        return new StationTransferHandler<>(menuClass, menuType, registration.getTransferHelper(), null);
        //?} else {
        /*return new StationTransferHandler<>(menuClass, menuType, registration.getTransferHelper(),
                Ae2JeiStock.stock(menuClass, menuType, registration.getTransferHelper()));*/
        //?}
    }
}
