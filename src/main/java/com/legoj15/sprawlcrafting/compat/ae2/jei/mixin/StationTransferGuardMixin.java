package com.legoj15.sprawlcrafting.compat.ae2.jei.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.legoj15.sprawlcrafting.Constants;
import com.legoj15.sprawlcrafting.compat.ae2.jei.StationTransferHandler;
import com.legoj15.sprawlcrafting.craft.StationStorages;

import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;

/**
 * Keeps {@code StationTransferHandler} the JEI transfer handler for station menus on Fabric, whatever
 * order Fabric Loader runs JEI plugins in. JEI keeps the LAST handler registered per (menu class,
 * recipe type); on Forge the optional AFTER dependency on ae2 in mods.toml makes our plugin register
 * after AE2's, but Fabric has no equivalent: Loader shuffles entrypoints in dev and guarantees no
 * order in production, so if AE2's plugin runs after ours its stock {@code UseCraftingRecipeTransfer}
 * overwrites ours and the deferred-craft paths silently disappear (the original "Found no compatible
 * items" bug). This mixin therefore cancels JEI's registration of any OTHER crafting handler for a
 * station menu class: whichever plugin runs first, ours stays. AE2's stock handler itself is not lost
 * — {@code Ae2JeiStock} builds the same class directly as our delegate, never through this
 * registration. Forge needs none of this (AFTER ordering already wins the race) and the config ships
 * on Fabric only, but applying it there too would be harmless for the same reason.
 *
 * <p>{@code @Pseudo} + a {@code required: false} config + {@code require = 0} everywhere: JEI is
 * optional at runtime and this pokes a JEI-internal class, so a JEI rewrite must degrade to the
 * plain ordering race, never a crash. The class lives in {@code compat/ae2/jei} so nodes without
 * AE2 or JEI drop it with the rest of the package. The target method is generic
 * {@code <C, R>}; the handler below matches its erased parameters, which is why it is written
 * against wildcards rather than the type variables.
 */
@Pseudo
@Mixin(targets = "mezz.jei.library.load.registration.RecipeTransferRegistration", remap = false)
public abstract class StationTransferGuardMixin {

    @Inject(method = "addRecipeTransferHandler(Lmezz/jei/api/recipe/transfer/IRecipeTransferHandler;Lmezz/jei/api/recipe/RecipeType;)V",
            at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void sprawlcrafting$keepStationHandler(IRecipeTransferHandler<?, ?> handler,
            RecipeType<?> recipeType, CallbackInfo ci) {
        if (handler instanceof StationTransferHandler || !RecipeTypes.CRAFTING.equals(recipeType)) {
            return;
        }
        Class<?> containerClass = handler.getContainerClass();
        for (StationStorages.StationMenu station : StationStorages.menus()) {
            if (station.menuClass().equals(containerClass)) {
                // One line per refused registration (AE2 registers for the wired and the wireless
                // terminal): nothing is lost, our handler fronts an identical stock instance.
                Constants.LOG.debug("SprawlCrafting: refused JEI transfer handler {} for crafting station menu {}",
                        handler.getClass().getName(), containerClass.getName());
                ci.cancel();
                return;
            }
        }
    }
}
