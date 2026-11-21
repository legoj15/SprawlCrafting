package com.legoj15.sprawlcrafting.forge.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;

/**
 * Recipe-book click + preview integration. The left-click on a recipe is diverted by its
 * SprawlCrafting craftability (computed by the client solver):
 * <ul>
 *   <li><b>Deferred</b> (yellow): two-click — the first click arms the recipe
 *       ({@link DeferredClickState}) and ghosts it into the grid; the second click confirms and
 *       starts it. Vanilla's own ghost guard would swallow that second click, so it is bypassed
 *       ({@link #sprawlcrafting$deferredPassesGhostGuard}). A recipe only the protected tier can
 *       make (issue #40) is deferred too: its preview warns which special items the plan consumes
 *       and its confirm sends the protected start packet.</li>
 *   <li><b>Unsolvable</b> (red): opens the {@link GuiMissingResources} gather screen for it.</li>
 *   <li><b>Direct</b> (white): laid into the grid by the mod's server-side placer when the
 *       server runs the mod (NBT- and station-chest-capable); vanilla placement on modless
 *       servers.</li>
 * </ul>
 * Stale armed state and stale ghosts are also cleaned up here: init disarms whatever the previous
 * screen left armed ({@link #sprawlcrafting$resetOnInit}), and a ghost is cleared as soon as a
 * real item arrives under it ({@link #sprawlcrafting$clearGhostOverItems}).
 * Our own logic in every injector is guarded so it can never throw from the click/render path
 * (the guard redirect still makes vanilla's own {@code isCraftable} call), and every injector
 * carries {@code require = 0} so a mapping mismatch degrades to vanilla rather than crashing.
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
                // Protected-aware classification (issue #40): a recipe only the protected tier can
                // make reads DEFERRED here too, so the first click arms it and the preview tooltip
                // carries the special-items warning.
                CraftPlanner.Craftability craftability = ClientPlanCache.classify(player, grid, recipe);

                if (craftability == CraftPlanner.Craftability.DEFERRED) {
                    if (DeferredClickState.isArmedFor(recipe)) {
                        if (ClientPlanCache.isProtectedOnly(recipe)) {
                            // The confirmed plan consumed protected stacks (#40): the protected
                            // start packet, which only a server that advertised the capability is
                            // ever sent one (isProtectedOnly implies the offer was on).
                            SprawlNetwork.startProtectedByRecipe(id);
                        } else {
                            SprawlNetwork.startByRecipe(id);
                        }
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
     * preview ghost on confirm. The armed recipe passes too, even once it stops being deferred
     * (its materials left the inventory): our preview ghost still holds it, and a grouped button
     * stays locked onto it, so swallowing the click would strand the button until the screen
     * reopens; passing lets {@code divertClick} route it as red (gather screen) or white and
     * disarm. {@link RecipeBookHighlight#isDeferred} already swallows its own failures.
     * {@code isCraftable} is called only once in {@code mouseClicked}.
     */
    @Redirect(
            method = "mouseClicked",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/recipebook/RecipeList;"
                            + "isCraftable(Lnet/minecraft/item/crafting/IRecipe;)Z"),
            require = 0)
    private boolean sprawlcrafting$deferredPassesGhostGuard(RecipeList list, IRecipe recipe) {
        return list.isCraftable(recipe) || DeferredClickState.isArmedFor(recipe)
                || RecipeBookHighlight.isDeferred(recipe);
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

    /** The ghost recipe whose covered slots {@link #sprawlcrafting$ghostStacks} tracks. */
    @Unique
    private IRecipe sprawlcrafting$trackedGhost;

    /** Per ghost ingredient, the stack last seen in the slot under it (EMPTY when none). */
    @Unique
    private ItemStack[] sprawlcrafting$ghostStacks;

    /**
     * Clears a ghost once a real item lands under it. Vanilla only clears the ghost on a grid-slot
     * click or another recipe click, so the stack the deferred craft's grid hand-off lays in (or a
     * JEI start) was drawn underneath a stale red ghost. Triggered by an ARRIVAL, not by mere
     * overlap: vanilla sends its own not-craftable ghost before the slot updates that empty the
     * grid (a tick later on a modless server), so the ghost briefly sits over items that are about
     * to leave and must survive that. Each frame compares the stack under every ghost ingredient
     * with the one seen last frame: a non-empty stack that is a new object (the client replaces a
     * slot's stack on every server update) is an arrival. A first sighting of a ghost only records.
     * The ghost's positions are slot {@code xPos}/{@code yPos} ({@code setupGhostRecipe} stores
     * exactly those), so matching them against the open container's slots finds what is under it.
     */
    @Inject(method = "renderGhostRecipe", at = @At("HEAD"), require = 0)
    private void sprawlcrafting$clearGhostOverItems(int left, int top, boolean bigResultSlot,
                                                    float partialTicks, CallbackInfo ci) {
        try {
            GhostRecipe ghost = ((GuiRecipeBookAccessor) (Object) this).sprawlcrafting$getGhostRecipe();
            EntityPlayer player = Minecraft.getMinecraft().player;
            if (ghost == null || ghost.size() == 0 || player == null || player.openContainer == null) {
                sprawlcrafting$trackedGhost = null;
                return;
            }
            List<Slot> slots = player.openContainer.inventorySlots;
            int size = ghost.size();
            ItemStack[] now = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                GhostRecipe.GhostIngredient ingredient = ghost.get(i);
                now[i] = ItemStack.EMPTY;
                for (Slot slot : slots) {
                    if (slot.xPos == ingredient.getX() && slot.yPos == ingredient.getY()
                            && slot.getHasStack()) {
                        now[i] = slot.getStack();
                        break;
                    }
                }
            }
            ItemStack[] seen = sprawlcrafting$ghostStacks;
            if (ghost.getRecipe() != sprawlcrafting$trackedGhost || seen == null || seen.length != size) {
                sprawlcrafting$trackedGhost = ghost.getRecipe();
                sprawlcrafting$ghostStacks = now;
                return;
            }
            for (int i = 0; i < size; i++) {
                if (!now[i].isEmpty() && now[i] != seen[i]) {
                    ghost.clear();
                    sprawlcrafting$trackedGhost = null;
                    return;
                }
            }
            sprawlcrafting$ghostStacks = now;
        } catch (Throwable ignored) {
            // Never let the guard break ghost rendering; vanilla draws the (possibly stale) ghost.
        }
    }
}
