package com.legoj15.sprawlcrafting.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.legoj15.sprawlcrafting.forge.SprawlConfig;
import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.client.DeferredClickState;
import com.legoj15.sprawlcrafting.forge.client.GuiMissingResources;
import com.legoj15.sprawlcrafting.forge.client.ServerPresence;
import com.legoj15.sprawlcrafting.forge.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.forge.craft.CraftJob;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.ExternalSlots;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.ShortfallView;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.recipebook.GuiRecipeBook;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;

/**
 * Recipe-book click + preview integration. The left-click on a recipe is diverted by its
 * SprawlCrafting craftability (computed by the client solver):
 * <ul>
 *   <li><b>Deferred</b> (yellow): two-click — the first click arms the recipe and shows a plan
 *       preview ({@link DeferredClickState}); the second click confirms and starts it.</li>
 *   <li><b>Unsolvable</b> (red): opens the {@link GuiMissingResources} gather screen for it.</li>
 *   <li><b>Direct</b> (white): laid into the grid by the mod's server-side placer when the
 *       server runs the mod (NBT- and station-chest-capable); vanilla placement on modless
 *       servers.</li>
 * </ul>
 * Both injectors are guarded so they can never throw from the click/render path, and carry
 * {@code require = 0} so a mapping mismatch degrades to vanilla rather than crashing.
 */
@Mixin(GuiRecipeBook.class)
public class GuiRecipeBookMixin {

    @Redirect(
            method = "mouseClicked",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/PlayerControllerMP;"
                            + "func_194338_a(ILnet/minecraft/item/crafting/IRecipe;Z"
                            + "Lnet/minecraft/entity/player/EntityPlayer;)V"),
            require = 0)
    private void sprawlcrafting$divertClick(PlayerControllerMP controller, int windowId,
                                            IRecipe recipe, boolean shift, EntityPlayer player) {
        try {
            if (recipe != null && recipe.getRegistryName() != null) {
                ResourceLocation id = recipe.getRegistryName();
                GridContext grid = GridContext.current(player);
                CraftPlanner.Session session = ClientPlanCache.get(player, grid);
                CraftPlanner.Craftability craftability = session.classify(recipe);

                if (craftability == CraftPlanner.Craftability.DEFERRED) {
                    if (DeferredClickState.isArmedFor(id)) {
                        SprawlNetwork.startByRecipe(id);
                        DeferredClickState.disarm();
                    } else {
                        CraftPlanner.PlanOutcome outcome = session.plan(recipe);
                        CraftJob plan = outcome instanceof CraftPlanner.PlanOutcome.Planned
                                ? ((CraftPlanner.PlanOutcome.Planned) outcome).job() : null;
                        DeferredClickState.arm(id, plan);
                        // Ghost the recipe into the real grid alongside the tooltip preview —
                        // vanilla's own uncraftable-click visual, and modern's first-click look.
                        // Vanilla cleared any previous ghost just before the call we redirect.
                        ((GuiRecipeBook) (Object) this)
                                .setupGhostRecipe(recipe, player.openContainer.inventorySlots);
                    }
                    return;
                }
                if (craftability == CraftPlanner.Craftability.UNSOLVABLE && SprawlConfig.needsSystem) {
                    DeferredClickState.disarm();
                    ShortfallView shortfall = CraftPlanner.shortfall(player, recipe);
                    Minecraft mc = Minecraft.getMinecraft();
                    mc.displayGuiScreen(new GuiMissingResources(shortfall, mc.currentScreen));
                    return;
                }
                // DIRECT (white): when the server runs the mod, place through the server-side
                // placer — identical vanilla recipe-book semantics, but it can also pull
                // NBT-carrying ingredients (filled buckets, which vanilla's tag-stripped unpack
                // can never retrieve) and the open station's bound chest. Same path the JEI "+"
                // uses, so the two entry points cannot disagree.
                if (ServerPresence.active()) {
                    DeferredClickState.disarm();
                    SprawlNetwork.placeRecipe(id, windowId, shift);
                    return;
                }
                // Modless server: vanilla placement fills from the player inventory alone, so a
                // recipe that is direct only thanks to an open station's connected chest is routed
                // through the engine instead. Plain player-direct recipes fall through to vanilla.
                if (ExternalSlots.present(player)
                        && !CraftExecutor.canCraftFromPlayerInventory(player, recipe)) {
                    DeferredClickState.disarm();
                    SprawlNetwork.startByRecipe(id);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to vanilla placement on any error.
        }
        DeferredClickState.disarm();
        controller.func_194338_a(windowId, recipe, shift, player);
    }
}
