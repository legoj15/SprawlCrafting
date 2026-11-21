package com.legoj15.sprawlcrafting.forge.client;

import java.util.HashSet;
import java.util.Set;

import com.legoj15.sprawlcrafting.forge.craft.CraftPlanner;
import com.legoj15.sprawlcrafting.forge.craft.ExternalSlots;
import com.legoj15.sprawlcrafting.forge.craft.GridContext;
import com.legoj15.sprawlcrafting.forge.craft.NetworkTerminals;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;

/**
 * Reuses one client-side {@link CraftPlanner.Session} across the many JEI "+" validation queries
 * that happen while the player hovers a recipe list. The session (and its expensive producer index)
 * is rebuilt only when the player's main inventory, the open grid, or a connected station inventory
 * (a Crafting Station's chest) changes, keyed by a cheap content signature. Client-only.
 *
 * <p>A second session — the same pool plus the player's protected stacks (enchanted and/or
 * renamed, never damaged; issue #40) — is kept alongside and consulted only when the server has the
 * protected capability ({@link ServerPresence#protectedCraft}) and the inventory holds a protected
 * stack. A recipe only that session can make classifies {@link CraftPlanner.Craftability#DEFERRED}
 * (never direct: vanilla's own placement refuses enchanted/renamed stacks) and is remembered in
 * {@link #isProtectedOnly}, so its confirm click sends the protected start instead of the plain one.
 */
public final class ClientPlanCache {

    private ClientPlanCache() {
    }

    private static CraftPlanner.Session session;
    /**
     * The plain pool plus the player's protected stacks (issue #40); null unless the offer — the
     * server's capability times an inventory that holds a protected stack — is on. Rebuilt in
     * lockstep with the plain session, so the two never disagree about the pool they share.
     */
    private static CraftPlanner.Session protectedSession;
    /** Whether {@link #protectedSession} was built with the offer on, so a flip rebuilds it. */
    private static boolean protectedBuilt;
    private static int signature = Integer.MIN_VALUE;
    private static GridContext cachedGrid;
    /** Recipes we marked deferred only via the protected tier (issue #40); see {@link #classify}. */
    private static final Set<ResourceLocation> protectedOnly = new HashSet<ResourceLocation>();

    public static CraftPlanner.Session get(EntityPlayer player, GridContext grid) {
        int sig = inventorySignature(player);
        if (session == null || sig != signature || grid != cachedGrid) {
            session = CraftPlanner.session(player, grid);
            signature = sig;
            cachedGrid = grid;
            // The twin pool moved too, and so may the offer: drop both and let the next
            // protected query rebuild them (the remembered distinctions die with them).
            protectedSession = null;
            protectedBuilt = false;
            protectedOnly.clear();
        }
        return session;
    }

    /**
     * The plain session's protected twin (issue #40), or null when the offer is off — the server
     * has not advertised the capability, or the 36 main slots hold no protected stack. Everything
     * else keeps the plain answer, and any change that flips either (a rebuilt session, a
     * capability flip on reconnect) also invalidates this, so it cannot go stale.
     */
    public static CraftPlanner.Session protectedSession(EntityPlayer player, GridContext grid) {
        get(player, grid); // keeps the twin in lockstep with the plain session's key
        boolean offer = ServerPresence.protectedCraft() && CraftPlanner.hasProtected(player.inventory);
        if (protectedSession == null || offer != protectedBuilt) {
            protectedSession = offer ? CraftPlanner.session(player, grid, true) : null;
            protectedBuilt = offer;
            protectedOnly.clear();
        }
        return protectedSession;
    }

