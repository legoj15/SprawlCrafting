package com.legoj15.sprawlcrafting.forge.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.legoj15.sprawlcrafting.forge.SprawlConfig;
import com.legoj15.sprawlcrafting.forge.client.ClientPlanCache;
import com.legoj15.sprawlcrafting.forge.client.DeferredClickState;
import com.legoj15.sprawlcrafting.forge.client.GuiMissingResources;
import com.legoj15.sprawlcrafting.forge.client.RecipeBookHighlight;
import com.legoj15.sprawlcrafting.forge.client.ServerPresence;
import com.legoj15.sprawlcrafting.forge.craft.CraftExecutor;
import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.ExternalSlots;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.ShortfallView;
import com.legoj15.sprawlcrafting.forge.network.SprawlNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.recipebook.GhostRecipe;
import net.minecraft.client.gui.recipebook.GuiRecipeBook;
import net.minecraft.client.gui.recipebook.RecipeList;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;

/**
 * Recipe-book click + preview integration. The left-click on a recipe is diverted by its
 * SprawlCrafting craftability (computed by the client solver):
 * <ul>
 *   <li><b>Deferred</b> (yellow): two-click — the first click arms the recipe
 *       ({@link DeferredClickState}) and ghosts it into the grid; the second click confirms and
 *       starts it. Vanilla's own ghost guard would swallow that second click, so it is bypassed
 *       ({@link #sprawlcrafting$deferredPassesGhostGuard}).</li>
 *   <li><b>Unsolvable</b> (red): opens the {@link GuiMissingResources} gather screen for it.</li>
 *   <li><b>Direct</b> (white): laid into the grid by the mod's server-side placer when the
 *       server runs the mod (NBT- and station-chest-capable); vanilla placement on modless
 *       servers.</li>
 * </ul>
 * Stale armed state and stale ghosts are also cleaned up here: init disarms whatever the previous
 * screen left armed ({@link #sprawlcrafting$resetOnInit}), and a ghost found sitting over a real
 * grid item is cleared before it can render ({@link #sprawlcrafting$clearGhostOverItems}).
 * All injectors are guarded so they can never throw from the click/render path, and carry
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
                    if (DeferredClickState.isArmedFor(recipe)) {
                        SprawlNetwork.startByRecipe(id);
                        DeferredClickState.disarm();
                    } else {
                        DeferredClickState.arm(recipe);
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

    /**
     * Ghost-guard bypass. Vanilla {@code mouseClicked} opens with
     * {@code if (!recipelist.isCraftable(irecipe) && this.ghostRecipe.getRecipe() == irecipe) return false;}
     * — a click on a non-craftable recipe whose ghost is showing is swallowed. Our first deferred
     * click sets exactly that ghost ({@code setupGhostRecipe}), so without this the confirm click
     * never reaches {@link #sprawlcrafting$divertClick}. A deferred recipe passes the guard; vanilla
     * then clears the ghost just before the place call we redirect — which is also what clears the
     * preview ghost on confirm. {@link RecipeBookHighlight#isDeferred} already swallows its own
     * failures. {@code isCraftable} is called only once in {@code mouseClicked}.
     */
    @Redirect(
            method = "mouseClicked",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/recipebook/RecipeList;"
                            + "isCraftable(Lnet/minecraft/item/crafting/IRecipe;)Z"),
            require = 0)
    private boolean sprawlcrafting$deferredPassesGhostGuard(RecipeList list, IRecipe recipe) {
        return list.isCraftable(recipe) || RecipeBookHighlight.isDeferred(recipe);
    }

    /**
     * Armed state must not outlive the screen: it is static, so without this reset the first click
     * after closing and reopening the table (fresh ghost, no preview showing) started the last
     * previewed craft instead of arming one. Init runs on every screen open, resize and return
     * from another screen. A ghost still holding the armed recipe is cleared with it, so reopening
     * never inherits the old screen's preview ghost either.
     */
    @Inject(method = "func_194303_a", at = @At("TAIL"), require = 0)
    private void sprawlcrafting$resetOnInit(int width, int height, Minecraft mc, boolean narrow,
                                            InventoryCrafting grid, CallbackInfo ci) {
        try {
            IRecipe armed = DeferredClickState.armed();
            if (armed != null) {
                GhostRecipe ghost = ((GuiRecipeBookAccessor) (Object) this).sprawlcrafting$getGhostRecipe();
                if (ghost != null && ghost.getRecipe() == armed) {
                    ghost.clear();
                }
            }
        } catch (Throwable ignored) {
            // Init must never fail; a stale ghost is cleared on the next real interaction.
        }
        DeferredClickState.disarm();
    }

    /**
     * A ghost is only ever meant to sit over an <em>empty</em> grid (vanilla's server placer
     * empties the grid before sending one), so a ghost found over a real item is always stale —
     * whoever set it (our preview, a server ghost packet) and however the item arrived (the
     * deferred craft's grid hand-off, a JEI start). The ghost's ingredient positions are slot
     * {@code xPos}/{@code yPos} ({@code setupGhostRecipe} stores exactly those), so matching them
     * against the open container's slots finds the overlap; clearing once is enough.
     */
    @Inject(method = "renderGhostRecipe", at = @At("HEAD"), require = 0)
    private void sprawlcrafting$clearGhostOverItems(int left, int top, boolean bigResultSlot,
                                                    float partialTicks, CallbackInfo ci) {
        try {
            GhostRecipe ghost = ((GuiRecipeBookAccessor) (Object) this).sprawlcrafting$getGhostRecipe();
            if (ghost == null || ghost.size() == 0) {
                return;
            }
            EntityPlayer player = Minecraft.getMinecraft().player;
            if (player == null || player.openContainer == null) {
                return;
            }
            List<Slot> slots = player.openContainer.inventorySlots;
            for (int i = 0; i < ghost.size(); i++) {
                GhostRecipe.GhostIngredient ingredient = ghost.get(i);
                for (Slot slot : slots) {
                    if (slot.xPos == ingredient.getX() && slot.yPos == ingredient.getY()
                            && slot.getHasStack()) {
                        ghost.clear();
                        return;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Never let the guard break ghost rendering; vanilla draws the (possibly stale) ghost.
        }
    }
}
