package com.legoj15.sprawlcrafting.compat.jei;

import java.lang.reflect.Constructor;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.client.MissingIngredientsScreen;
import com.legoj15.sprawlcrafting.craft.StationStorages;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaRecipeCategoryUid;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferInfo;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.inventory.container.WorkbenchContainer;
import net.minecraft.util.ResourceLocation;

/**
 * Found by JEI's {@code @JeiPlugin} annotation scan, so this class never loads when JEI is absent.
 *
 * <p>JEI 7 (1.16.5) has no API factory for its builtin "basic" transfer handler, so the direct-transfer
 * path is JEI's internal {@code mezz.jei.transfer.BasicRecipeTransferHandler}, built reflectively from
 * our own {@link IRecipeTransferInfo}. If that internal class ever changes shape, we log and register
 * nothing, leaving JEI's own crafting-table handler in place (no deferred button, but no breakage).
 * Our registration replaces JEI's because JEI loads its vanilla plugin first and the handler table is
 * keyed by (container class, category), last write wins.
 *
 * <p>Scope note: the crafting table screen's handler is replaced, as on the modern nodes, plus each
 * crafting station's (the AE2 Crafting Terminal, {@link StationTransferHandler}); the player 2x2 is covered
 * by the recipe-book integration.
 */
@JeiPlugin
public class SprawlCraftingJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID = Constants.id("jei_plugin");
    private static final String BASIC_HANDLER = "mezz.jei.transfer.BasicRecipeTransferHandler";
    /** AE2 8.x's own Crafting Terminal handler; package-private constructor (Class, helper). */
    private static final String AE2_CRAFTING_HANDLER = "appeng.integration.modules.jei.CraftingRecipeTransferHandler";

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        IRecipeTransferHandlerHelper helper = registration.getTransferHelper();
        registerStations(registration, helper);
        IRecipeTransferHandler<WorkbenchContainer> vanilla = createBasicHandler(registration, helper);
        if (vanilla == null) {
            return;
        }
        registration.addRecipeTransferHandler(
                new DeferredCraftingTransferHandler(vanilla), VanillaRecipeCategoryUid.CRAFTING);
    }

    /**
     * One {@link StationTransferHandler} per crafting-station menu a provider registered (the AE2 Crafting
     * Terminal; the list is empty without AE2, so no AE2 class is touched then). JEI 7 keys handlers by exact
     * container class and category, last write wins, and walks plugins in mod order: the optional AFTER
     * dependency on appliedenergistics2 in mods.toml is what makes ours replace AE2's own registration.
     */
    private static void registerStations(IRecipeTransferRegistration registration, IRecipeTransferHandlerHelper helper) {
        for (Class<? extends Container> menu : StationStorages.menus()) {
            registration.addRecipeTransferHandler(stationHandler(menu, helper), VanillaRecipeCategoryUid.CRAFTING);
        }
    }

    @SuppressWarnings("unchecked")
    private static <C extends Container> StationTransferHandler<C> stationHandler(
            Class<? extends Container> menu, IRecipeTransferHandlerHelper helper) {
        Class<C> menuClass = (Class<C>) menu;
        return new StationTransferHandler<>(menuClass, helper, createStockStationHandler(menuClass, helper));
    }

    /**
     * AE2's own handler for {@code menuClass}, which ours fronts for the direct (green) craft, or null when it
     * cannot be built (the engine then takes every recipe). Reflective because the constructor is
     * package-private and so this plugin never links AE2.
     */
    @SuppressWarnings("unchecked")
    private static <C extends Container> IRecipeTransferHandler<C> createStockStationHandler(
            Class<C> menuClass, IRecipeTransferHandlerHelper helper) {
        try {
            Constructor<?> constructor = Class.forName(AE2_CRAFTING_HANDLER)
                    .getDeclaredConstructor(Class.class, IRecipeTransferHandlerHelper.class);
            constructor.setAccessible(true);
            IRecipeTransferHandler<C> stock = (IRecipeTransferHandler<C>) constructor.newInstance(menuClass, helper);
            // AE2's handler is written for one container class; never front it for a different one.
            return stock.getContainerClass() == menuClass ? stock : null;
        } catch (ReflectiveOperationException | LinkageError | ClassCastException | SecurityException e) {
            Constants.LOG.warn("Could not build AE2's Crafting Terminal JEI handler; SprawlCrafting handles every"
                    + " recipe there", e);
            return null;
        }
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Two halves of making R/U work on the gather-list screen (a plain Screen, not a container):
        // the screen handler marks it as JEI-handled, and the global handler reports the hovered item.
        registration.addGuiScreenHandler(MissingIngredientsScreen.class, GatherListGuiHandler::guiProperties);
        registration.addGlobalGuiHandler(new GatherListGuiHandler());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static IRecipeTransferHandler<WorkbenchContainer> createBasicHandler(
            IRecipeTransferRegistration registration, IRecipeTransferHandlerHelper helper) {
        try {
            Class<?> type = Class.forName(BASIC_HANDLER);
            for (Constructor<?> constructor : type.getConstructors()) {
                if (constructor.getParameterCount() == 3) {
                    return (IRecipeTransferHandler<WorkbenchContainer>) constructor.newInstance(
                            registration.getJeiHelpers().getStackHelper(), helper, new CraftingTableInfo());
                }
            }
            Constants.LOG.warn("JEI's {} has no 3-arg constructor; deferred JEI transfer disabled", BASIC_HANDLER);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            Constants.LOG.warn("Could not build JEI's basic transfer handler; deferred JEI transfer disabled", e);
        }
        return null;
    }

    /** Same slot layout JEI's own VanillaPlugin registers: grid slots 1-9, player inventory 10-45. */
    private static final class CraftingTableInfo implements IRecipeTransferInfo<WorkbenchContainer> {

        @Override
        public Class<WorkbenchContainer> getContainerClass() {
            return WorkbenchContainer.class;
        }

        @Override
        public ResourceLocation getRecipeCategoryUid() {
            return VanillaRecipeCategoryUid.CRAFTING;
        }

        @Override
        public boolean canHandle(WorkbenchContainer container) {
            return true;
        }

        @Override
        public List<Slot> getRecipeSlots(WorkbenchContainer container) {
            return slots(container, 1, 9);
        }

        @Override
        public List<Slot> getInventorySlots(WorkbenchContainer container) {
            return slots(container, 10, 36);
        }

        private static List<Slot> slots(WorkbenchContainer container, int start, int count) {
            ImmutableList.Builder<Slot> out = ImmutableList.builder();
            for (int i = start; i < start + count; i++) {
                out.add(container.getSlot(i));
            }
            return out.build();
        }
    }
}
