package com.legoj15.sprawlcrafting.forge.compat.jei;

import com.legoj15.sprawlcrafting.forge.client.GuiMissingResources;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.recipe.VanillaRecipeCategoryUid;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IRecipeTransferRegistry;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerPlayer;
import net.minecraft.inventory.ContainerWorkbench;
import net.minecraftforge.fml.common.Loader;

/**
 * JEI integration entry point. Discovered by JEI's {@code @JEIPlugin} scan (client-only); never
 * loaded when JEI is absent, so JEI stays a soft, compile-only dependency. Registers the deferred
 * craft transfer handler for the 2x2 inventory grid, the 3x3 table, known modded crafting
 * containers (FastWorkbench, TConstruct Crafting Station), and — via a class-less structural
 * fallback resolved by {@code RecipeRegistryMixin} — any other container that reads as a 3x3
 * crafter when opened (e.g. a slab-mod Crafting Station).
 *
 * <p>JEI's registry is last-write-wins, and {@code @JEIPlugin} iteration order is an unordered
 * {@code Set} — so another mod's handler can silently overwrite ours. To guarantee priority,
 * handler instances are also stored in {@link DeferredHandlerRegistry}; a Mixin on JEI's
 * {@code RecipeRegistry.getRecipeTransferHandler()} checks that registry first, walking the class
 * hierarchy so every {@code ContainerWorkbench} subclass resolves to our handler automatically.
 */
@JEIPlugin
public class SprawlCraftingJeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {
        IRecipeTransferHandlerHelper helper = registry.getJeiHelpers().recipeTransferHandlerHelper();
        IRecipeTransferRegistry transfer = registry.getRecipeTransferRegistry();

        // Vanilla containers — registered in both JEI's table and DeferredHandlerRegistry.
        registerAndStore(transfer, ContainerWorkbench.class,
                new DeferredCraftTransferHandler<>(ContainerWorkbench.class, helper));
        registerAndStore(transfer, ContainerPlayer.class,
                new DeferredCraftTransferHandler<>(ContainerPlayer.class, helper));

        // FastWorkbench: extends ContainerWorkbench, so grid context auto-detects as 3x3.
        // The Mixin's hierarchy walk catches all ContainerWorkbench subclasses via the
        // registration above, but explicit table entries help if the Mixin doesn't apply.
        registerModdedCrafter(transfer, helper, "fastbench",
                "shadows.fastbench.gui.ContainerFastBench", null);
        registerModdedCrafter(transfer, helper, "fastbench",
                "shadows.fastbench.gui.ClientContainerFastBench", null);

        // TConstruct Crafting Station: does NOT extend ContainerWorkbench, needs explicit 3x3.
        registerModdedCrafter(transfer, helper, "tconstruct",
                "slimeknights.tconstruct.tools.common.inventory.ContainerCraftingStation",
                GridContext.CRAFTING_TABLE);

        // Structural fallback: a class-less 3x3 handler the RecipeRegistryMixin hands back for any
        // *open* container that reads as a 3x3 crafter but isn't registered above — an unknown
        // modded station (e.g. a Slab Machines Crafting Station slab). It is never added to JEI's
        // own class table (that would wrongly claim every container); only our mixin returns it,
        // after verifying the open container structurally.
        DeferredHandlerRegistry.setStructuralFallback(
                new DeferredCraftTransferHandler<>(Container.class, helper, GridContext.CRAFTING_TABLE));

        // The gather screen as a JEI-active screen: R/U on its items, panel-aware overlay.
        // Needs-helper-gated at runtime inside the handler; both registrations exist in JEI 4.15
        // (SF4's version) and 4.16 alike — javap-verified.
        GatherListGuiHandler gatherHandler = new GatherListGuiHandler();
        registry.addGuiScreenHandler(GuiMissingResources.class, gatherHandler);
        registry.addGlobalGuiHandlers(gatherHandler);
    }

    private static <C extends Container> void registerAndStore(IRecipeTransferRegistry transfer,
            Class<C> containerClass, DeferredCraftTransferHandler<C> handler) {
        transfer.addRecipeTransferHandler(handler, VanillaRecipeCategoryUid.CRAFTING);
        DeferredHandlerRegistry.register(containerClass, handler);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerModdedCrafter(IRecipeTransferRegistry transfer,
            IRecipeTransferHandlerHelper helper, String modId, String containerClassName,
            GridContext gridOverride) {
        if (!Loader.isModLoaded(modId)) {
            return;
        }
        try {
            Class cls = Class.forName(containerClassName);
            DeferredCraftTransferHandler handler =
                    new DeferredCraftTransferHandler(cls, helper, gridOverride);
            transfer.addRecipeTransferHandler(handler, VanillaRecipeCategoryUid.CRAFTING);
            DeferredHandlerRegistry.register(cls, handler);
        } catch (ClassNotFoundException ignored) {
            // Mod's internal layout changed; silently skip.
        }
    }
}