    /**
     * Direct vs deferred vs unsolvable over the plain pool, widened by the protected tier
     * (issue #40): a recipe the plain session cannot make but the protected one can reports
     * {@link CraftPlanner.Craftability#DEFERRED} — never direct, since vanilla's own placement
     * refuses enchanted/renamed stacks — and is remembered so the confirm sends the protected
     * start ({@link #isProtectedOnly}).
     */
    public static CraftPlanner.Craftability classify(EntityPlayer player, GridContext grid, IRecipe recipe) {
        CraftPlanner.Craftability plain = get(player, grid).classify(recipe);
        if (plain != CraftPlanner.Craftability.UNSOLVABLE) {
            return plain;
        }
        CraftPlanner.Session protectedTier = protectedSession(player, grid);
        if (protectedTier != null && protectedTier.classify(recipe) != CraftPlanner.Craftability.UNSOLVABLE) {
            if (recipe.getRegistryName() != null) {
                protectedOnly.add(recipe.getRegistryName());
            }
            return CraftPlanner.Craftability.DEFERRED;
        }
        return CraftPlanner.Craftability.UNSOLVABLE;
    }

    /** Whether {@code recipe} is makeable only by consuming protected stacks (see {@link #classify}). */
    public static boolean isProtectedOnly(IRecipe recipe) {
        return recipe != null && recipe.getRegistryName() != null
                && protectedOnly.contains(recipe.getRegistryName());
    }

    /**
     * A full plan for the click preview, from the same session the outlines used. A recipe only
     * the protected tier can make gets that plan, with {@code usesProtected} set — the preview's
     * warning lines and the confirm click both key off it.
     */
    public static CraftPlanner.PlanOutcome plan(EntityPlayer player, GridContext grid, IRecipe recipe) {
        CraftPlanner.PlanOutcome outcome = get(player, grid).plan(recipe);
        if (outcome instanceof CraftPlanner.PlanOutcome.Planned) {
            return outcome;
        }
        CraftPlanner.Session protectedTier = protectedSession(player, grid);
        if (protectedTier != null) {
            CraftPlanner.PlanOutcome withProtected = protectedTier.plan(recipe);
            if (withProtected instanceof CraftPlanner.PlanOutcome.Planned) {
                ((CraftPlanner.PlanOutcome.Planned) withProtected).job().setUsesProtected(true);
                return withProtected;
            }
        }
        return outcome; // the plain rejection, with its own message semantics
    }

    /**
     * Whether {@code result} can be deferred-crafted from current stock, counting the protected
     * tier too (issue #40) — the JEI "+" fallback, which has no recipe object to classify.
     */
    public static boolean canDeferCraft(EntityPlayer player, GridContext grid, ItemStack result) {
        if (get(player, grid).canDeferCraft(result)) {
            return true;
        }
        CraftPlanner.Session protectedTier = protectedSession(player, grid);
        return protectedTier != null && protectedTier.canDeferCraft(result);
    }

    private static int inventorySignature(EntityPlayer player) {
        int hash = 1;
        for (int i = 0; i < CraftPlanner.MAIN_INVENTORY_SIZE; i++) {
            hash = 31 * hash + stackHash(player.inventory.mainInventory.get(i));
        }
        // Fold in the open container's other input slots — its crafting grid and a station's
        // connected chest — so the highlight refreshes when those change even though the player's own
        // 36 slots are untouched (items moved into the grid, a hopper feeds the chest). Empty when no
        // crafting container is open.
        for (Slot slot : ExternalSlots.materialSlots(player)) {
            hash = 31 * hash + stackHash(slot.getStack());
        }
        // An open network crafting terminal (AE2): the network is material while it is open, so its
        // displayed contents are part of the key (0 when no terminal is open). The provider throttles
        // this to a few ticks so a busy network does not rebuild the session every frame.
        hash = 31 * hash + NetworkTerminals.clientSignature(player);
        return hash;
    }

    private static int stackHash(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        int hash = (Item.getIdFromItem(stack.getItem()) * 31 + stack.getMetadata()) * 31 + stack.getCount();
        // The stack's protected state is part of the key (issue #40): renaming or enchanting a stack
        // in place changes neither item, metadata nor count, but moves it between the plain and the
        // protected pool — and with it every protected-only classification. Damaged stacks have
        // their meta (the damage) hashed above already.
        if (stack.isItemEnchanted()) {
            hash = hash * 2 + 1;
        }
        if (stack.hasDisplayName()) {
            hash = hash * 3 + 2;
        }
        return hash;
    }
}
