package com.legoj15.sprawlcrafting.client;

import com.legoj15.sprawlcrafting.craft.GridContext;
import com.legoj15.sprawlcrafting.craft.RecipeIds;
import com.legoj15.sprawlcrafting.craft.ShortfallView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Opens the {@link MissingIngredientsScreen} from a trigger site (the JEI transfer button, or a
 * right-clicked red recipe-book entry), keeping the current screen as the parent to return to.
 *
 * <p>1.21.1 solves the gather list locally and opens the screen already populated; 26.x fires a
 * request to the server and opens a loading screen that fills in when the reply lands.
 */
public final class MissingIngredientsView {

    private MissingIngredientsView() {
    }

    /**
     * The screen the gather list returns to on ESC: never a transient recipe viewer, always the
     * underlying container. Prefer the caller's explicit container (REI hands us
     * {@code context.getContainerScreen()}); else the current screen if it is itself a container
     * (recipe-book trigger, and JEI after it tears its viewer down); else the last container the
     * watcher saw (REI keeps its recipe screen frontmost after a transfer, so {@code mc.screen} there
     * is the viewer — adopting it as parent would form an inescapable ESC/E loop with the viewer).
     */
    private static Screen resolveParent(Minecraft mc, Screen explicit) {
        if (explicit instanceof AbstractContainerScreen<?>) {
            return explicit;
        }
        if (mc.screen instanceof AbstractContainerScreen<?>) {
            return mc.screen;
        }
        Screen remembered = CraftingScreenWatcher.lastContainerScreen();
        return remembered != null ? remembered : mc.screen;
    }

    /** Open for a recipe the caller holds directly (the JEI/REI transfer button, both versions). */
    public static void open(RecipeHolder<?> holder, GridContext grid) {
        open(holder, grid, null);
    }

    /**
     * As {@link #open(RecipeHolder, GridContext)}, but with the container screen the trigger already
     * knows (REI's {@code context.getContainerScreen()}). REI leaves its recipe screen frontmost after
     * a successful transfer, so resolving the parent from {@code mc.screen} alone would capture the
     * viewer; the explicit container anchors ESC back to the table.
     */
    public static void open(RecipeHolder<?> holder, GridContext grid, Screen explicitParent) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = resolveParent(mc, explicitParent);
        //? if >=1.21.11 {
        /*int token = MissingIngredients.requestByRecipe(RecipeIds.id(holder));
        mc.setScreen(new MissingIngredientsScreen(parent, null, token));*/
        //?} else {
        ShortfallView view = MissingIngredients.shortfallLocal(holder, grid);
        mc.setScreen(new MissingIngredientsScreen(parent, view, 0));
        //?}
    }

    //? if >=1.21.11 {
    /*// Open for a recipe-book entry on 26.x, which the client knows only by its opaque display id.
    public static void open(net.minecraft.world.item.crafting.display.RecipeDisplayId id) {
        Minecraft mc = Minecraft.getInstance();
        int token = MissingIngredients.requestByDisplay(id);
        mc.setScreen(new MissingIngredientsScreen(resolveParent(mc, null), null, token));
    }*/
    //?}
}
